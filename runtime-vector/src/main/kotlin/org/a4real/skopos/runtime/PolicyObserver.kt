package org.a4real.skopos.runtime

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Handle for one active contacts-database observation; [stop] is idempotent. */
internal fun interface ObserverHandle {
    fun stop()
}

/**
 * Re-resolution trigger for SELECTED scopes: observes the whole contacts provider and fires
 * [onFire] (debounced) when the database mutates, so aggregate-id changes from edits, merges
 * or splits are picked up without per-query resolution.
 *
 * Threading, by constraint:
 *  - the observer itself delivers on the main looper (always present: registration happens
 *    only after the Application exists), so [ContentObserver.onChange] only cancels and
 *    reschedules — never blocks the UI thread and never touches the provider;
 *  - a lazily created single-thread [ScheduledExecutorService] provides debounce delay plus
 *    serialization (one refresh at a time) in a single primitive — no permanent HandlerThread
 *    in processes that never select into SELECTED, and none held after leaving it;
 *  - after each debounced refresh, a finite set of follow-ups re-resolves (never
 *    chained, never re-armed by follow-ups themselves) to cover a ContactsProvider
 *    aggregation window where the first callback lands before the new aggregate is
 *    resolvable; leaving SELECTED cancels everything;
 *  - [stop] unregisters, cancels pending work and shuts the executor down without awaiting,
 *    so leaving SELECTED releases everything promptly.
 *
 * Constructed only via [starter], which resolves the ContentResolver lazily at start time so a
 * deferred (Application-not-ready) init path simply yields no observer until a later publish
 * re-evaluates.
 */
internal object PolicyObserver {

    private const val DEBOUNCE_MS = 1750L

    /**
     * Bounded follow-up delays after each debounced refresh, covering a provider
     * aggregation window where the first callback lands before the new aggregate is
     * resolvable. Scheduled once per debounced fire (never chained, never re-armed by
     * follow-ups themselves); any new mutation or stop cancels the whole set.
     */
    internal val followupDelaysMs: List<Long> = listOf(3000L, 6000L, 10000L)

    fun starter(
        resolvers: () -> ContentResolver?,
    ): (onFire: () -> Unit) -> ObserverHandle? = { onFire ->
        val resolver = resolvers()
        if (resolver == null) null else start(resolver, onFire)
    }

    private fun start(resolver: ContentResolver, onFire: () -> Unit): ObserverHandle? =
        runCatching {
            val executor = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "SkoposPolicyObs").apply { isDaemon = true }
            }
            var pending: ScheduledFuture<*>? = null
            val followups = mutableListOf<ScheduledFuture<*>>()
            fun cancelAll() {
                pending?.cancel(false)
                pending = null
                followups.forEach { it.cancel(false) }
                followups.clear()
            }
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    cancelAll()
                    pending = executor.schedule({
                        onFire()
                        followupDelaysMs.forEach { delay ->
                            followups += executor.schedule({ onFire() }, delay, TimeUnit.MILLISECONDS)
                        }
                    }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
                }
            }
            resolver.registerContentObserver(ContactsContract.AUTHORITY_URI, true, observer)
            ObserverHandle {
                runCatching { resolver.unregisterContentObserver(observer) }
                cancelAll()
                executor.shutdown()
            }
        }.getOrNull()
}
