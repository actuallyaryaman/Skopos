package org.a4real.skopos.runtime

import android.content.ContentResolver
import android.database.CharArrayBuffer
import android.database.Cursor
import android.database.CursorWindow
import android.database.DataSetObserver
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.PhoneLookup

/**
 * The one sanctioned post-filter the runtime applies, and only on the PhoneLookup family:
 * the provider expunges a caller selection before a phone lookup query (CP2:7432), so a
 * scoped phone lookup cannot hide rows by SQL and must drop them after the fact.
 *
 * [PhoneLookup.CONTACT_ID] rows carry the aggregate contact id, which is the same id space
 * the policy resolves lookup keys against. Rows of contacts outside the allowed id set are
 * skipped; an [ContactScope.Empty] never has any id allowed and yields zero rows.
 *
 * When the caller's projection omitted CONTACT_ID the interceptor injects it provider-facing
 * (at the end, where the caller cannot otherwise reach it); the wrapper then hides that slot
 * from [getColumnNames]/[getColumnCount]/[getColumnIndex] so the injected column is
 * unobservable to the caller while powering the filter.
 */
class PhoneLookupScopeCursor(
    private val delegate: Cursor,
    allowedAggregateIds: Set<Long>,
    private val injectedContactIdIndex: Int = -1,
) : Cursor {

    /** Delegate position for each kept (external) row; built at construction. */
    private val mapping: IntArray

    init {
        val kept = IntArray(delegate.count)
        var keptCount = 0
        val filterIndex = if (injectedContactIdIndex in 0 until delegate.columnCount) {
            injectedContactIdIndex
        } else {
            delegate.getColumnIndexOrThrow(PhoneLookup.CONTACT_ID)
        }
        val before = delegate.position
        var p = 0
        while (delegate.moveToNext()) {
            val isAllowed = when {
                !delegate.isNull(filterIndex) ->
                    allowedAggregateIds.contains(delegate.getLong(filterIndex))
                else -> false
            }
            if (isAllowed) kept[keptCount++] = p
            p++
        }
        delegate.moveToPosition(before)
        mapping = kept.copyOf(keptCount)
    }

    private var externalPosition = -1

    private fun positionDelegateAtExternal(): Boolean {
        val ext = externalPosition
        if (ext in mapping.indices) {
            delegate.moveToPosition(mapping[ext])
            return true
        }
        return false
    }

    // -- position ---------------------------------------------------------

    override fun getCount(): Int = mapping.size

    override fun getPosition(): Int = externalPosition

    override fun moveToPosition(position: Int): Boolean {
        if (position !in mapping.indices) {
            externalPosition = -1
            return false
        }
        externalPosition = position
        return positionDelegateAtExternal()
    }

    override fun moveToFirst(): Boolean = moveToPosition(if (mapping.isEmpty()) -1 else 0)

    override fun moveToLast(): Boolean = moveToPosition(mapping.lastIndex)

    override fun moveToNext(): Boolean = moveToPosition(externalPosition + 1)

    override fun moveToPrevious(): Boolean = moveToPosition(externalPosition - 1)

    override fun move(offset: Int): Boolean = moveToPosition(externalPosition + offset)

    override fun isFirst(): Boolean = externalPosition == 0 && mapping.isNotEmpty()

    override fun isLast(): Boolean = externalPosition == mapping.lastIndex && mapping.isNotEmpty()

    override fun isBeforeFirst(): Boolean = externalPosition < 0

    override fun isAfterLast(): Boolean = externalPosition >= mapping.size

    override fun requery(): Boolean = delegate.requery()

    // -- columns (mask the injected slot) --------------------------------

    override fun getColumnCount(): Int =
        if (injectedContactIdIndex >= 0) delegate.columnCount - 1 else delegate.columnCount

    override fun getColumnNames(): Array<String> {
        val names = delegate.columnNames
        if (injectedContactIdIndex < 0) return names
        return names.filterIndexed { i, _ -> i != injectedContactIdIndex }.toTypedArray()
    }

    override fun getColumnIndex(columnName: String): Int {
        if (injectedContactIdIndex >= 0) {
            val hidden = delegate.getColumnIndex(columnName)
            if (hidden == injectedContactIdIndex) return -1
        }
        return delegate.getColumnIndex(columnName)
    }

    override fun getColumnIndexOrThrow(columnName: String): Int {
        val index = getColumnIndex(columnName)
        if (index < 0) throw IllegalArgumentException("column '$columnName' does not exist")
        return index
    }

    override fun getColumnName(columnIndex: Int): String =
        if (injectedContactIdIndex >= 0 && columnIndex >= injectedContactIdIndex) {
            delegate.getColumnName(columnIndex + 1)
        } else {
            delegate.getColumnName(columnIndex)
        }

    // -- values -----------------------------------------------------------

    private inline fun <T> withRow(read: () -> T): T {
        positionDelegateAtExternal()
        return read()
    }

    override fun getString(columnIndex: Int): String = withRow { delegate.getString(columnIndex) }

    override fun getShort(columnIndex: Int): Short = withRow { delegate.getShort(columnIndex) }

    override fun getInt(columnIndex: Int): Int = withRow { delegate.getInt(columnIndex) }

    override fun getLong(columnIndex: Int): Long = withRow { delegate.getLong(columnIndex) }

    override fun getFloat(columnIndex: Int): Float = withRow { delegate.getFloat(columnIndex) }

    override fun getDouble(columnIndex: Int): Double = withRow { delegate.getDouble(columnIndex) }

    override fun getType(columnIndex: Int): Int = withRow { delegate.getType(columnIndex) }

    override fun isNull(columnIndex: Int): Boolean = withRow { delegate.isNull(columnIndex) }

    override fun getBlob(columnIndex: Int): ByteArray? = withRow { delegate.getBlob(columnIndex) }

    override fun getExtras(): Bundle = delegate.extras

    override fun getNotificationUri(): Uri = delegate.notificationUri

    override fun getWantsAllOnMoveCalls(): Boolean = delegate.wantsAllOnMoveCalls

    override fun setExtras(extras: Bundle) = delegate.setExtras(extras)

    override fun setNotificationUri(cr: android.content.ContentResolver, uri: Uri) =
        delegate.setNotificationUri(cr, uri)

    override fun setNotificationUris(cr: ContentResolver, uris: List<Uri>) =
        delegate.setNotificationUris(cr, uris)

    override fun registerContentObserver(observer: android.database.ContentObserver) =
        delegate.registerContentObserver(observer)

    override fun unregisterContentObserver(observer: android.database.ContentObserver) =
        delegate.unregisterContentObserver(observer)

    override fun registerDataSetObserver(observer: DataSetObserver) =
        delegate.registerDataSetObserver(observer)

    override fun unregisterDataSetObserver(observer: DataSetObserver) =
        delegate.unregisterDataSetObserver(observer)

    override fun copyStringToBuffer(columnIndex: Int, buffer: CharArrayBuffer) =
        withRow { delegate.copyStringToBuffer(columnIndex, buffer) }

    override fun close() = delegate.close()

    override fun isClosed(): Boolean = delegate.isClosed()

    override fun respond(extras: Bundle): Bundle = delegate.respond(extras)

    @Suppress("DEPRECATION")
    override fun deactivate() = delegate.deactivate()
}