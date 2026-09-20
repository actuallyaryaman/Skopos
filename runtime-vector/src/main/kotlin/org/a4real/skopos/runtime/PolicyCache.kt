package org.a4real.skopos.runtime

import android.util.Log
import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.core.PolicyState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The runtime's picture of the authoritative policy: a [PolicyState] plus the aggregate
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
 *    once, keeping lifecycle-deferral and policy failure distinguishable;
 *  - absence of a stored policy is [PolicyState.Unset] (bypass), never confused with an
 *    explicit empty scope; a malformed value is [PolicyState.Corrupt] (fail-closed).
 *
 * Synchronization: slow provider I/O always runs outside [lock]. Each load captures a version
 * stamp and publishes only if still current, so a newer preference-change or observer reload
 * can never be overwritten by an older in-flight one. No lock is held across RemotePreferences
 * or ContactsProvider calls, and no callback runs while holding [lock], so these paths cannot
 * deadlock through framework callbacks.
 */
internal class PolicyCache(
    private val source: PolicySource,
    private val resolverProvider: () -> PolicyResolver?,
    private val observerStarter: ((onFire: () -> Unit) -> ObserverHandle?)? = null,
) {

    @Volatile
    private var snapshot = Snapshot(PolicyState.Configured(ContactScope.Empty), emptySet())

    @Volatile
    private var initialized = false

    private val subscribed = AtomicBoolean(false)

    @Volatile
    private var activeResolver: PolicyResolver? = null

    @Volatile
    private var version = 0L

    @Volatile
    private var failureLogged = false

    @Volatile
    private var corruptLogged = false

    @Volatile
    private var resolveFailed = false

    @Volatile
    private var observerHandle: ObserverHandle? = null

    private val lock = Any()

    fun current(): Snapshot = snapshot

    /** True when THIS call brought the cache out of the uninitialized state. */
    fun ensureInitialized(): Boolean {
        if (initialized) return false
        val resolver = synchronized(lock) {
            if (initialized) return false
            resolverProvider()?.also { activeResolver = it } ?: return false
        }
        if (subscribed.compareAndSet(false, true)) {
            source.onChanged { refreshFromExternal() }
        }
        if (!reload(resolver)) return false
        synchronized(lock) {
            if (initialized) return false
            initialized = true
            return true
        }
    }

    /**
     * Re-reads the policy and re-resolves lookup keys. Entry point for both the
     * RemotePreferences change callback and the contacts-database observer; safe to call from
     * any thread, and concurrent reloads serialize by version stamp (newest wins).
     */
    fun refreshFromExternal(): Boolean {
        val resolver = synchronized(lock) { activeResolver } ?: return false
        return reload(resolver)
    }

    private fun reload(resolver: PolicyResolver): Boolean {
        val stamp = synchronized(lock) {
            ++version
            version
        }
        val built = try {
            buildSnapshot(resolver)
        } catch (e: Throwable) {
            if (!failureLogged) {
                failureLogged = true
                Log.w(
                    "SkoposRuntime",
                    "policy reload failed (fail-closed EMPTY): ${e.message}",
                )
            }
            // Still serve fail-closed, but report unhealthy so initialization retries.
            synchronized(lock) {
                if (stamp != version) return false
                snapshot = Snapshot(PolicyState.Configured(ContactScope.Empty), emptySet())
                syncObserverLocked()
            }
            return false
        }
        synchronized(lock) {
            if (stamp != version) return false
            snapshot = built
            syncObserverLocked()
            return true
        }
    }

    private fun buildSnapshot(resolver: PolicyResolver): Snapshot {
        val present = source.contains()
        val encoded = source.encoded()
        if (!present || encoded == null) return Snapshot(PolicyState.Unset, emptySet())
        val scope = ContactScope.decode(encoded)
        if (scope is ContactScope.Empty && encoded != "EMPTY") {
            if (!corruptLogged) {
                corruptLogged = true
                Log.w(
                    "SkoposRuntime",
                    "policy corrupt (mode=${encoded.substringBefore('\n')}), fail-closed",
                )
            }
            return Snapshot(PolicyState.Corrupt, emptySet())
        }
        val state = PolicyState.Configured(scope)
        val allowedIds = if (scope is ContactScope.Selected) {
            val ids = runCatching { resolver.resolveKeys(scope.lookupKeys.toList()) }.getOrNull()
            if (ids == null) {
                // Keys stay authoritative in [state]; only the resolved numeric set goes
                // fail-closed. The next observer event or policy refresh retries.
                if (!resolveFailed) {
                    resolveFailed = true
                    Log.w("SkoposRuntime", "key resolution failed (fail-closed, will retry)")
                }
                emptySet()
            } else {
                resolveFailed = false
                ids
            }
        } else {
            emptySet()
        }
        return Snapshot(state, allowedIds)
    }

    /**
     * Central observer ownership: exactly one registration exists, only while the published
     * snapshot is a configured SELECTED scope. Evaluated on every publish, so both
     * preference-change and init paths converge here; the entry never touches the observer.
     */
    private fun syncObserverLocked() {
        val selected =
            (snapshot.state as? PolicyState.Configured)?.scope is ContactScope.Selected
        val starter = observerStarter
        if (selected && observerHandle == null && starter != null) {
            observerHandle = runCatching { starter { refreshFromExternal() } }.getOrNull()
        } else if (!selected && observerHandle != null) {
            runCatching { observerHandle?.stop() }
            observerHandle = null
        }
    }

    data class Snapshot(val state: PolicyState, val allowedIds: Set<Long>)
}

/** The durable policy read side; adapts Vector remote prefs in the real module. */
internal interface PolicySource {
    fun encoded(): String?
    fun contains(): Boolean
    fun onChanged(handler: () -> Unit)
}

/** Key-to-id resolution against ContactsProvider; the only piece needing the app context. */
internal interface PolicyResolver {
    fun resolveKeys(lookupKeys: List<String>): Set<Long>
}
