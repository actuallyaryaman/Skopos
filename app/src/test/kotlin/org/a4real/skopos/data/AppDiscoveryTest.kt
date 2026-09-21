package org.a4real.skopos.data

import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.core.PolicyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDiscoveryTest {

    @Test
    fun `valid package names pass`() {
        assertTrue(AppDiscovery.isValidPackageName("com.whatsapp"))
        assertTrue(AppDiscovery.isValidPackageName("org.a4real.skopos.test"))
        assertTrue(AppDiscovery.isValidPackageName("com.example.app_1"))
    }

    @Test
    fun `invalid package names fail`() {
        assertFalse(AppDiscovery.isValidPackageName(""))
        assertFalse(AppDiscovery.isValidPackageName("single"))
        assertFalse(AppDiscovery.isValidPackageName(".leading.dot"))
        assertFalse(AppDiscovery.isValidPackageName("trailing.dot."))
        assertFalse(AppDiscovery.isValidPackageName("9starts.with.digit"))
        assertFalse(AppDiscovery.isValidPackageName("has space.com"))
        assertFalse(AppDiscovery.isValidPackageName("has-dash.com"))
    }

    private fun entry(pkg: String, system: Boolean = false) =
        AppDiscovery.AppEntry(pkg, pkg, declaresReadContacts = true, isSystem = system)

    @Test
    fun `one row policy failure degrades only that row`() {
        val rows = AppDiscovery.assembleRows(
            scopePackages = listOf("com.a", "com.b"),
            discovered = listOf(entry("com.a"), entry("com.b")),
            manualPackages = emptySet(),
            showSystem = true,
            policyFor = { pkg ->
                if (pkg == "com.a") throw RuntimeException("daemon hiccup")
                PolicyState.Configured(ContactScope.Empty)
            },
        )
        assertEquals(2, rows.size)
        val failed = rows.single { it.packageName == "com.a" }
        assertEquals(null, failed.policy)
        assertEquals(true, failed.vectorActive)
        val ok = rows.single { it.packageName == "com.b" }
        assertEquals(PolicyState.Configured(ContactScope.Empty), ok.policy)
    }

    @Test
    fun `scope-only packages appear unverified without discovery`() {
        val rows = AppDiscovery.assembleRows(
            scopePackages = listOf("com.hidden"),
            discovered = emptyList(),
            manualPackages = emptySet(),
            showSystem = false,
            policyFor = { null },
        )
        assertEquals(1, rows.size)
        assertEquals("com.hidden", rows[0].label)
        assertEquals(true, rows[0].unverified)
        assertEquals(true, rows[0].vectorActive)
    }

    @Test
    fun `manual packages appear unverified and skopos itself is excluded`() {
        val rows = AppDiscovery.assembleRows(
            scopePackages = emptyList(),
            discovered = listOf(entry("com.a")),
            manualPackages = setOf("com.manual", "org.a4real.skopos"),
            showSystem = false,
            policyFor = { null },
        )
        assertTrue(rows.any { it.packageName == "com.manual" && it.unverified })
        assertTrue(rows.none { it.packageName == "org.a4real.skopos" })
    }

    @Test
    fun `system toggle only filters the discoverable set`() {        val discovered = listOf(entry("com.user"), entry("com.sys", system = true))
        val hidden = AppDiscovery.assembleRows(
            scopePackages = emptyList(),
            discovered = discovered,
            manualPackages = emptySet(),
            showSystem = false,
            policyFor = { null },
        )
        assertEquals(listOf("com.user"), hidden.map { it.packageName })
        val shown = AppDiscovery.assembleRows(
            scopePackages = emptyList(),
            discovered = discovered,
            manualPackages = emptySet(),
            showSystem = true,
            policyFor = { null },
        )
        assertEquals(2, shown.size)
    }

    private fun row(
        pkg: String,
        label: String = pkg,
        vectorActive: Boolean? = false,
        policy: PolicyState? = null,
    ) = AppRow(pkg, label, declaresReadContacts = false, isSystem = false, vectorActive, policy)

    @Test
    fun `vector-active app is managed`() {
        val (managed, other) = AppDiscovery.groupApps(
            listOf(row("com.a", "A", vectorActive = false), row("com.b", "B", vectorActive = true)),
        )
        assertEquals(listOf("com.b"), managed.map { it.packageName })
        assertEquals(listOf("com.a"), other.map { it.packageName })
    }

    @Test
    fun `configured but vector-inactive app stays managed`() {
        val (managed, other) = AppDiscovery.groupApps(
            listOf(
                row(
                    "com.a",
                    "A",
                    vectorActive = false,
                    policy = PolicyState.Configured(ContactScope.Empty),
                ),
            ),
        )
        assertEquals(listOf("com.a"), managed.map { it.packageName })
        assertTrue(other.isEmpty())
    }

    @Test
    fun `unconfigured inactive app is other`() {
        val (managed, other) = AppDiscovery.groupApps(
            listOf(row("com.a", "A", vectorActive = false, policy = PolicyState.Unset)),
        )
        assertTrue(managed.isEmpty())
        assertEquals(listOf("com.a"), other.map { it.packageName })
    }

    @Test
    fun `groups sort alphabetically with package tiebreak`() {
        val (managed, other) = AppDiscovery.groupApps(
            listOf(
                row("com.z", "Same", vectorActive = true),
                row("com.a", "Same", vectorActive = true),
                row("com.m", "Middle", vectorActive = true),
                row("com.o", "Other"),
            ),
        )
        assertEquals(listOf("com.m", "com.a", "com.z"), managed.map { it.packageName })
        assertEquals(listOf("com.o"), other.map { it.packageName })
    }
}
