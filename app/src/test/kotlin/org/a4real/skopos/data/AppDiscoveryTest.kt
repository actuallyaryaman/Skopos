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

    private fun entry(
        pkg: String,
        system: Boolean = false,
        declares: Boolean = true,
        granted: Boolean? = null,
    ) = AppDiscovery.AppEntry(
        pkg, pkg, declaresReadContacts = declares, readContactsGranted = granted, isSystem = system,
    )

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
    fun `system toggle only filters the discoverable set`() {
        val discovered = listOf(entry("com.user"), entry("com.sys", system = true))
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
    ) = AppRow(
        packageName = pkg,
        label = label,
        declaresReadContacts = false,
        isSystem = false,
        vectorActive = vectorActive,
        policy = policy,
    )

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

    private fun assemble(
        discovered: List<AppDiscovery.AppEntry>,
        scope: List<String> = emptyList(),
        manual: Set<String> = emptySet(),
        showSystem: Boolean = false,
        policyFor: (String) -> PolicyState? = { null },
    ) = AppDiscovery.assembleRows(
        scopePackages = scope,
        discovered = discovered,
        manualPackages = manual,
        showSystem = showSystem,
        policyFor = policyFor,
    ).map { it.packageName }

    @Test
    fun `unmanaged declaring app is included`() {
        assertEquals(
            listOf("com.chat"),
            assemble(listOf(entry("com.chat"), entry("com.game", declares = false))),
        )
    }

    @Test
    fun `unmanaged non-declaring app is excluded`() {
        assertTrue(
            assemble(listOf(entry("com.game", declares = false))).isEmpty(),
        )
    }

    @Test
    fun `managed app without declaration stays visible`() {
        assertEquals(
            listOf("com.old"),
            assemble(
                listOf(entry("com.old", declares = false)),
                policyFor = { PolicyState.Configured(ContactScope.Empty) },
            ),
        )
    }

    @Test
    fun `grant state never affects eligibility`() {
        val denied = assemble(listOf(entry("com.a", granted = false)))
        assertEquals(listOf("com.a"), denied)
        val unknown = assemble(listOf(entry("com.a", granted = null)))
        assertEquals(listOf("com.a"), unknown)
    }

    @Test
    fun `non-launchable model with declaration is included`() {
        // AppEntry carries no launcher property: eligibility never depended on it.
        assertEquals(
            listOf("com.svc"),
            assemble(listOf(entry("com.svc"))),
        )
    }

    @Test
    fun `managed system app stays visible with toggle off`() {
        assertEquals(
            listOf("com.sys"),
            assemble(
                listOf(entry("com.sys", system = true, declares = false)),
                scope = listOf("com.sys"),
                showSystem = false,
            ),
        )
    }

    @Test
    fun `unmanaged system app follows the toggle`() {
        val discovered = listOf(entry("com.sys", system = true))
        assertTrue(assemble(discovered, showSystem = false).isEmpty())
        assertEquals(listOf("com.sys"), assemble(discovered, showSystem = true))
    }

    @Test
    fun `rows available while daemon enrichment absent`() {
        val rows = AppDiscovery.assembleRows(
            scopePackages = null,
            discovered = listOf(entry("com.a")),
            manualPackages = emptySet(),
            showSystem = false,
            policyFor = { null },
        )
        assertEquals(1, rows.size)
        assertEquals(null, rows[0].vectorActive)
        assertEquals(null, rows[0].policy)
        assertEquals("com.a", rows[0].label)
    }

    @Test
    fun `enrichment promotes row without discarding local fields`() {
        val discovered = listOf(entry("com.a"))
        val bare = AppDiscovery.assembleRows(
            scopePackages = null,
            discovered = discovered,
            manualPackages = emptySet(),
            showSystem = false,
            policyFor = { null },
        )
        val enriched = AppDiscovery.assembleRows(
            scopePackages = listOf("com.a"),
            discovered = discovered,
            manualPackages = emptySet(),
            showSystem = false,
            policyFor = { PolicyState.Configured(ContactScope.Selected(setOf("k"))) },
        )
        assertEquals("com.a", bare[0].label)
        assertEquals("com.a", enriched[0].label)
        assertEquals(true, enriched[0].vectorActive)
        assertEquals(
            PolicyState.Configured(ContactScope.Selected(setOf("k"))),
            enriched[0].policy,
        )
        val (managed, _) = AppDiscovery.groupApps(enriched)
        assertEquals(listOf("com.a"), managed.map { it.packageName })
    }

    @Test
    fun `unknown daemon state is not classified as unmanaged-configured`() {
        val (managed, other) = AppDiscovery.groupApps(
            listOf(
                AppRow(
                    packageName = "com.a",
                    label = "A",
                    declaresReadContacts = true,
                    isSystem = false,
                    vectorActive = null,
                    policy = null,
                ),
            ),
        )
        assertTrue(managed.isEmpty())
        assertEquals(listOf("com.a"), other.map { it.packageName })
    }
}
