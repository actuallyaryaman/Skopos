package org.a4real.skopos.test

import android.content.ContentValues
import android.content.Context
import android.content.ContentUris
import android.content.SharedPreferences
import android.provider.ContactsContract
import org.a4real.skopos.core.SkoposContract

/**
 * Deterministic marker contacts the test app owns on the device contact store, plus the query
 * observer that makes a scope visible in the UI.
 *
 * The test app never writes policy — only markers. Seeding uses the app's own account so the
 * only ids the harness needs to remember are the RawContacts._ID values it inserted; cleanup
 * deletes exactly those rows and nothing else.
 */
object ContactHarness {

    const val ACCOUNT_TYPE = "org.a4real.skopos.test"
    const val ACCOUNT_NAME = "Skopos Markers"

    private const val PREFS = "skopos_harness"
    private const val KEY_SEEDED_RAW_IDS = "seeded_raw_ids"

    fun markerNumber(marker: String): String {
        val index = SkoposContract.MARKER_NAMES.indexOf(marker)
        return "55501$index"
    }

    /** Seeds one raw contact (name + phone) per marker, remembering only the inserted ids. */
    fun seed(context: Context): SeedResult {
        val resolver = context.contentResolver
        val inserted = mutableSetOf<Long>()
        for (marker in SkoposContract.MARKER_NAMES) {
            if (markerContactByDataName(resolver, marker) != null) continue
            val rawId = insertMarkerRawContact(resolver, marker) ?: continue
            inserted += rawId
        }
        if (inserted.isNotEmpty()) {
            savedRawIds(context).addAll(inserted)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SEEDED_RAW_IDS, savedRawIds(context).joinToString(",")).apply()
        }
        return SeedResult(inserted.size, savedRawIds(context).size)
    }

    fun cleanup(context: Context): Int {
        val resolver = context.contentResolver
        var deleted = 0
        for (rawId in savedRawIds(context)) {
            deleted += resolver.delete(
                ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId),
                null,
                null,
            ) ?: 0
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_SEEDED_RAW_IDS).apply()
        return deleted
    }

    fun report(context: Context): Report {
        val resolver = context.contentResolver

        val contactClass = runCatching {
            resolver.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null)
                ?.use { it.javaClass.name }
        }.getOrNull() ?: "n/a"
        val contactCount = runCatching {
            resolver.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null)
                ?.use { it.count } ?: -1
        }.getOrDefault(-1)

        val markerPhone = markerNumber(SkoposContract.MARKER_NAMES.first())
        val lookupUri = ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI
            .buildUpon().appendPath(markerPhone).build()
        val lookupClass = runCatching {
            resolver.query(lookupUri, null, null, null, null)?.use { it.javaClass.name }
        }.getOrNull() ?: "n/a"
        val lookupCount = runCatching {
            resolver.query(lookupUri, null, null, null, null)?.use { it.count } ?: -1
        }.getOrDefault(-1)

        val visibleLookupKeys = runCatching {
            resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
                null, null, null,
            )?.use { cursor ->
                val count = cursor.count
                val keys = mutableListOf<String>()
                while (cursor.moveToNext()) keys += cursor.getString(0)
                VisibleKeys(count, keys)
            } ?: VisibleKeys(-1, emptyList())
        }.getOrDefault(VisibleKeys(-1, emptyList()))

        return Report(contactCount, contactClass, lookupCount, lookupClass, visibleLookupKeys)
    }

    private fun insertMarkerRawContact(
        resolver: android.content.ContentResolver,
        marker: String,
    ): Long? {
        var rawId: Long? = null
        try {
            val values = ContentValues().apply {
                put(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                put(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
            }
            val uri = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, values) ?: return null
            rawId = ContentUris.parseId(uri)

            resolver.insert(
                ContactsContract.Data.CONTENT_URI,
                ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawId!!)
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, marker)
                },
            )
            resolver.insert(
                ContactsContract.Data.CONTENT_URI,
                ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawId!!)
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.Phone.NUMBER, markerNumber(marker))
                    put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                },
            )
        } catch (_: Throwable) {
            return null
        }
        return rawId
    }

    private fun markerContactByDataName(
        resolver: android.content.ContentResolver,
        marker: String,
    ): Long? {
        return resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.RAW_CONTACT_ID),
            "${ContactsContract.Data.MIMETYPE} = ? AND " +
                "${ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME} = ?",
            arrayOf(
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                marker,
            ),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun savedRawIds(context: Context): MutableSet<Long> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SEEDED_RAW_IDS, null) ?: return mutableSetOf()
        return raw.split(",").filter { it.isNotEmpty() }.map { it.toLong() }.toMutableSet()
    }

    data class SeedResult(val inserted: Int, val totalSeeded: Int)

    data class VisibleKeys(val count: Int, val keys: List<String>)

    data class Report(
        val contactCount: Int,
        val contactCursorClass: String,
        val lookupCount: Int,
        val lookupCursorClass: String,
        val visibleKeys: VisibleKeys,
    )
}