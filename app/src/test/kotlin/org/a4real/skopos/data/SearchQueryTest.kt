package org.a4real.skopos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {

    @Test
    fun `trailing space matches`() {
        assertTrue(SearchQuery.matches("alice ", "Alice"))
    }

    @Test
    fun `leading space matches`() {
        assertTrue(SearchQuery.matches("  alice", "Alice"))
    }

    @Test
    fun `repeated internal spaces match`() {
        assertTrue(SearchQuery.matches("alice   smith", "Alice Smith"))
    }

    @Test
    fun `mixed case matches`() {
        assertTrue(SearchQuery.matches("ALICE", "Alice"))
    }

    @Test
    fun `multi-token prefix matches`() {
        assertTrue(SearchQuery.matches("ary sah", "Aryaman Sah"))
        assertTrue(SearchQuery.matches("alice smi", "Alice Smith"))
    }

    @Test
    fun `token mismatch does not match`() {
        assertFalse(SearchQuery.matches("bob x", "Bob Smith"))
        assertFalse(SearchQuery.matches("alice jones", "Alice Smith"))
    }

    @Test
    fun `empty query matches everything`() {
        assertTrue(SearchQuery.matches("", "Anything"))
        assertTrue(SearchQuery.matches("   ", "Anything"))
    }

    @Test
    fun `normalize trims collapses and lowercases`() {
        assertEquals("alice smith", SearchQuery.normalize("  Alice   Smith "))
    }
}
