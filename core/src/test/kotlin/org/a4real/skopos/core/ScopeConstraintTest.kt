package org.a4real.skopos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeConstraintTest {

    private val contactIds = (1L..3L).toSet()

    @Test
    fun fullScopeHasNoConstraint() {
        assertNull(ScopeConstraint.constraint(ScopeFamily.CONTACTS, ContactScope.Full, emptySet()))
    }

    @Test
    fun emptyScopeIsUnsatisfiableByColumn() {
        val fragment = ScopeConstraint.constraint(ScopeFamily.DATA, ContactScope.Empty, emptySet())
        assertEquals("contact_id = -1", fragment)
    }

    @Test
    fun selectedWithNoResolvedIdsFailsClosed() {
        val fragment = ScopeConstraint.constraint(
            ScopeFamily.CONTACTS, ContactScope.from(setOf("ki")), emptySet())
        assertEquals("_id = -1", fragment)
    }

    @Test
    fun selectedBuildsInListWithoutArgs() {
        val fragment = ScopeConstraint.constraint(
            ScopeFamily.CONTACTS, ContactScope.from(setOf("k1")), contactIds)
        assertEquals("(_id IN (1,2,3))", fragment)
    }

    @Test
    fun selectedUsesContactIdColumnForDataLineage() {
        val fragment = ScopeConstraint.constraint(
            ScopeFamily.PHONES, ContactScope.from(setOf("k1")), contactIds)
        assertEquals("(contact_id IN (1,2,3))", fragment)
    }

    @Test
    fun chunkBreaksHugeSetsAcrossMultipleInLists() {
        val ids = (1L..(ScopeConstraint.MAX_IN_LIST * 2 + 7)).toSet()
        val fragment = ScopeConstraint.constraint(
            ScopeFamily.CONTACTS, ContactScope.from(setOf("k")), ids)!!
        // Every id appears exactly once, sorted, in an IN list ≤500.
        val longs = Regex("\\d+").findAll(fragment).map { it.value.toLong() }.toList()
        assertEquals(ids, longs.toSet())
        assertEquals("three chunks", 3, fragment.split(" OR ").size)
        assertTrue("no chunk exceeds cap", fragment.split(" OR ").maxOf { it.count { c -> c == ',' } + (if (it.contains('(')) 1 else 0) } <= ScopeConstraint.MAX_IN_LIST)
    }

    @Test
    fun mergePreservesCallerSelectionAndPlaceholders() {
        assertEquals(
            "(caller = ?) AND ((_id IN (1,2,3)))",
            ScopeConstraint.mergeSelection(
                "caller = ?",
                ScopeConstraint.constraint(ScopeFamily.CONTACTS, ContactScope.from(setOf("k")), contactIds)),
        )
        assertEquals(
            "_id = -1",
            ScopeConstraint.mergeSelection(null, ScopeConstraint.constraint(
                ScopeFamily.CONTACTS, ContactScope.Empty, emptySet())),
        )
        assertEquals(
            "(a = 1) AND (contact_id = -1)",
            ScopeConstraint.mergeSelection("a = 1", "contact_id = -1"),
        )
        assertEquals("plain", ScopeConstraint.mergeSelection("plain", null))
    }

    @Test
    fun familyLineageFromPaths() {
        assertEquals(ScopeFamily.CONTACTS, ScopeFamily.fromPathSegments(listOf("contacts")))
        assertEquals(ScopeFamily.CONTACTS, ScopeFamily.fromPathSegments(listOf("contacts", "42")))
        assertEquals(ScopeFamily.CONTACTS, ScopeFamily.fromPathSegments(listOf("contacts", "lookup", "k")))
        assertEquals(ScopeFamily.CONTACTS, ScopeFamily.fromPathSegments(listOf("contacts", "lookup", "k", "42")))
        assertEquals(ScopeFamily.CONTACTS, ScopeFamily.fromPathSegments(listOf("contacts", "filter", "alice")))

        assertEquals(ScopeFamily.DATA, ScopeFamily.fromPathSegments(listOf("contacts", "42", "data")))
        assertEquals(ScopeFamily.DATA, ScopeFamily.fromPathSegments(listOf("contacts", "lookup", "k", "data")))
        assertEquals(ScopeFamily.DATA, ScopeFamily.fromPathSegments(listOf("contacts", "42", "photo")))
        assertEquals(ScopeFamily.DATA, ScopeFamily.fromPathSegments(listOf("contacts", "42", "entities")))
        assertEquals(ScopeFamily.DATA, ScopeFamily.fromPathSegments(listOf("data", "phones")))
        assertEquals(ScopeFamily.DATA, ScopeFamily.fromPathSegments(listOf("phones", "filter")))
        assertEquals(ScopeFamily.DATA, ScopeFamily.fromPathSegments(listOf("raw_contacts", "7", "data")))

        assertEquals(ScopeFamily.RAW_CONTACTS, ScopeFamily.fromPathSegments(listOf("raw_contacts")))
        assertEquals(ScopeFamily.RAW_CONTACTS, ScopeFamily.fromPathSegments(listOf("raw_contacts", "7")))

        assertEquals(ScopeFamily.PHONE_LOOKUP, ScopeFamily.fromPathSegments(listOf("phone_lookup", "555")))

        assertNull(ScopeFamily.fromPathSegments(emptyList()))
        assertNull(ScopeFamily.fromPathSegments(listOf("profile")))
        assertNull(ScopeFamily.fromPathSegments(listOf("groups", "1")))
        assertNull(ScopeFamily.fromPathSegments(listOf("settings")))
        assertNull(ScopeFamily.fromPathSegments(listOf("contacts", "42", "stream_items")))
    }
}