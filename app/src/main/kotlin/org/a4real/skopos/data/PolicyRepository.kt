package org.a4real.skopos.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import io.github.libxposed.service.XposedService
import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.core.PolicyState
import org.a4real.skopos.core.SkoposContract

/**
 * The manager's side of the policy channel, parameterized by target package. Skopos writes each
 * target's scope into its own Vector RemotePreferences group
 * ([SkoposContract.policyGroupFor]) through the service binder the daemon delivers to
 * [io.github.libxposed.service.XposedProvider]; the injected runtime reads the same slot.
 * No other store is authoritative: the manager is the sole policy writer, targets never write.
 *
 * One instance per target package so binders and slots never cross. Until the daemon has
 * handed the app its service the repo reports "not connected" and writes are ignored.
 */
class PolicyRepository private constructor(
    context: Context,
    val targetPackage: String,
) {

    private val appContext = context.applicationContext
    private val markerResolver = appContext.contentResolver

    init {
        // Shared process-wide connection: exactly one XposedServiceHelper listener exists
        // per process (last registration wins), so repositories must never register their
        // own — they only read the shared service.
        DaemonConnection.ensure()
    }

    private val service: XposedService?
        get() = DaemonConnection.service

    val connected: Boolean
        get() = DaemonConnection.connected

    private fun group() = SkoposContract.policyGroupFor(targetPackage)

    /** The durable policy state for the target package; null when the daemon is unreachable. */
    fun currentPolicy(): PolicyState? {
        val bound = service ?: return null
        val prefs = bound.getRemotePreferences(group())
        return PolicyState.decode(
            prefs.getString(SkoposContract.POLICY_KEY, null),
            prefs.contains(SkoposContract.POLICY_KEY),
        )
    }

    /** Backwards-compatible scope read; absence decodes per [PolicyState] (Unset, not Empty). */
    fun currentScope(): ContactScope? {
        return when (val state = currentPolicy()) {
            is PolicyState.Configured -> state.scope
            else -> null
        }
    }

    /**
     * Remembered selection for this package: the stored entry when present, else the legacy
     * fallback (an existing `SELECTED(keys)` scope acts as its own remembered set). Never
     * touches the active scope. Empty when the daemon is unreachable.
     */
    fun readRememberedSelectedKeys(): Set<String> {
        val bound = service ?: return emptySet()
        val prefs = bound.getRemotePreferences(group())
        val present = prefs.contains(RememberedPolicy.REMEMBERED_KEY)
        val stored = RememberedPolicy.decode(prefs.getString(RememberedPolicy.REMEMBERED_KEY, null))
        return RememberedPolicy.effectiveRemembered(present, stored, currentPolicy())
    }

    /**
     * Single coherent write path: active scope plus remembered set in one atomic editor
     * transaction. Callers compute both halves with [RememberedPolicy] first.
     */
    private fun writePolicy(write: RememberedPolicy.PolicyWrite): Boolean {
        val bound = service ?: return false
        return bound.getRemotePreferences(group())
            .edit()
            .putString(SkoposContract.POLICY_KEY, write.scope.encode())
            .putString(RememberedPolicy.REMEMBERED_KEY, RememberedPolicy.encode(write.remembered))
            .commit()
    }

    /** Contact toggle inside SELECTED; deselect-last becomes explicit EMPTY + cleared memory. */
    fun setSelected(nextKeys: Set<String>): Boolean =
        writePolicy(RememberedPolicy.writeForToggle(nextKeys))

    /** Enter FULL, preserving the current-or-remembered selection silently. */
    fun setFullPreservingSelection(): Boolean {
        if (service == null) return false
        val remembered = RememberedPolicy.writeForModeChange(
            currentPolicy(),
            readRememberedSelectedKeys(),
        )
        return writePolicy(RememberedPolicy.PolicyWrite(ContactScope.Full, remembered))
    }

    /** Enter EMPTY, preserving the current-or-remembered selection silently. */
    fun setEmptyPreservingSelection(): Boolean {
        if (service == null) return false
        val remembered = RememberedPolicy.writeForModeChange(
            currentPolicy(),
            readRememberedSelectedKeys(),
        )
        return writePolicy(RememberedPolicy.PolicyWrite(ContactScope.Empty, remembered))
    }

    /** Publish remembered keys as the active SELECTED scope; null = nothing to publish. */
    fun selectRemembered(): Boolean? {
        if (service == null) return false
        val write = RememberedPolicy.writeForSelectTab(readRememberedSelectedKeys()) ?: return null
        return writePolicy(write)
    }

    /**
     * Clears both the active scope and the remembered selection (the whole per-package
     * group). The runtime observes the deletion and returns to [PolicyState.Unset].
     * Remove-from-scope, by contrast, preserves both.
     */
    fun resetContactPolicy(): Boolean {
        val bound = service ?: return false
        return runCatching {
            bound.deleteRemotePreferences(group())
            true
        }.getOrDefault(false)
    }

    /**
     * Packages Vector currently injects this module into (calling user's view), or null when
     * the daemon is unreachable. Read-only; Skopos never edits Vector scope silently.
     */
    fun vectorScope(): List<String>? {
        val bound = service ?: return null
        return runCatching { bound.scope }.getOrNull()
    }

    /**
     * Asks Vector to add [packageName] to this module's scope. Vector always presents its own
     * user-approval prompt; there is no silent path. Result arrives on [listener].
     */
    fun requestVectorScope(
        packageName: String,
        listener: XposedService.OnScopeEventListener,
    ): Boolean {
        val bound = service ?: return false
        return runCatching {
            bound.requestScope(listOf(packageName), listener)
            true
        }.getOrDefault(false)
    }

    /**
     * Removes [packageName] from Vector module scope. Silent local daemon call (Vector only
     * prompts when *adding* scope). The persisted Skopos policy is deliberately preserved:
     * enforcement stops because Skopos is no longer injected, and re-adding the package
     * later restores the saved policy.
     */
    fun removeVectorScope(packageName: String): Boolean {
        val bound = service ?: return false
        return runCatching {
            bound.removeScope(listOf(packageName))
            true
        }.getOrDefault(false)
    }

    /**
     * One real contact row for the production picker: aggregate id, durable lookup key,
     * display name, and a secondary line (first phone number, if any) to disambiguate
     * duplicate names. Lookup keys are the durable selection identity; numeric ids are
     * display/session-local only and are never persisted.
     */
    data class DeviceContact(
        val id: Long,
        val lookupKey: String,
        val displayName: String,
        val secondary: String?,
    )

    /**
     * All device contacts, loaded off the UI thread by the caller. Returns an empty list
     * without permission; failures never crash the manager.
     */
    /**
     * All device contacts, loaded off the UI thread by the caller. Returns an empty list
     * without permission; provider failures throw so the caller can distinguish an error
     * from a genuine zero-contact result.
     */
    fun deviceContacts(): List<DeviceContact> {
        if (!hasReadContactsPermission) return emptyList()
        val found = markerResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME,
            ),
            null, null,
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
            val keyIdx = cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
            val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val ids = mutableListOf<Long>()
            val rows = mutableListOf<Triple<Long, String, String>>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                val key = cursor.getString(keyIdx) ?: continue
                val id = cursor.getLong(idIdx)
                ids += id
                rows += Triple(id, key, name)
            }
            val phones = phonesFor(ids)
            rows.map { (id, key, name) -> DeviceContact(id, key, name, phones[id]) }
        } ?: emptyList()
        return found
    }

    /**
     * Durably resolves each persisted lookup key to its current aggregate id using the
     * provider's lookup path (exact key, then the key's constituent raw-contact ids), which
     * survives aggregate recreation after edits, merges and splits where raw LOOKUP_KEY
     * equality would miss. Returns null per key that does not resolve (deleted or
     * mid-aggregation); no permission or empty input yields an empty map.
     */
    fun resolveSelectedKeys(lookupKeys: Set<String>): Map<String, Long?> {
        if (!hasReadContactsPermission || lookupKeys.isEmpty()) return emptyMap()
        return lookupKeys.associateWith { key -> resolveKey(key) }
    }

    private fun resolveKey(lookupKey: String): Long? {
        if (lookupKey.isEmpty()) return null
        return runCatching {
            val lookupUri = ContactsContract.Contacts.CONTENT_LOOKUP_URI
                .buildUpon()
                .appendPath(lookupKey)
                .build()
            val current = ContactsContract.Contacts.lookupContact(markerResolver, lookupUri)
                ?: return@runCatching null
            ContentUris.parseId(current)
        }.getOrNull()
    }

    private fun phonesFor(contactIds: List<Long>): Map<Long, String> {
        if (contactIds.isEmpty()) return emptyMap()
        val phones = mutableMapOf<Long, String>()
        runCatching {
            contactIds.chunked(500).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                markerResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} IN ($placeholders)",
                    chunk.map { it.toString() }.toTypedArray(),
                    null,
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIdx)
                        if (id !in phones) phones[id] = cursor.getString(numIdx) ?: ""
                    }
                }
            }
        }
        return phones
    }

    /**
     * The deterministic marker contacts the test app seeds (matching on display name is safe
     * here: this is a test-only contact store on a validation device).
     *
     * This is the only provider query the manager ever makes, and it must not run without
     * READ_CONTACTS. The permission check is the gate; the [runCatching] guard is defensive
     * only, so an unexpected provider failure never crashes the manager.
     */
    fun markerContacts(): List<MarkerContact> {
        if (!hasReadContactsPermission) return emptyList()
        return runCatching {
            val markers = mutableListOf<MarkerContact>()
            markerResolver.query(
                ContactsQuery.URI,
                ContactsQuery.PROJECTION,
                "${ContactsContract.Contacts.DISPLAY_NAME} IN (${SkoposContract.MARKER_NAMES.joinToString(",") { "?" }} )",
                SkoposContract.MARKER_NAMES.toTypedArray(),
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    markers.add(
                        MarkerContact(
                            id = cursor.getLong(ContactsQuery._ID),
                            lookupKey = cursor.getString(ContactsQuery.LOOKUP_KEY),
                            displayName = cursor.getString(ContactsQuery.NAME) ?: "",
                        ),
                    )
                }
            }
            markers
        }.getOrDefault(emptyList())
    }

    private val hasReadContactsPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED

    data class MarkerContact(val id: Long, val lookupKey: String, val displayName: String)

    private object ContactsQuery {
        val URI: Uri = ContactsContract.Contacts.CONTENT_URI
        val PROJECTION = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME,
        )
        val _ID = 0
        val LOOKUP_KEY = 1
        val NAME = 2
    }

    companion object {
        private val instances = mutableMapOf<String, PolicyRepository>()
        private val guard = Any()

        fun get(context: Context, targetPackage: String): PolicyRepository =
            synchronized(guard) {
                instances.getOrPut(targetPackage) {
                    PolicyRepository(context.applicationContext, targetPackage)
                }
            }
    }
}
