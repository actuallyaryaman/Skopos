package org.a4real.skopos.runtime

/**
 * An out-of-band, thread-local re-entrancy guard for the runtime's own ContactsProvider-side
 * reads (aggregate-id resolution, PhoneLookup scoping, policy refresh). These reads go through
 * the very [android.content.ContentResolver.query]/[ContentProviderClient.query] methods the
 * module hooks, so without a guard a policy resolution issued while already inside a scoped
 * query would feed itself back into the hook and loop.
 *
 * The guard lives on its own thread (a [ThreadLocal]), never leaking into the query's URI,
 * selection, or selectionArgs — the query surface stays byte-for-byte free of runtime markers.
 * It is:
 *
 *  - re-entrant / nested-correct: a [withGuard] call that is already inside an outer guard on
 *    the same thread is a pass-through no-op (the outer guard remains active);
 *  - exception-safe: the active state is always released in a [finally], even when the guarded
 *    block throws;
 *  - zero-allocation on the pass-through path: the common case (a normal, non-runtime query)
 *    reads one [ThreadLocal.get] and proceeds.
 *
 * Kept as its own small seam so the contacts query hook and the policy backends share one
 * notion of "this call is ours" without threading an android Application through either.
 */
internal object Reentrancy {

    private val active = ThreadLocal.withInitial { false }

    val isActive: Boolean
        get() = active.get() == true

    /** Runs [block] with the runtime re-entrancy guard raised; nested calls pass through. */
    inline fun <T> withGuard(block: () -> T): T {
        if (active.get() == true) return block()
        active.set(true)
        try {
            return block()
        } finally {
            active.set(false)
        }
    }
}
