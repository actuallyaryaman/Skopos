package org.a4real.skopos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.a4real.skopos.data.ThemePreferences
import org.a4real.skopos.ui.MainScreen
import org.a4real.skopos.ui.theme.SkoposTheme
import org.a4real.skopos.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    private val themePreferences by lazy { ThemePreferences(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by themePreferences.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            val scope = rememberCoroutineScope()
            SkoposTheme(themeMode = themeMode) {
                MainScreen(
                    themeMode = themeMode,
                    onThemeModeChange = { mode -> scope.launch { themePreferences.setThemeMode(mode) } },
                )
            }
        }
    }
}