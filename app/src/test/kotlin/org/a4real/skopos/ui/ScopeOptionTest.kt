package org.a4real.skopos.ui

import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.core.PolicyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeOptionTest {

    @Test
    fun `picker loads on picker-open state regardless of stored policy`() {
        // Regression: the load gate once used the stored scope, deadlocking the picker
        // empty when opening SELECTED from UNSET/FULL/EMPTY. The gate now follows the
        // transient picker-open state, so the stored policy must not influence it.
        assertTrue(ScopeOption.pickerLoads(granted = true, pickerOpen = true))
        assertFalse(ScopeOption.pickerLoads(granted = true, pickerOpen = false))
        assertFalse(ScopeOption.pickerLoads(granted = false, pickerOpen = true))
        assertFalse(ScopeOption.pickerLoads(granted = false, pickerOpen = false))
    }

    @Test
    fun `policy maps to tabs with UNSET unselected`() {
        assertEquals(
            ScopeOption.SELECTED,
            ScopeOption.fromPolicy(PolicyState.Configured(ContactScope.Selected(setOf("k")))),
        )
        assertEquals(
            ScopeOption.FULL,
            ScopeOption.fromPolicy(PolicyState.Configured(ContactScope.Full)),
        )
        assertEquals(
            ScopeOption.EMPTY,
            ScopeOption.fromPolicy(PolicyState.Configured(ContactScope.Empty)),
        )
        assertNull(ScopeOption.fromPolicy(PolicyState.Unset))
        assertNull(ScopeOption.fromPolicy(PolicyState.Corrupt))
        assertNull(ScopeOption.fromPolicy(null))
    }
}
