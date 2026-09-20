package org.a4real.skopos.test

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import org.a4real.skopos.core.SkoposContract

/**
 * Deterministic marker contacts the test app owns on the device contact store, plus the query
 * observer that makes a scope visible in the UI.
 *
 * The test app never writes policy — only markers. Seeding uses the app's own account so the
 * only ids the harness needs to remember are the RawContacts._ID values it inserted; cleanup
 * deletes exactly those rows and nothing else.
 *
 * Privacy: UI/log output identifies markers only by their synthetic labels ("Skopos Alice",
 * …). Raw lookup keys and phone numbers never appear in results.
 */
object ContactHarness {

    const val ACCOUNT_TYPE = "org.a4real.skopos.test"
    const val ACCOUNT_NAME = "Skopos Markers"

    private const val PREFS = "skopos_harness"
    private const val KEY_SEEDED_RAW_IDS = "seeded_raw_ids"
    private const val KEY_MARKER_REFS = "marker_refs"

    enum class Verdict { PASS, FAIL, NOT_TESTABLE }

    data class Check(val verdict: Verdict, val text: String)

    fun markerNumber(marker: String): String {
        val index = SkoposContract.MARKER_NAMES.indexOf(marker)
        return "55501$index"
    }

    /** Seeds one raw contact (name + phone) per marker, remembering only the inserted ids. */
    fun seed(context: Context): SeedResult {
        val resolver = context.contentResolver
        val tracked = savedRawIds(context)
        var inserted = 0
        for (marker in SkoposContract.MARKER_NAMES) {
            if (markerContactByDataName(resolver, marker) != null) continue
            val rawId = insertMarkerRawContact(resolver, marker) ?: continue
            tracked += rawId
            inserted++
        }
        if (inserted > 0) saveRawIds(context, tracked)
        return SeedResult(inserted, tracked.size)
    }

    data class CleanupResult(val attempted: Int, val deleted: Int, val remaining: Int)

    /**
     * Deletes exactly the tracked RawContacts._ID rows (never display-name matching). Rows that
     * delete cleanly are dropped from tracking; rows whose delete throws stay tracked for retry.
     */
    fun cleanup(context: Context): CleanupResult {
        val resolver = context.contentResolver
        val tracked = savedRawIds(context)
        var deleted = 0
        val remaining = tracked.toMutableSet()
        for (rawId in tracked) {
            val outcome = runCatching {
                resolver.delete(
                    ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId),
                    null,
                    null,
                )
            }
            val rows = outcome.getOrNull()
            if (rows != null) {
                deleted += rows
                remaining.remove(rawId)
            }
        }
        saveRawIds(context, remaining)
        return CleanupResult(tracked.size, deleted, remaining.size)
    }

    /** Aggregate (_ID + LOOKUP_KEY) reference for one marker, resolved from the provider. */
    data class MarkerRef(val label: String, val contactId: Long, val lookupKey: String)

    /**
     * Resolves currently-visible markers by synthetic display name and merges them into the
     * cached refs (so a marker hidden by the current scope keeps its last-known IDs, resolved
     * earlier under a wider scope). Returns the merged set.
     */
    fun resolveMarkers(context: Context): List<MarkerRef> {
        val merged = (cachedRefs(context).associateBy { it.label } +
            queryMarkerRefs(context).associateBy { it.label }).values.toList()
        saveRefs(context, merged)
        return merged
    }

    fun cachedRefs(context: Context): List<MarkerRef> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MARKER_REFS, null) ?: return emptyList()
        return runCatching {
            raw.split("\u001E").filter { it.isNotEmpty() }.mapNotNull { entry ->
                val parts = entry.split("\u001F")
                if (parts.size != 3) null
                else MarkerRef(parts[0], parts[1].toLong(), parts[2])
            }
        }.getOrDefault(emptyList())
    }

    /** Synthetic marker labels currently visible through a plain contacts list query. */
    fun visibleMarkerLabels(context: Context): Set<String> =
        queryMarkerRefs(context).map { it.label }.toSet()

    fun report(context: Context): Report {
        val resolver = context.contentResolver

        val contactClass = runCatching {
            resolver.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null)
                ?.use { it.javaClass.name }
        }.getOrNull() ?: "n/a"
        val contactCount = queryCount(
            resolver, ContactsContract.Contacts.CONTENT_URI, null, null, null, null)

        val markerPhone = markerNumber(SkoposContract.MARKER_NAMES.first())
        val lookupUri = ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI
            .buildUpon().appendPath(markerPhone).build()
        val lookupClass = runCatching {
            resolver.query(lookupUri, null, null, null, null)?.use { it.javaClass.name }
        }.getOrNull() ?: "n/a"
        val lookupCount = queryCount(resolver, lookupUri, null, null, null, null)

        val visible = visibleMarkerLabels(context)
        val refs = resolveMarkers(context)
        val visibleLabels = refs.filter { it.label in visible }.map { it.label }

        return Report(contactCount, contactClass, lookupCount, lookupClass, visibleLabels, refs.size)
    }

    /** Section 1+2: direct contact-ID and lookup-URI visibility for one allowed + one hidden marker. */
    fun directAndLookupChecks(context: Context): List<Check> {
        val out = mutableListOf<Check>()
        val visible = visibleMarkerLabels(context)
        val refs = resolveMarkers(context).associateBy { it.label }
        val selectedLabel = visible.firstOrNull { it in SkoposContract.MARKER_NAMES }
        val hiddenLabel = (SkoposContract.MARKER_NAMES - visible).firstOrNull()
        if (selectedLabel == null) {
            out += Check(Verdict.NOT_TESTABLE, "direct/lookup selected: no marker currently visible")
        } else {
            val ref = refs[selectedLabel]
            if (ref == null) {
                out += Check(Verdict.NOT_TESTABLE, "direct/lookup selected $selectedLabel: no cached aggregate ID")
            } else {
                val seen = idVisible(context, ref.contactId)
                out += verdict(
                    seen == true, "direct contacts/${label(ref)} selected: " +
                        if (seen == true) "visible as expected"
                        else "NOT visible (rowCount/exception: ${seen == null})",
                )
                val lookSeen = lookupVisible(context, ref)
                out += verdict(
                    lookSeen == true, "lookup ${label(ref)} selected: " +
                        if (lookSeen == true) "visible as expected"
                        else "NOT visible (rowCount/exception)",
                )
            }
        }
        if (hiddenLabel == null) {
            out += Check(Verdict.NOT_TESTABLE, "direct/lookup hidden: every marker currently visible")
        } else {
            val ref = refs[hiddenLabel]
            if (ref == null) {
                out += Check(
                    Verdict.NOT_TESTABLE,
                    "direct/lookup hidden $hiddenLabel: no cached aggregate ID — resolve under FULL first",
                )
            } else {
                val seen = idVisible(context, ref.contactId)
                out += verdict(
                    seen == false, "direct contacts/${label(ref)} hidden: " +
                        if (seen == false) "hidden as expected" else "ESCAPED scope",
                )
                val lookSeen = lookupVisible(context, ref)
                out += verdict(
                    lookSeen == false, "lookup ${label(ref)} hidden: " +
                        if (lookSeen == false) "hidden as expected" else "ESCAPED scope",
                )
            }
        }
        return out
    }

    /** Section 3: hidden-ID bypass probes — direct ID, `_ID = ?`, `_ID IN (...)`. */
    fun bypassChecks(context: Context): List<Check> {
        val resolver = context.contentResolver
        val visible = visibleMarkerLabels(context)
        val refs = resolveMarkers(context).associateBy { it.label }
        val hiddenLabel = (SkoposContract.MARKER_NAMES - visible).firstOrNull()
            ?: return listOf(Check(Verdict.NOT_TESTABLE, "bypass: every marker currently visible"))
        val ref = refs[hiddenLabel]
            ?: return listOf(
                Check(
                    Verdict.NOT_TESTABLE,
                    "bypass hidden $hiddenLabel: no cached aggregate ID — resolve under FULL first",
                ),
            )
        val out = mutableListOf<Check>()
        out += verdict(
            idVisible(context, ref.contactId) == false,
            "bypass direct contacts/${label(ref)}: " +
                if (idVisible(context, ref.contactId) == false) "hidden as expected" else "ESCAPED scope",
        )
        val eqCount = queryCount(
            resolver, ContactsContract.Contacts.CONTENT_URI, null,
            "${ContactsContract.Contacts._ID} = ?", arrayOf(ref.contactId.toString()), null)
        out += verdict(eqCount == 0, "bypass `_ID = ?` hidden ${label(ref)}: rows=$eqCount")
        val inCount = queryCount(
            resolver, ContactsContract.Contacts.CONTENT_URI, null,
            "${ContactsContract.Contacts._ID} IN (?,?)",
            arrayOf(ref.contactId.toString(), "-999"), null)
        out += verdict(inCount == 0, "bypass `_ID IN (...)` hidden ${label(ref)}: rows=$inCount")
        return out
    }

    /** Section 4+5: PhoneLookup scoping, projection masking, and cursor behavior. */
    fun phoneLookupChecks(context: Context): List<Check> {
        val out = mutableListOf<Check>()
        val visible = visibleMarkerLabels(context)
        val selectedLabel = visible.firstOrNull { it in SkoposContract.MARKER_NAMES }
        val hiddenLabel = (SkoposContract.MARKER_NAMES - visible).firstOrNull()

        if (selectedLabel == null) {
            out += Check(Verdict.NOT_TESTABLE, "phonelookup selected: no marker currently visible")
        } else {
            val uri = filterUri(markerNumber(selectedLabel))
            val rows = queryCount(context.contentResolver, uri, null, null, null, null)
            out += verdict(rows > 0, "phonelookup selected $selectedLabel: rows=$rows")

            // C: explicit projection containing CONTACT_ID.
            val withId = queryColumns(context, uri, arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.PhoneLookup.CONTACT_ID,
            ))
            out += verdict(
                withId != null && withId.rows > 0 &&
                    ContactsContract.PhoneLookup.CONTACT_ID in withId.columns,
                "phonelookup explicit CONTACT_ID $selectedLabel: " +
                    "cols=${withId?.columns?.size} rows=${withId?.rows} " +
                    "contactIdPresent=${withId?.let { ContactsContract.PhoneLookup.CONTACT_ID in it.columns }}",
            )

            // D: explicit projection omitting CONTACT_ID — caller must not see it.
            val withoutId = queryColumns(context, uri, arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ))
            val masked = withoutId != null &&
                ContactsContract.PhoneLookup.CONTACT_ID !in withoutId.columns
            out += verdict(
                withoutId != null && withoutId.rows > 0 && masked,
                "phonelookup masked projection $selectedLabel: " +
                    "cols=${withoutId?.columns?.joinToString()} count=${withoutId?.columns?.size} " +
                    "rows=${withoutId?.rows} contactIdLeaked=${withoutId?.let {
                        ContactsContract.PhoneLookup.CONTACT_ID in it.columns
                    }}",
            )

            // Cursor behavior on the selected phone lookup cursor.
            out += cursorBehaviorChecks(context, uri)
        }

        if (hiddenLabel == null) {
            out += Check(Verdict.NOT_TESTABLE, "phonelookup hidden: every marker currently visible")
        } else {
            val rows = queryCount(
                context.contentResolver, filterUri(markerNumber(hiddenLabel)), null, null, null, null)
            out += verdict(rows == 0, "phonelookup hidden $hiddenLabel: rows=$rows")
        }
        return out
    }

    /** Section 6: null-selection + non-null sort regression under the current scope. */
    fun sortRegressionChecks(context: Context): List<Check> {
        val out = mutableListOf<Check>()
        val resolver = context.contentResolver
        val visible = visibleMarkerLabels(context)
        val hidden = SkoposContract.MARKER_NAMES - visible
        val names = runCatching {
            resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                null, null,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val list = mutableListOf<String>()
                while (cursor.moveToNext()) list += cursor.getString(idx) ?: ""
                list
            } ?: emptyList<String>()
        }.getOrDefault(emptyList())
        val leaked = names.filter { it in hidden }
        out += verdict(
            leaked.isEmpty(),
            "sort regression hidden absent: leaked=${if (leaked.isEmpty()) "none" else leaked.joinToString()} rows=${names.size}",
        )
        out += verdict(
            names == names.sorted(),
            "sort regression order preserved: rows=${names.size} sorted=${names == names.sorted()}",
        )
        return out
    }

    private data class ColumnProbe(val columns: List<String>, val rows: Int)

    private fun queryColumns(
        context: Context,
        uri: android.net.Uri,
        projection: Array<String>?,
    ): ColumnProbe? = runCatching {
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            ColumnProbe(cursor.columnNames.toList(), cursor.count)
        }
    }.getOrNull()

    private fun cursorBehaviorChecks(context: Context, uri: android.net.Uri): List<Check> {
        val out = mutableListOf<Check>()
        fun probe(name: String, block: (Cursor) -> Any?): Check {
            val result = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use(block)
                    ?: throw IllegalStateException("null cursor")
            }
            return if (result.isSuccess) Check(Verdict.PASS, "cursor $name: ok")
            else Check(Verdict.FAIL, "cursor $name: ${result.exceptionOrNull()?.javaClass?.simpleName}")
        }
        // count/move/position/columns/reads on a live cursor.
        out += probe("count+moveToFirst+moveToNext", { c ->
            check(c.count >= 0); check(c.moveToFirst()); var n = 0
            do { n++ } while (c.moveToNext()); check(n == c.count)
        })
        out += probe("moveToPosition+getPosition", { c ->
            check(c.moveToPosition(0)); check(c.getPosition() == 0)
        })
        out += probe("getColumnNames+getColumnCount+getColumnIndex", { c ->
            check(c.columnNames.size == c.columnCount)
            c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        })
        out += probe("getString+getLong", { c ->
            check(c.moveToFirst())
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            if (nameIdx >= 0) c.getString(nameIdx)
            val idIdx = c.getColumnIndex(ContactsContract.Contacts._ID)
            if (idIdx >= 0) c.getLong(idIdx)
        })
        out += probe("extras", { c -> c.extras })
        // close() propagation: use{} must leave the cursor closed.
        val leaked = runCatching {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
                ?: throw IllegalStateException("null cursor")
            cursor.use { check(it.count >= 0) }
            !cursor.isClosed
        }
        out += if (leaked.isSuccess && leaked.getOrDefault(true) == false) {
            Check(Verdict.PASS, "cursor close: propagated")
        } else {
            Check(Verdict.FAIL, "cursor close: not closed after use{}")
        }
        return out
    }

    private fun idVisible(context: Context, contactId: Long): Boolean? {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        val count = queryCount(context.contentResolver, uri, null, null, null, null)
        return when {
            count < 0 -> null
            else -> count > 0
        }
    }

    private fun lookupVisible(context: Context, ref: MarkerRef): Boolean? {
        val uri = runCatching {
            ContactsContract.Contacts.getLookupUri(ref.contactId, ref.lookupKey)
        }.getOrNull() ?: return null
        val count = queryCount(context.contentResolver, uri, null, null, null, null)
        return when {
            count < 0 -> null
            else -> count > 0
        }
    }

    private fun filterUri(phone: String): android.net.Uri =
        ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI
            .buildUpon().appendPath(phone).build()

    private fun label(ref: MarkerRef): String = ref.label

    private fun verdict(pass: Boolean, text: String): Check =
        if (pass) Check(Verdict.PASS, text) else Check(Verdict.FAIL, text)

    private fun queryCount(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        projection: Array<String>?,
        selection: String?,
        args: Array<String>?,
        sort: String?,
    ): Int = runCatching {
        resolver.query(uri, projection, selection, args, sort)?.use { it.count } ?: -1
    }.getOrDefault(-1)

    private fun queryMarkerRefs(context: Context): List<MarkerRef> = runCatching {
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME,
            ),
            "${ContactsContract.Contacts.DISPLAY_NAME} IN (?,?,?)",
            SkoposContract.MARKER_NAMES.toTypedArray(),
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
            val keyIdx = cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
            val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val refs = mutableListOf<MarkerRef>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                if (name !in SkoposContract.MARKER_NAMES) continue
                refs += MarkerRef(name, cursor.getLong(idIdx), cursor.getString(keyIdx) ?: "")
            }
            refs
        } ?: emptyList()
    }.getOrDefault(emptyList())

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
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, marker)
                },
            )
            resolver.insert(
                ContactsContract.Data.CONTENT_URI,
                ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
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

    private fun saveRawIds(context: Context, ids: Set<Long>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (ids.isEmpty()) remove(KEY_SEEDED_RAW_IDS)
            else putString(KEY_SEEDED_RAW_IDS, ids.joinToString(","))
        }.apply()
    }

    private fun saveRefs(context: Context, refs: List<MarkerRef>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MARKER_REFS, refs.joinToString("\u001E") {
                "${it.label}\u001F${it.contactId}\u001F${it.lookupKey}"
            }).apply()
    }

    data class SeedResult(val inserted: Int, val totalSeeded: Int)

    data class VisibleKeys(val count: Int, val keys: List<String>)

    data class Report(
        val contactCount: Int,
        val contactCursorClass: String,
        val lookupCount: Int,
        val lookupCursorClass: String,
        val visibleLabels: List<String>,
        val refsCached: Int,
    )
}
