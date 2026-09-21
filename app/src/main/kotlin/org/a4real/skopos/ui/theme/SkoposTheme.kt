package org.a4real.skopos.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private const val BRAND_PRIMARY = 0xFF3D6B63
private const val BRAND_SECONDARY = 0xFF5B6B76

private val LightScheme = lightColorScheme(
    primary = Color(BRAND_PRIMARY),
    secondary = Color(BRAND_SECONDARY),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8FD1C5),
    secondary = Color(0xFFA9BAC6),
)

/**
 * Pure effective-scheme decision, host-testable without framework dynamic-color APIs.
 */
internal enum class SchemeKind { DYNAMIC_DARK, DYNAMIC_LIGHT, STATIC_DARK, STATIC_LIGHT }

internal fun resolveScheme(
    themeMode: ThemeMode,
    dynamicOn: Boolean,
    systemDark: Boolean,
): SchemeKind {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    return when {
        dynamicOn && dark -> SchemeKind.DYNAMIC_DARK
        dynamicOn -> SchemeKind.DYNAMIC_LIGHT
        dark -> SchemeKind.STATIC_DARK
        else -> SchemeKind.STATIC_LIGHT
    }
}

/**
 * The Material 3 Expressive surface is still an alpha API in the 1.5.0-alpha line
 * (same stack as the ../Vector reference build); the opt-in stays confined to this
 * single composable. On Android 12+ the wallpaper drives dynamic colours, with the
 * static schemes above as the fallback.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SkoposTheme(
    themeMode: ThemeMode,
    dynamicColors: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when (resolveScheme(themeMode, dynamicColors, isSystemInDarkTheme())) {
        // minSdk is 36, so dynamic schemes need no compatibility branch below API 31.
        SchemeKind.DYNAMIC_DARK -> dynamicDarkColorScheme(context)
        SchemeKind.DYNAMIC_LIGHT -> dynamicLightColorScheme(context)
        SchemeKind.STATIC_DARK -> DarkScheme
        SchemeKind.STATIC_LIGHT -> LightScheme
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}