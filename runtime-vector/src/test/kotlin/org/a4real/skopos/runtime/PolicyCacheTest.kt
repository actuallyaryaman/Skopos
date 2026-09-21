package org.a4real.skopos.runtime

import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.core.PolicyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The deferred-initialization contract the physical-device run leans on. These run on the host
 * JVM against the pure state seam of [PolicyCache] — the same seam the runtime uses to resume
 * after package-ready while the target Application is still being created:
 *
 *  1. a call before the resolver is available (package-ready, no Application yet) is NOT a
 *     failure — it returns false, keeps serving the fail-closed EMPTY snapshot, and retries;
 *  2. once the resolver appears, the next call initializes exactly once (no double
 *     subscription, no re-read) and reports true;
 *  3. a real resolver failure keeps the fail-closed EMPTY snapshot and reports false;
 *  4. absence of a stored policy is [PolicyState.Unset] (bypass), never explicit EMPTY;
 *  5. malformed values are [PolicyState.Corrupt] (fail-closed), distinct from absence.
 */
class PolicyCacheTest {

    private class MemorySource(
        var encoded: String?,
        var present: Boolean = true,
    ) : PolicySource {
        var listenerCount = 0
            private set
        var handler: (() -> Unit)? = null
            private set

        override fun encoded(): String? = encoded

        override fun contains(): Boolean = present

        override fun onChanged(handler: () -> Unit) {
            listenerCount++
            this.handler = handler
        }

        fun fire() {
            handler?.invoke()
        }
    }

    private class FakeResolver(
        private var keyToId: Map<String, Long>,
        var failTransient: Boolean = false,
    ) : PolicyResolver {
        var calls = 0
        var gate: CountDownLatch? = null
        var enteredGate = CountDownLatch(1)

        override fun resolveKeys(lookupKeys: List<String>): KeyResolution {
            calls++
            gate?.let {
                enteredGate.countDown()
                assertTrue(it.await(5, TimeUnit.SECONDS))
            }
            if (failTransient) return KeyResolution(emptySet(), 1)
            return KeyResolution(lookupKeys.mapNotNull { keyToId[it] }.toSet(), 0)
        }

        fun forget(key: String) {
            keyToId = keyToId - key
        }
    }

    private class FakeStarter {
        var starts = 0
        var stops = 0

        val fn: ((onFire: () -> Unit) -> ObserverHandle?) = {
            starts++
            ObserverHandle { stops++ }
        }
    }

    private fun encodedSelected(vararg lookupKeys: String): String {
        val scope = ContactScope.Selected(lookupKeys.toSet())
        return scope.encode()
    }

    @Test
    fun `deferred before resolver seam is available - keeps EMPTY, not failure`() {
        val source = MemorySource(encodedSelected("phone"))
        var resolver: FakeResolver? = null
        val cache = PolicyCache(source, resolverProvider = { resolver })

        assertFalse(cache.ensureInitialized())
        assertEquals(
            PolicyState.Configured(ContactScope.Empty),
            cache.current().state,
        )
        assertTrue(cache.current().allowedIds.isEmpty())

        // Deferral is retriable, not a durable failure: no listener subscribed yet, so a later
        // availability can still subscribe once.
        assertFalse(cache.ensureInitialized())
        assertEquals(0, source.listenerCount)

        resolver = FakeResolver(mapOf("phone" to 7L))

        assertTrue(cache.ensureInitialized())
        assertEquals(
            PolicyState.Configured(ContactScope.Selected(setOf("phone"))),
            cache.current().state,
        )
        assertEquals(setOf(7L), cache.current().allowedIds)
        assertEquals(1, resolver.calls)
        assertEquals(1, source.listenerCount)

        // Idempotent: a second call does not re-read or re-subscribe.
        assertFalse(cache.ensureInitialized())
        assertEquals(1, resolver.calls)
        assertEquals(1, source.listenerCount)
    }

    @Test
    fun `whole-call resolver throw is transient then recovers`() {
        val source = MemorySource(encodedSelected("phone"))
        val resolver = object : PolicyResolver {
            var fail = true
            override fun resolveKeys(lookupKeys: List<String>): KeyResolution {
                if (fail) error("ContactsProvider unavailable")
                return KeyResolution(setOf(7L), 0)
            }
        }
        val cache = PolicyCache(source, resolverProvider = { resolver })

        assertTrue(cache.ensureInitialized())
        assertEquals(
            PolicyState.Configured(ContactScope.Selected(setOf("phone"))),
            cache.current().state,
        )
        assertTrue(cache.current().allowedIds.isEmpty())

        resolver.fail = false
        source.fire()
        assertEquals(setOf(7L), cache.current().allowedIds)
    }

    @Test
    fun `source failure keeps fail-closed EMPTY and reports false`() {
        val source = object : PolicySource {
            override fun encoded(): String? = error("prefs down")
            override fun contains(): Boolean = true
            override fun onChanged(handler: () -> Unit) {}
        }
        val cache = PolicyCache(source, resolverProvider = { FakeResolver(emptyMap()) })

        assertFalse(cache.ensureInitialized())
        assertEquals(
            PolicyState.Configured(ContactScope.Empty),
            cache.current().state,
        )
        assertTrue(cache.current().allowedIds.isEmpty())
    }

    @Test
    fun `absent preference decodes to UNSET`() {
        val source = MemorySource(encoded = null, present = false)
        val cache = PolicyCache(source, resolverProvider = { FakeResolver(emptyMap()) })

        assertTrue(cache.ensureInitialized())
        assertEquals(PolicyState.Unset, cache.current().state)
        assertTrue(cache.current().allowedIds.isEmpty())
    }

    @Test
    fun `explicit EMPTY FULL SELECTED decode distinctly`() {
        val empty = PolicyCache(MemorySource("EMPTY"), resolverProvider = { FakeResolver(emptyMap()) })
        assertTrue(empty.ensureInitialized())
        assertEquals(PolicyState.Configured(ContactScope.Empty), empty.current().state)

        val full = PolicyCache(MemorySource("FULL"), resolverProvider = { FakeResolver(emptyMap()) })
        assertTrue(full.ensureInitialized())
        assertEquals(PolicyState.Configured(ContactScope.Full), full.current().state)

        val selected = PolicyCache(
            MemorySource(encodedSelected("a")),
            resolverProvider = { FakeResolver(mapOf("a" to 3L)) },
        )
        assertTrue(selected.ensureInitialized())
        assertEquals(
            PolicyState.Configured(ContactScope.Selected(setOf("a"))),
            selected.current().state,
        )
        assertEquals(setOf(3L), selected.current().allowedIds)
    }

    @Test
    fun `malformed value is Corrupt fail-closed, not Unset`() {
        val cache = PolicyCache(MemorySource("BOGUS\nk1"), resolverProvider = { FakeResolver(emptyMap()) })
        assertTrue(cache.ensureInitialized())
        assertEquals(PolicyState.Corrupt, cache.current().state)
        assertTrue(cache.current().allowedIds.isEmpty())
    }

    @Test
    fun `reset to absent returns to UNSET`() {
        val source = MemorySource(encodedSelected("phone"))
        val cache = PolicyCache(source, resolverProvider = { FakeResolver(mapOf("phone" to 7L)) })
        assertTrue(cache.ensureInitialized())
        assertTrue(cache.current().state is PolicyState.Configured)

        source.present = false
        source.encoded = null
        source.fire()

        assertEquals(PolicyState.Unset, cache.current().state)
        assertTrue(cache.current().allowedIds.isEmpty())
    }

    @Test
    fun `per-package sources stay isolated`() {
        val sourceA = MemorySource(encodedSelected("a"))
        val sourceB = MemorySource("EMPTY")
        val cacheA = PolicyCache(sourceA, resolverProvider = { FakeResolver(mapOf("a" to 1L)) })
        val cacheB = PolicyCache(sourceB, resolverProvider = { FakeResolver(emptyMap()) })
        assertTrue(cacheA.ensureInitialized())
        assertTrue(cacheB.ensureInitialized())

        assertEquals(
            PolicyState.Configured(ContactScope.Selected(setOf("a"))),
            cacheA.current().state,
        )
        assertEquals(PolicyState.Configured(ContactScope.Empty), cacheB.current().state)

        sourceA.present = false
        sourceA.encoded = null
        sourceA.fire()
        assertEquals(PolicyState.Unset, cacheA.current().state)
        assertEquals(PolicyState.Configured(ContactScope.Empty), cacheB.current().state)
    }

    @Test
    fun `observer starts on SELECTED and stops when leaving it`() {
        val source = MemorySource(encodedSelected("phone"))
        val starter = FakeStarter()
        val cache = PolicyCache(source, resolverProvider = { FakeResolver(mapOf("phone" to 7L)) }, observerStarter = starter.fn)
        assertTrue(cache.ensureInitialized())
        assertEquals(1, starter.starts)
        assertEquals(0, starter.stops)

        source.encoded = "EMPTY"
        source.fire()

        assertEquals(PolicyState.Configured(ContactScope.Empty), cache.current().state)
        assertEquals(1, starter.starts)
        assertEquals(1, starter.stops)
    }

    @Test
    fun `transient failure preserves good snapshot then recovers`() {
        val source = MemorySource(encodedSelected("phone"))
        val resolver = FakeResolver(mapOf("phone" to 7L))
        val cache = PolicyCache(source, resolverProvider = { resolver })
        assertTrue(cache.ensureInitialized())
        assertEquals(setOf(7L), cache.current().allowedIds)

        // Same policy, transient failure: must not overwrite the good snapshot.
        resolver.failTransient = true
        source.fire()
        assertEquals(setOf(7L), cache.current().allowedIds)
        assertEquals(
            PolicyState.Configured(ContactScope.Selected(setOf("phone"))),
            cache.current().state,
        )

        resolver.failTransient = false
        source.fire()
        assertEquals(setOf(7L), cache.current().allowedIds)
    }

    @Test
    fun `clean miss publishes empty immediately and stays fail-closed`() {
        val source = MemorySource(encodedSelected("phone"))
        val resolver = FakeResolver(mapOf("phone" to 7L))
        val cache = PolicyCache(source, resolverProvider = { resolver })
        assertTrue(cache.ensureInitialized())
        assertEquals(setOf(7L), cache.current().allowedIds)

        // Genuine deletion: the key no longer resolves cleanly (no transient involved).
        resolver.forget("phone")
        source.fire()

        assertEquals(
            PolicyState.Configured(ContactScope.Selected(setOf("phone"))),
            cache.current().state,
        )
        assertTrue(cache.current().allowedIds.isEmpty())

        // Still fail-closed on the next refresh with nothing new to resolve.
        source.fire()
        assertTrue(cache.current().allowedIds.isEmpty())
    }

    @Test
    fun `newer FULL policy wins immediately over SELECTED`() {
        val source = MemorySource(encodedSelected("phone"))
        val cache = PolicyCache(source, resolverProvider = { FakeResolver(mapOf("phone" to 7L)) })
        assertTrue(cache.ensureInitialized())
        assertEquals(setOf(7L), cache.current().allowedIds)

        source.encoded = "FULL"
        source.fire()

        assertEquals(PolicyState.Configured(ContactScope.Full), cache.current().state)
        assertTrue(cache.current().allowedIds.isEmpty())
    }

    @Test
    fun `slow success is not discarded by newer transient failure`() {
        val source = MemorySource(encodedSelected("phone"))
        val resolver = FakeResolver(mapOf("phone" to 7L))
        val cache = PolicyCache(source, resolverProvider = { resolver })
        assertTrue(cache.ensureInitialized())
        assertEquals(setOf(7L), cache.current().allowedIds)

        // A slow in-flight reload (same policy) vs a fast transient failure: the failure
        // must not overwrite the good snapshot, and the late success must still publish.
        val slowGate = CountDownLatch(1)
        resolver.gate = slowGate
        resolver.enteredGate = CountDownLatch(1)
        var slowResult = false
        val slow = thread { slowResult = cache.refreshFromExternal() }
        assertTrue(resolver.enteredGate.await(5, TimeUnit.SECONDS))
        // The slow call is parked inside await on slowGate; clear the field so the fast
        // reload below does not block on it.
        resolver.gate = null

        resolver.failTransient = true
        assertFalse(cache.refreshFromExternal())
        assertEquals(setOf(7L), cache.current().allowedIds)

        resolver.failTransient = false
        slowGate.countDown()
        slow.join(5000)
        assertTrue(slowResult)
        assertEquals(setOf(7L), cache.current().allowedIds)
    }

    @Test
    fun `follow-up schedule is bounded and finite`() {
        val delays = PolicyObserver.followupDelaysMs
        assertEquals(3, delays.size)
        assertTrue(delays.all { it > 0 })
        assertEquals(delays.sorted(), delays)
    }
}
