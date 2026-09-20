package org.a4real.skopos.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Classic query overloads (`query(Uri, String[], String, String[], String[, CancellationSignal])`)
 * carry the selection at fixed index 2. A content scan for the first String misses null
 * selections and can hit sortOrder at index 4 instead.
 *
 * Host-JVM runnable: the helper touches no android framework classes.
 */
class RewriteSelectionSlotTest {

    @Test
    fun `null selection null sort rewrites index 2`() {
        val result = classicSelectionRewrite(listOf(null, null, null, null, null), "_id = -1")
        assertEquals(2 to "_id = -1", result)
    }

    @Test
    fun `null selection with sort rewrites index 2 and leaves sort alone`() {
        val args = listOf(null, null, null, null, "sort ASC")
        val result = classicSelectionRewrite(args, "_id = -1")
        assertEquals(2 to "_id = -1", result)
        assertEquals("sort ASC", args[4])
    }

    @Test
    fun `non-null selection merges at index 2`() {
        val result = classicSelectionRewrite(
            listOf(null, null, "display_name LIKE ?", arrayOf("%a%"), null),
            "_id = -1",
        )
        assertEquals(2 to "(display_name LIKE ?) AND (_id = -1)", result)
    }
}
