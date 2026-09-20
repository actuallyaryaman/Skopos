package org.a4real.skopos.runtime

import org.a4real.skopos.core.ContactScope
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
 *  3. a real resolver failure keeps the fail-closed EMPTY snapshot and reports false.
 */
class PolicyCacheTest {

    private class MemorySource(initial: String?) : PolicySource {
        var encoded: String? = initial
        var listenerCount = 0
            private set

        override fun encoded(): String? = encoded

        override fun onChanged(handler: () -> Unit) {
            listenerCount++
        }
    }

    private class FakeResolver(private val keyToId: Map<String, Long>) : PolicyResolver {
        var calls = 0
        override fun resolveKeys(lookupKeys: List<String>): Set<Long> {
            calls++
            return lookupKeys.mapNotNull { keyToId[it] }.toSet()
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
        val cache = PolicyCache(source) { resolver }

        assertFalse(cache.ensureInitialized())
        assertEquals(ContactScope.Empty, cache.current().scope)
        assertTrue(cache.current().allowedIds.isEmpty())

        // Deferral is retriable, not a durable failure: no listener subscribed yet, so a later
        // availability can still subscribe once.
        assertFalse(cache.ensureInitialized())
        assertEquals(0, source.listenerCount)

        resolver = FakeResolver(mapOf("phone" to 7L))

        assertTrue(cache.ensureInitialized())
        assertEquals(ContactScope.Selected::class.java, cache.current().scope.javaClass)
        assertEquals(setOf(7L), cache.current().allowedIds)
        assertEquals(1, resolver!!.calls)
        assertEquals(1, source.listenerCount)

        // Idempotent: a second call does not re-read or re-subscribe.
        assertFalse(cache.ensureInitialized())
        assertEquals(1, resolver.calls)
        assertEquals(1, source.listenerCount)
    }

    @Test
    fun `resolver failure keeps fail-closed EMPTY and still serves safely`() {
        val source = MemorySource(encodedSelected("phone"))
        val cache =
            PolicyCache(source) {
                object : PolicyResolver {
                    override fun resolveKeys(lookupKeys: List<String>): Set<Long> =
                        error("ContactsProvider unavailable")
                }
            }

        assertFalse(cache.ensureInitialized())
        assertEquals(ContactScope.Empty, cache.current().scope)
        assertTrue(cache.current().allowedIds.isEmpty())
    }
}
