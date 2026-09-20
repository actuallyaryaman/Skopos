package org.a4real.skopos.runtime

import android.content.ContentResolver
import android.content.SharedPreferences
import android.provider.ContactsContract
import org.a4real.skopos.core.ScopeConstraint
import org.a4real.skopos.core.SkoposContract

/** [PolicySource] over the injected process's RemotePreferences snapshot. */
internal class PrefsPolicySource(private val prefs: SharedPreferences) : PolicySource {
    override fun encoded(): String? = prefs.getString(SkoposContract.POLICY_KEY, null)

    override fun contains(): Boolean = prefs.contains(SkoposContract.POLICY_KEY)

    override fun onChanged(handler: () -> Unit) {
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == SkoposContract.POLICY_KEY) handler()
        }
    }
}

/** [PolicyResolver] reading aggregate ids through the target application's ContentResolver. */
internal class AppPolicyResolver(private val resolver: ContentResolver) : PolicyResolver {
    override fun resolveKeys(lookupKeys: List<String>): Set<Long> {
        if (lookupKeys.isEmpty()) return emptySet()
        val ids = mutableSetOf<Long>()
        Reentrancy.withGuard {
            lookupKeys.chunked(ScopeConstraint.MAX_IN_LIST).forEach { chunk ->
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
}