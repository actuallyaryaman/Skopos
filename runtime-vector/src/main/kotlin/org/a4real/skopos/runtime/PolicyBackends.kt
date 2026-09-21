package org.a4real.skopos.runtime

import android.content.ContentResolver
import android.content.ContentUris
import android.content.SharedPreferences
import android.provider.ContactsContract
import android.util.Log
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
    @Volatile
    private var transientLogged = false

    override fun resolveKeys(lookupKeys: List<String>): KeyResolution {
        if (lookupKeys.isEmpty()) return KeyResolution(emptySet(), 0)
        val ids = mutableSetOf<Long>()
        var transientFailures = 0
        Reentrancy.withGuard {
            // Durable per-key resolution: the provider's lookup path (exact key, then the
            // key's constituent raw-contact ids) survives aggregate recreation after edits,
            // merges and splits, where raw LOOKUP_KEY equality would miss. Outcomes stay
            // distinct: clean nulls (deleted or mid-reaggregation) vs transient exceptions
            // (busy/dead provider), so callers can fail closed now and still retry later.
            lookupKeys.forEach { key ->
                if (key.isEmpty()) return@forEach
                val id = runCatching {
                    val lookupUri = ContactsContract.Contacts.CONTENT_LOOKUP_URI
                        .buildUpon()
                        .appendPath(key)
                        .build()
                    val current = ContactsContract.Contacts.lookupContact(resolver, lookupUri)
                        ?: return@runCatching null
                    ContentUris.parseId(current)
                }.onFailure { e ->
                    transientFailures++
                    if (!transientLogged) {
                        transientLogged = true
                        Log.w(
                            "SkoposRuntime",
                            "lookup transient failure (${e::class.java.simpleName}), will retry",
                        )
                    }
                }.getOrNull()
                if (id == null) {
                    Log.d("SkoposRuntime", "lookup unresolved: $key")
                } else {
                    transientLogged = false
                    Log.d("SkoposRuntime", "lookup resolved: $key -> $id")
                    ids.add(id)
                }
            }
        }
        return KeyResolution(ids, transientFailures)
    }
}