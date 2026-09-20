package org.a4real.skopos.data

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.core.SkoposContract

/**
 * The manager's side of the policy channel. Skopos writes its scope into a Vector
 * RemotePreferences group ([SkoposContract.POLICY_GROUP]) through the service binder the
 * daemon delivers to [io.github.libxposed.service.XposedProvider]; the injected runtime reads
 * the same slot. No other store is authoritative.
 *
 * The repo is a singleton so the binder survives activity recreation. Until the daemon has
 * handed the app its service the repo reports "not connected" and writes are ignored.
 */
class PolicyRepository private constructor(context: Context) {

    private val markerResolver = context.applicationContext.contentResolver

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

    fun currentScope(): ContactScope? {
        val bound = service ?: return null
        return ContactScope.decode(
            bound.getRemotePreferences(SkoposContract.POLICY_GROUP)
                .getString(SkoposContract.POLICY_KEY, null))
    }

    fun writeScope(scope: ContactScope): Boolean {
        val bound = service ?: return false
        return bound.getRemotePreferences(SkoposContract.POLICY_GROUP)
            .edit()
            .putString(SkoposContract.POLICY_KEY, scope.encode())
            .commit()
    }

    /**
     * The deterministic marker contacts the test app seeds (matching on display name is safe
     * here: this is a test-only contact store on a validation device).
     */
    fun markerContacts(): List<MarkerContact> {
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
        return markers
    }

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
        @Volatile
        private var instance: PolicyRepository? = null

        fun get(context: Context): PolicyRepository =
            instance ?: synchronized(this) {
                instance ?: PolicyRepository(context.applicationContext).also { instance = it }
            }
    }
}