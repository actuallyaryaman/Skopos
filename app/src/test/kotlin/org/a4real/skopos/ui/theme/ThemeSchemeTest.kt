package org.a4real.skopos.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeSchemeTest {

    @Test
    fun `system dynamic follows device`() {
        assertEquals(
            SchemeKind.DYNAMIC_DARK,
            resolveScheme(ThemeMode.SYSTEM, dynamicOn = true, systemDark = true),
        )
        assertEquals(
            SchemeKind.DYNAMIC_LIGHT,
            resolveScheme(ThemeMode.SYSTEM, dynamicOn = true, systemDark = false),
        )
    }

    @Test
    fun `explicit modes pin darkness`() {
        assertEquals(
            SchemeKind.DYNAMIC_DARK,
            resolveScheme(ThemeMode.DARK, dynamicOn = true, systemDark = false),
        )
        assertEquals(
            SchemeKind.DYNAMIC_LIGHT,
            resolveScheme(ThemeMode.LIGHT, dynamicOn = true, systemDark = true),
        )
    }

    @Test
    fun `dynamic off uses static palettes`() {
        assertEquals(
            SchemeKind.STATIC_DARK,
            resolveScheme(ThemeMode.DARK, dynamicOn = false, systemDark = false),
        )
        assertEquals(
            SchemeKind.STATIC_LIGHT,
            resolveScheme(ThemeMode.LIGHT, dynamicOn = false, systemDark = true),
        )
        assertEquals(
            SchemeKind.STATIC_DARK,
            resolveScheme(ThemeMode.SYSTEM, dynamicOn = false, systemDark = true),
        )
        assertEquals(
            SchemeKind.STATIC_LIGHT,
            resolveScheme(ThemeMode.SYSTEM, dynamicOn = false, systemDark = false),
        )
    }
}
