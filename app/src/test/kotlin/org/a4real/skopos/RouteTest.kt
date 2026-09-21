package org.a4real.skopos

import org.a4real.skopos.data.AppRow
import org.a4real.skopos.ui.BottomTab
import org.a4real.skopos.ui.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteTest {

    private fun row(pkg: String) = AppRow(
        packageName = pkg,
        label = pkg,
        declaresReadContacts = false,
        isSystem = false,
        vectorActive = false,
        policy = null,
    )

    @Test
    fun `back chain pops one level`() {
        assertEquals(
            Route.ContactsApps,
            Route.Detail(row("com.example.app")).back(),
        )
        assertEquals(Route.Home, Route.ContactsApps.back())
        assertEquals(Route.Home, Route.Settings.back())
        assertEquals(Route.Settings, Route.AdvancedSettings.back())
        assertNull(Route.Home.back())
    }

    @Test
    fun `bottom tabs show only on top-level screens`() {
        assertEquals(BottomTab.HOME, Route.Home.bottomTab())
        assertEquals(BottomTab.SETTINGS, Route.Settings.bottomTab())
        assertNull(Route.ContactsApps.bottomTab())
        assertNull(Route.Detail(row("com.example.app")).bottomTab())
        assertNull(Route.AdvancedSettings.bottomTab())
    }
}
