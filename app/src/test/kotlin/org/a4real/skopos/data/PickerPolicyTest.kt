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

    @Test
    fun `row checked by persisted key or resolved id`() {
        assertTrue(PickerPolicy.isRowChecked("k1", 7L, setOf("k1"), emptySet()))
        assertTrue(PickerPolicy.isRowChecked("k2", 7L, setOf("k1"), setOf(7L)))
        assertFalse(PickerPolicy.isRowChecked("k2", 8L, setOf("k1"), setOf(7L)))
        assertFalse(PickerPolicy.isRowChecked("k2", 8L, emptySet(), emptySet()))
    }

    @Test
    fun `unchecking drops own key plus persisted keys covering the row`() {
        assertEquals(
            emptySet<String>(),
            PickerPolicy.toggledKeys(
                selectedKeys = setOf("k1"),
                rowKey = "k1",
                rowId = 7L,
                resolved = mapOf("k1" to 7L),
                checking = false,
            ),
        )
        // Key changed since persisting: unchecking the resolved row removes the old key.
        assertEquals(
            emptySet<String>(),
            PickerPolicy.toggledKeys(
                selectedKeys = setOf("kOld"),
                rowKey = "kNew",
                rowId = 7L,
                resolved = mapOf("kOld" to 7L),
                checking = false,
            ),
        )
    }

    @Test
    fun `checking normalizes to the current row key without duplicates`() {
        assertEquals(
            setOf("kNew"),
            PickerPolicy.toggledKeys(
                selectedKeys = setOf("kOld"),
                rowKey = "kNew",
                rowId = 7L,
                resolved = mapOf("kOld" to 7L),
                checking = true,
            ),
        )
        assertEquals(
            setOf("k1", "k2"),
            PickerPolicy.toggledKeys(
                selectedKeys = setOf("k1"),
                rowKey = "k2",
                rowId = 8L,
                resolved = mapOf("k1" to 7L),
                checking = true,
            ),
        )
    }
}
