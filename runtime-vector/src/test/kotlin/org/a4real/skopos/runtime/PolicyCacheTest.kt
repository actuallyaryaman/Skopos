package org.a4real.skopos.runtime

import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.core.PolicyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        private val keyToId: Map<String, Long>,
        var fail: Boolean = false,
    ) : PolicyResolver {
        var calls = 0
        override fun resolveKeys(lookupKeys: List<String>): Set<Long> {
            calls++
            if (fail) error("ContactsProvider unavailable")
            return lookupKeys.mapNotNull { keyToId[it] }.toSet()
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
    fun `resolver failure preserves keys with empty ids and retries`() {
        val source = MemorySource(encodedSelected("phone"))
        val resolver = object : PolicyResolver {
            var fail = true
            override fun resolveKeys(lookupKeys: List<String>): Set<Long> {
                if (fail) error("ContactsProvider unavailable")
                return setOf(7L)
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
    fun `failed post-mutation resolution keeps keys and empties ids`() {
        val source = MemorySource(encodedSelected("phone"))
        val resolver = FakeResolver(mapOf("phone" to 7L))
        val cache = PolicyCache(source, resolverProvider = { resolver })
        assertTrue(cache.ensureInitialized())
        assertEquals(setOf(7L), cache.current().allowedIds)

        resolver.fail = true
        source.fire()

        assertEquals(
            PolicyState.Configured(ContactScope.Selected(setOf("phone"))),
            cache.current().state,
        )
        assertTrue(cache.current().allowedIds.isEmpty())

        resolver.fail = false
        source.fire()
        assertEquals(setOf(7L), cache.current().allowedIds)
    }
}
