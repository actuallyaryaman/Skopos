package org.a4real.skopos.data

import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.core.PolicyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerPolicyTest {

    private val storedStates = listOf(
        PolicyState.Unset,
        PolicyState.Configured(ContactScope.Full),
        PolicyState.Configured(ContactScope.Empty),
        PolicyState.Configured(ContactScope.Selected(setOf("k1"))),
        PolicyState.Corrupt,
    )

    @Test
    fun `opening SELECTED never publishes regardless of stored policy`() {
        for (stored in storedStates) {
            assertEquals(
                "stored=$stored",
                PickerPolicy.PickerOpenAction.OpenPicker,
                PickerPolicy.decideOpenSelected(hasPermission = true, stored = stored),
            )
        }
    }

    @Test
    fun `opening SELECTED without permission requests it regardless of stored policy`() {
        for (stored in storedStates) {
            assertEquals(
                "stored=$stored",
                PickerPolicy.PickerOpenAction.RequestPermission,
                PickerPolicy.decideOpenSelected(hasPermission = false, stored = stored),
            )
        }
    }

    @Test
    fun `persisted non-empty SELECTED auto-opens picker once`() {
        assertTrue(
            PickerPolicy.shouldAutoOpenPicker(
                PolicyState.Configured(ContactScope.Selected(setOf("k1"))),
            ),
        )
    }

    @Test
    fun `UNSET FULL EMPTY corrupt and absent never auto-open`() {
        assertFalse(PickerPolicy.shouldAutoOpenPicker(PolicyState.Unset))
        assertFalse(
            PickerPolicy.shouldAutoOpenPicker(PolicyState.Configured(ContactScope.Full)),
        )
        assertFalse(
            PickerPolicy.shouldAutoOpenPicker(PolicyState.Configured(ContactScope.Empty)),
        )
        assertFalse(PickerPolicy.shouldAutoOpenPicker(PolicyState.Corrupt))
        assertFalse(PickerPolicy.shouldAutoOpenPicker(null))
    }
}
