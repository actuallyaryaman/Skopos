package org.a4real.skopos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.data.PolicyRepository
import org.a4real.skopos.data.ThemePreferences
import org.a4real.skopos.ui.MainScreen
import org.a4real.skopos.ui.theme.SkoposTheme
import org.a4real.skopos.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    private val themePreferences by lazy { ThemePreferences(applicationContext) }
    private val policyRepository by lazy { PolicyRepository.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by themePreferences.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            val scope = rememberCoroutineScope()

            var tick by remember { mutableIntStateOf(0) }
            var connected by remember { mutableStateOf(false) }
            var currentScope by remember { mutableStateOf<ContactScope?>(null) }
            var markers by remember { mutableStateOf(listOf<PolicyRepository.MarkerContact>()) }
            var feedback by remember { mutableStateOf("Waiting for the Vector daemon…") }

            androidx.compose.runtime.LaunchedEffect(tick) {
                connected = policyRepository.connected
                currentScope = policyRepository.currentScope()
                markers = policyRepository.markerContacts()
                if (connected) {
                    feedback = "Reading the scope published in the daemon store."
                } else {
                    feedback = "Daemon not connected — changes are not published yet."
                }
            }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                while (true) {
                    delay(1500)
                    tick++
                }
            }

            SkoposTheme(themeMode = themeMode) {
                MainScreen(
                    themeMode = themeMode,
                    onThemeModeChange = { mode -> scope.launch { themePreferences.setThemeMode(mode) } },
                    connected = connected,
                    scope = currentScope,
                    markers = markers,
                    feedback = feedback,
                    onPublish = { next -> scope.launch {
                        val ok = policyRepository.writeScope(next)
                        feedback = if (ok) "Scope published to the daemon."
                        else "Daemon not connected — not published."
                    } },
                    onRefresh = { tick++ },
                )
            }
        }
    }
}