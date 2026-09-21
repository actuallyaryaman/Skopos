package org.a4real.skopos

import org.a4real.skopos.data.AppRow
import org.a4real.skopos.ui.BottomTab
import org.a4real.skopos.ui.Route
import org.a4real.skopos.ui.TransitionKind
import org.a4real.skopos.ui.transitionKind
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

    @Test
    fun `forward drill-down transitions`() {
        assertEquals(
            TransitionKind.FORWARD,
            transitionKind(Route.Home, Route.ContactsApps),
        )
        assertEquals(
            TransitionKind.FORWARD,
            transitionKind(Route.ContactsApps, Route.Detail(row("com.example.app"))),
        )
        assertEquals(
            TransitionKind.FORWARD,
            transitionKind(Route.Settings, Route.AdvancedSettings),
        )
    }

    @Test
    fun `backward transitions mirror forward`() {
        assertEquals(
            TransitionKind.BACKWARD,
            transitionKind(Route.Detail(row("com.example.app")), Route.ContactsApps),
        )
        assertEquals(TransitionKind.BACKWARD, transitionKind(Route.ContactsApps, Route.Home))
        assertEquals(
            TransitionKind.BACKWARD,
            transitionKind(Route.AdvancedSettings, Route.Settings),
        )
    }

    @Test
    fun `home settings switch slides directionally`() {
        assertEquals(TransitionKind.TOP_LEVEL_RIGHT, transitionKind(Route.Home, Route.Settings))
        assertEquals(TransitionKind.TOP_LEVEL_LEFT, transitionKind(Route.Settings, Route.Home))
    }

    @Test
    fun `about drills forward from settings`() {
        assertEquals(TransitionKind.FORWARD, transitionKind(Route.Settings, Route.About))
        assertEquals(TransitionKind.BACKWARD, transitionKind(Route.About, Route.Settings))
        assertNull(Route.About.bottomTab())
    }
}
