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
 * The Material 3 Expressive surface is still an alpha API in the 1.5.0-alpha line
 * (same stack as the ../Vector reference build); the opt-in stays confined to this
 * single composable. On Android 12+ the wallpaper drives dynamic colours, with the
 * static schemes above as the fallback.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SkoposTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark =
        when (themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }

    val context = LocalContext.current
    val colorScheme =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (dark) DarkScheme else LightScheme
        }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}