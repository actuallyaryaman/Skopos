package org.a4real.skopos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactScopeTest {

    @Test
    fun emptySelectionCollapsesToEmpty() {
        assertEquals(ContactScope.Empty, ContactScope.from(emptySet()))
    }

    @Test
    fun nonEmptySelectionIsSelected() {
        val scope = ContactScope.from(setOf("k1"))
        assertTrue(scope is ContactScope.Selected)
        assertEquals(setOf("k1"), (scope as ContactScope.Selected).lookupKeys)
    }

    @Test(expected = IllegalArgumentException::class)
    fun selectedWithEmptySetIsRejected() {
        ContactScope.Selected(emptySet())
    }

    @Test
    fun encodeDecodeRoundTrips() {
        for (scope in listOf(
            ContactScope.Full,
            ContactScope.Empty,
            ContactScope.from(setOf("aaa", "b  b", "k=1")),
        )) {
            assertEquals(scope, ContactScope.decode(scope.encode()))
        }
    }

    @Test
    fun decodeGarbageFailsClosedToEmpty() {
        assertEquals(ContactScope.Empty, ContactScope.decode("NOT_A_MODE\nk1"))
        assertEquals(ContactScope.Empty, ContactScope.decode(null))
        assertEquals(ContactScope.Empty, ContactScope.decode(""))
    }

    @Test
    fun decodeSelectedWithNoKeysCollapsesToEmpty() {
        assertEquals(ContactScope.Empty, ContactScope.decode("SELECTED\n\n\n"))
    }
}