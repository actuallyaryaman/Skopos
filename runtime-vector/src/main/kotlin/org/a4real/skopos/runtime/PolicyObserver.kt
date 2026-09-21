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
 *  - after each debounced refresh, exactly one follow-up refresh runs ~4s later (never
 *    chained) to cover a ContactsProvider aggregation window where the first callback
 *    lands before the new aggregate is resolvable; leaving SELECTED cancels everything;
 *  - [stop] unregisters, cancels pending work and shuts the executor down without awaiting,
 *    so leaving SELECTED releases everything promptly.
 *
 * Constructed only via [starter], which resolves the ContentResolver lazily at start time so a
 * deferred (Application-not-ready) init path simply yields no observer until a later publish
 * re-evaluates.
 */
internal object PolicyObserver {

    private const val DEBOUNCE_MS = 1750L
    private const val FOLLOWUP_MS = 4000L

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
            var followup: ScheduledFuture<*>? = null
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    pending?.cancel(false)
                    followup?.cancel(false)
                    pending = executor.schedule({
                        onFire()
                        // One bounded follow-up, never chained: covers an aggregation window
                        // where the first callback lands before the new aggregate resolves.
                        followup = executor.schedule({ onFire() }, FOLLOWUP_MS, TimeUnit.MILLISECONDS)
                    }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
                }
            }
            resolver.registerContentObserver(ContactsContract.AUTHORITY_URI, true, observer)
            ObserverHandle {
                runCatching { resolver.unregisterContentObserver(observer) }
                pending?.cancel(false)
                followup?.cancel(false)
                executor.shutdown()
            }
        }.getOrNull()
}
