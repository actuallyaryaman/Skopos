package org.a4real.skopos.runtime

import android.content.ContentResolver
import android.content.ContentUris
import android.content.SharedPreferences
import android.provider.ContactsContract
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
            // Durable per-key resolution: the provider's lookup path (exact key, then the
            // key's constituent raw-contact ids) survives aggregate recreation after edits,
            // merges and splits, where raw LOOKUP_KEY equality would miss. Unresolvable keys
            // are skipped individually so one bad key never drops the whole selection.
            lookupKeys.forEach { key ->
                runCatching {
                    if (key.isEmpty()) return@forEach
                    val lookupUri = ContactsContract.Contacts.CONTENT_LOOKUP_URI
                        .buildUpon()
                        .appendPath(key)
                        .build()
                    val current = ContactsContract.Contacts.lookupContact(resolver, lookupUri)
                        ?: return@forEach
                    ids.add(ContentUris.parseId(current))
                }
            }
        }
        return ids
    }
}