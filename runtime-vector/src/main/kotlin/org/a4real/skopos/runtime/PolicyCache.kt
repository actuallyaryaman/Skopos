package org.a4real.skopos.runtime

import android.util.Log
import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.core.SkoposContract

/**
 * The runtime's picture of the authoritative policy: a [ContactScope] plus the aggregate
 * contact ids it resolves to, swapped atomically on policy change.
 *
 * The module entry constructs it at package-ready, which is BEFORE the target Application
 * exists; only the [PolicyResolver] (which reads ContactsProvider through the app's
 * ContentResolver) needs the Application. The two android sides are therefore behind small
 * seams, and the holder itself is pure state: construction costs nothing, and initialization
 * is deferred until either the Application is already available (eager call by the entry) or
 * the first scoped query arrives — a point reached after the Application was created in the
 * observed dispatch order, so the resolver is normally available by then; a miss simply
 * retries on the next query.
 *
 * Behavior rules:
 *  - [ensureInitialized] runs once and is idempotent (no double subscription, no re-read);
 *  - a deferred state (resolver not yet available) is NOT a failure: it retries on the next
 *    call and the current fail-closed snapshot keeps serving;
 *  - a real initialization failure also keeps the fail-closed snapshot and is logged exactly
 *    once, keeping lifecycle-deferral and policy failure distinguishable.
 */
internal class PolicyCache(
    private val source: PolicySource,
    private val resolverProvider: () -> PolicyResolver?,
) {

    @Volatile
    private var snapshot = Snapshot(ContactScope.Empty, emptySet())

    @Volatile
    private var initialized = false

    @Volatile
    private var activeResolver: PolicyResolver? = null

    @Volatile
    private var failureLogged = false

    private val lock = Any()

    fun current(): Snapshot = snapshot

    /** True when THIS call brought the cache out of the uninitialized state. */
    fun ensureInitialized(): Boolean {
        if (initialized) return false
        synchronized(lock) {
            if (initialized) return false
            val resolver = resolverProvider() ?: return false
            activeResolver = resolver
            try {
                source.onChanged { refresh() }
                refresh(resolver)
                initialized = true
                return true
            } catch (e: Throwable) {
                if (!failureLogged) {
                    failureLogged = true
                    Log.w(
                        "SkoposRuntime",
                        "policy cache init failed (fail-closed EMPTY): ${e.message}",
                    )
                }
                snapshot = Snapshot(ContactScope.Empty, emptySet())
                return false
            }
        }
    }

    private fun refresh(resolver: PolicyResolver) {
        val encoded = source.encoded()
        val scope = ContactScope.decode(encoded)
        val allowedIds = if (scope is ContactScope.Selected) {
            resolver.resolveKeys(scope.lookupKeys.toList())
        } else {
            emptySet()
        }
        snapshot = Snapshot(scope, allowedIds)
    }

    private fun refresh() {
        val resolver = activeResolver ?: return
        refresh(resolver)
    }

    data class Snapshot(val scope: ContactScope, val allowedIds: Set<Long>)
}

/** The durable policy read side; adapts Vector remote prefs in the real module. */
internal interface PolicySource {
    fun encoded(): String?
    fun onChanged(handler: () -> Unit)
}

/** Key-to-id resolution against ContactsProvider; the only piece needing the app context. */
internal interface PolicyResolver {
    fun resolveKeys(lookupKeys: List<String>): Set<Long>
}