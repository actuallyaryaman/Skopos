package org.a4real.skopos.runtime

import android.content.ContentResolver
import android.content.SharedPreferences
import android.provider.ContactsContract
import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.core.ScopeConstraint
import org.a4real.skopos.core.SkoposContract

/**
 * The runtime's picture of the authoritative policy: a [ContactScope] plus the aggregate
 * contact ids it resolves to, swapped atomically on policy change.
 *
 * Read source is the injected process's RemotePreferences snapshot ([XposedModule] side).
 * The snapshot is delivered at construction; update pushes arrive on the preference-change
 * listener and trigger a re-resolve. Resolution and every exchange with the provider run
 * under [Reentrancy] so Skopos's own reads are not scoped or wrapped by its own hooks.
 *
 * Absent or unparsable policy fails closed to [ContactScope.Empty]: a target that no scope
 * was ever written for sees zero contacts until the manager writes one.
 */
class PolicyCache(private val prefs: SharedPreferences, private val resolver: ContentResolver) {

    @Volatile
    private var snapshot = Snapshot(ContactScope.Empty, emptySet())

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SkoposContract.POLICY_KEY) refresh()
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        refresh()
    }

    fun current(): Snapshot = snapshot

    private fun refresh() {
        val encoded = prefs.getString(SkoposContract.POLICY_KEY, null)
        val scope = ContactScope.decode(encoded)
        val allowedIds = if (scope is ContactScope.Selected) {
            resolveLookupKeys(scope.lookupKeys)
        } else {
            emptySet()
        }
        snapshot = Snapshot(scope, allowedIds)
    }

    fun resolveLookupKeys(lookupKeys: Set<String>): Set<Long> {
        if (lookupKeys.isEmpty()) return emptySet()
        val ids = mutableSetOf<Long>()
        Reentrancy.withGuard {
            lookupKeys.toList().chunked(ScopeConstraint.MAX_IN_LIST).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                resolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts._ID),
                    "${ContactsContract.Contacts.LOOKUP_KEY} IN ($placeholders)",
                    chunk.toTypedArray(),
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        ids.add(cursor.getLong(0))
                    }
                }
            }
        }
        return ids
    }

    data class Snapshot(val scope: ContactScope, val allowedIds: Set<Long>)
}

/** Re-entrancy guard for the hook's own ContactsProvider traffic. */
internal object Reentrancy {
    private val active = ThreadLocal.withInitial { false }

    inline fun <T> withGuard(block: () -> T): T {
        if (active.get()) return block()
        active.set(true)
        try {
            return block()
        } finally {
            active.set(false)
        }
    }

    val isActive: Boolean
        get() = active.get()
}