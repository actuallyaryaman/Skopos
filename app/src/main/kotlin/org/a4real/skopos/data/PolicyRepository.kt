package org.a4real.skopos.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
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

    @Volatile
    private var service: XposedService? = null

    init {
        XposedServiceHelper.registerListener(
            object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(xposedService: XposedService) {
                    service = xposedService
                }

                override fun onServiceDied(xposedService: XposedService) {
                    if (service === xposedService) service = null
                }
            },
        )
    }

    val connected: Boolean
        get() = service != null

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

    fun writeScope(scope: ContactScope): Boolean {
        val bound = service ?: return false
        return bound.getRemotePreferences(group())
            .edit()
            .putString(SkoposContract.POLICY_KEY, scope.encode())
            .commit()
    }

    /**
     * Removes the target's policy slot. The runtime observes the deletion and returns to
     * [PolicyState.Unset] (native behavior).
     */
    fun resetPolicy(): Boolean {
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
    fun deviceContacts(): List<DeviceContact> {
        if (!hasReadContactsPermission) return emptyList()
        return runCatching {
            markerResolver.query(
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
        }.getOrDefault(emptyList())
    }

    /**
     * How many of [lookupKeys] still resolve against the provider. Reported in the picker so
     * stale selections are visible instead of silently changing policy.
     */
    fun resolvableCount(lookupKeys: Set<String>): Int {
        if (!hasReadContactsPermission || lookupKeys.isEmpty()) return 0
        return runCatching {
            var count = 0
            lookupKeys.chunked(500).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                markerResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts._ID),
                    "${ContactsContract.Contacts.LOOKUP_KEY} IN ($placeholders)",
                    chunk.toTypedArray(),
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) count++
                }
            }
            count
        }.getOrDefault(0)
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
