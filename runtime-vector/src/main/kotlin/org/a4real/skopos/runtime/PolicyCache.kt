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
    private var publishedIdentity: PolicyVersion? = null

    @Volatile
    private var failureLogged = false

    @Volatile
    private var corruptLogged = false

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
        val built: BuiltSnapshot = try {
            buildSnapshot(resolver)
        } catch (e: Throwable) {
            if (!failureLogged) {
                failureLogged = true
                Log.w(
                    "SkoposRuntime",
                    "policy reload failed (fail-closed EMPTY): ${e.message}",
                )
            }
            // Nothing was read: claim no new data, so a good snapshot is never
            // overwritten by this failure. Still serve fail-closed when there is
            // nothing good to keep, and report unhealthy so initialization retries.
            synchronized(lock) {
                if (isSuccessSnapshot(snapshot)) return false
                if (stamp != version) return false
                snapshot = Snapshot(PolicyState.Configured(ContactScope.Empty), emptySet())
                syncObserverLocked()
                Log.d("SkoposRuntime", "reload published: EMPTY (after failure)")
            }
            return false
        }
        synchronized(lock) {
            if (built.identity != publishedIdentity) {
                // New policy data wins immediately — but only from the latest attempt,
                // so a stale in-flight load cannot overwrite fresher data.
                if (stamp != version) {
                    Log.d("SkoposRuntime", "reload discarded (stale generation)")
                    return false
                }
            } else if (built.outcome == Outcome.TRANSIENT && isSuccessSnapshot(snapshot)) {
                // Same policy, transient failure: never overwrite a good snapshot with
                // garbage. Bounded retries converge; genuinely newer data always wins.
                Log.d("SkoposRuntime", "reload discarded (transient over success)")
                return false
            }
            snapshot = built.snapshot
            publishedIdentity = built.identity
            syncObserverLocked()
            Log.d("SkoposRuntime", "reload published: ${describe(built.snapshot)}")
            return true
        }
    }

    private fun isSuccessSnapshot(snapshot: Snapshot): Boolean = when (val state = snapshot.state) {
        is PolicyState.Unset -> true
        is PolicyState.Corrupt -> true
        is PolicyState.Configured -> state.scope !is ContactScope.Selected ||
            snapshot.allowedIds.isNotEmpty()
    }

    private fun describe(snapshot: Snapshot): String = when (val state = snapshot.state) {
        is PolicyState.Unset -> "UNSET"
        is PolicyState.Corrupt -> "CORRUPT"
        is PolicyState.Configured -> when (val scope = state.scope) {
            is ContactScope.Full -> "FULL"
            is ContactScope.Empty -> "EMPTY"
            is ContactScope.Selected -> "SELECTED(ids=${snapshot.allowedIds.size})"
        }
    }

    /** Policy data identity behind a snapshot: what was read, not when it was read. */
    private data class PolicyVersion(val present: Boolean, val encoded: String?)

    private enum class Outcome { SUCCESS, CLEAN_MISS, TRANSIENT }

    private data class BuiltSnapshot(
        val snapshot: Snapshot,
        val identity: PolicyVersion,
        val outcome: Outcome,
    )

    private fun buildSnapshot(resolver: PolicyResolver): BuiltSnapshot {
        val present = source.contains()
        val encoded = source.encoded()
        val identity = PolicyVersion(present, encoded)
        if (!present || encoded == null) {
            return BuiltSnapshot(Snapshot(PolicyState.Unset, emptySet()), identity, Outcome.SUCCESS)
        }
        val scope = ContactScope.decode(encoded)
        if (scope is ContactScope.Empty && encoded != "EMPTY") {
            if (!corruptLogged) {
                corruptLogged = true
                Log.w(
                    "SkoposRuntime",
                    "policy corrupt (mode=${encoded.substringBefore('\n')}), fail-closed",
                )
            }
            return BuiltSnapshot(
                Snapshot(PolicyState.Corrupt, emptySet()),
                identity,
                Outcome.SUCCESS,
            )
        }
        val state = PolicyState.Configured(scope)
        if (scope !is ContactScope.Selected) {
            return BuiltSnapshot(Snapshot(state, emptySet()), identity, Outcome.SUCCESS)
        }
        // Keys stay authoritative in [state]; only the resolved numeric set goes
        // fail-closed. A clean miss (deleted or mid-reaggregation) takes effect but stays
        // retryable inside the observer window; a transient failure additionally justifies
        // retry and must never overwrite a good snapshot (see publish rules in reload).
        val resolution = runCatching { resolver.resolveKeys(scope.lookupKeys.toList()) }.getOrNull()
        val ids = resolution?.ids ?: emptySet()
        val outcome = when {
            ids.isNotEmpty() -> Outcome.SUCCESS
            (resolution?.transientFailures ?: 1) > 0 -> Outcome.TRANSIENT
            else -> Outcome.CLEAN_MISS
        }
        return BuiltSnapshot(Snapshot(state, ids), identity, outcome)
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
    fun resolveKeys(lookupKeys: List<String>): KeyResolution
}

/**
 * Outcome of one resolution pass: the ids found plus how many keys failed transiently
 * (busy/dead provider) as opposed to cleanly missing (deleted or mid-reaggregation).
 * Callers fail closed on any empty set, but only transient failure justifies a retry.
 */
internal data class KeyResolution(val ids: Set<Long>, val transientFailures: Int)
