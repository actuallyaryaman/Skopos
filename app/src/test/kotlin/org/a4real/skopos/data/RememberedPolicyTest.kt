package org.a4real.skopos.data

import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.core.PolicyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RememberedPolicyTest {

    @Test
    fun `codec round-trips sorted deterministically`() {
        val encoded = RememberedPolicy.encode(setOf("k2", "k1"))
        assertEquals("SELECTED\nk1\nk2", encoded)
        assertEquals(setOf("k1", "k2"), RememberedPolicy.decode(encoded))
    }

    @Test
    fun `empty set encodes blank and decodes back`() {
        assertEquals("", RememberedPolicy.encode(emptySet()))
        assertEquals(emptySet<String>(), RememberedPolicy.decode(""))
        assertEquals(emptySet<String>(), RememberedPolicy.decode(null))
    }

    @Test
    fun `malformed remembered data decodes safely empty`() {
        assertEquals(emptySet<String>(), RememberedPolicy.decode("EMPTY"))
        assertEquals(emptySet<String>(), RememberedPolicy.decode("BOGUS\nk1"))
        assertEquals(emptySet<String>(), RememberedPolicy.decode("FULL"))
    }

    @Test
    fun `stored entry wins over legacy scope`() {
        assertEquals(
            setOf("n1"),
            RememberedPolicy.effectiveRemembered(
                storedPresent = true,
                stored = setOf("n1"),
                policy = PolicyState.Configured(ContactScope.Selected(setOf("o1"))),
            ),
        )
    }

    @Test
    fun `legacy SELECTED acts as remembered set immediately`() {
        assertEquals(
            setOf("a", "b"),
            RememberedPolicy.effectiveRemembered(
                storedPresent = false,
                stored = emptySet(),
                policy = PolicyState.Configured(ContactScope.Selected(setOf("a", "b"))),
            ),
        )
    }

    @Test
    fun `no stored entry and no SELECTED scope means empty`() {
        for (policy in listOf(
            PolicyState.Unset,
            PolicyState.Corrupt,
            PolicyState.Configured(ContactScope.Full),
            PolicyState.Configured(ContactScope.Empty),
            null,
        )) {
            assertEquals(
                "policy=$policy",
                emptySet<String>(),
                RememberedPolicy.effectiveRemembered(false, emptySet(), policy),
            )
        }
    }

    @Test
    fun `toggle writes scope plus memory, deselect-last clears both`() {
        assertEquals(
            RememberedPolicy.PolicyWrite(
                ContactScope.Selected(setOf("a", "b")),
                setOf("a", "b"),
            ),
            RememberedPolicy.writeForToggle(setOf("a", "b")),
        )
        assertEquals(
            RememberedPolicy.PolicyWrite(ContactScope.Empty, emptySet()),
            RememberedPolicy.writeForToggle(emptySet()),
        )
    }

    @Test
    fun `mode change preserves current selection over stored memory`() {
        val policy = PolicyState.Configured(ContactScope.Selected(setOf("a", "b")))
        assertEquals(setOf("a", "b"), RememberedPolicy.writeForModeChange(policy, setOf("old")))
        assertEquals(
            setOf("old"),
            RememberedPolicy.writeForModeChange(PolicyState.Configured(ContactScope.Full), setOf("old")),
        )
        assertEquals(
            emptySet<String>(),
            RememberedPolicy.writeForModeChange(PolicyState.Configured(ContactScope.Full), emptySet()),
        )
        assertEquals(emptySet<String>(), RememberedPolicy.writeForModeChange(PolicyState.Unset, emptySet()))
    }

    @Test
    fun `select tab restores remembered or navigates only`() {
        assertEquals(
            RememberedPolicy.PolicyWrite(
                ContactScope.Selected(setOf("a", "b")),
                setOf("a", "b"),
            ),
            RememberedPolicy.writeForSelectTab(setOf("a", "b")),
        )
        assertNull(RememberedPolicy.writeForSelectTab(emptySet()))
    }

    @Test
    fun `select-tab restore round-trips through FULL`() {
        // SELECTED(A,B) -> FULL -> SELECTED must restore SELECTED(A,B).
        val modeChange = RememberedPolicy.writeForModeChange(
            PolicyState.Configured(ContactScope.Selected(setOf("a", "b"))),
            emptySet(),
        )
        assertEquals(setOf("a", "b"), modeChange)
        assertEquals(
            RememberedPolicy.PolicyWrite(
                ContactScope.Selected(setOf("a", "b")),
                setOf("a", "b"),
            ),
            RememberedPolicy.writeForSelectTab(modeChange),
        )
    }
}
