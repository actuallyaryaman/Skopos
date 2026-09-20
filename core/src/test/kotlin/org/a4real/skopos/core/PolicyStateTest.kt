package org.a4real.skopos.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyStateTest {

    @Test
    fun absentPreferenceIsUnset() {
        assertEquals(PolicyState.Unset, PolicyState.decode(null, present = false))
        assertEquals(PolicyState.Unset, PolicyState.decode("EMPTY", present = false))
    }

    @Test
    fun nullValueIsUnset() {
        assertEquals(PolicyState.Unset, PolicyState.decode(null, present = true))
    }

    @Test
    fun explicitEmptyStaysEmpty() {
        assertEquals(
            PolicyState.Configured(ContactScope.Empty),
            PolicyState.decode("EMPTY", present = true),
        )
    }

    @Test
    fun fullDecodes() {
        assertEquals(
            PolicyState.Configured(ContactScope.Full),
            PolicyState.decode("FULL", present = true),
        )
    }

    @Test
    fun selectedDecodes() {
        assertEquals(
            PolicyState.Configured(ContactScope.Selected(setOf("k1"))),
            PolicyState.decode("SELECTED\nk1", present = true),
        )
    }

    @Test
    fun malformedIsCorrupt() {
        assertEquals(PolicyState.Corrupt, PolicyState.decode("BOGUS\nk1", present = true))
        assertEquals(PolicyState.Corrupt, PolicyState.decode("", present = true))
        assertEquals(PolicyState.Corrupt, PolicyState.decode("SELECTED\n", present = true))
    }

    @Test
    fun policyGroupIsPerPackageWithM2Compat() {
        assertEquals("com.example.app", SkoposContract.policyGroupFor("com.example.app"))
        assertEquals(
            SkoposContract.TEST_PACKAGE,
            SkoposContract.policyGroupFor(SkoposContract.TEST_PACKAGE),
        )
    }
}
