package org.a4real.skopos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.data.ContactsAccess
import org.a4real.skopos.data.PolicyRepository
import org.a4real.skopos.data.ThemePreferences
import org.a4real.skopos.ui.MainScreen
import org.a4real.skopos.ui.ScopeOption
import org.a4real.skopos.ui.theme.SkoposTheme
import org.a4real.skopos.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    private val themePreferences by lazy { ThemePreferences(applicationContext) }
    private val policyRepository by lazy { PolicyRepository.get(this) }

    private fun hasReadContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private fun canAskAgain(): Boolean =
        shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by themePreferences.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            val scope = rememberCoroutineScope()

            var tick by remember { mutableIntStateOf(0) }
            var connected by remember { mutableStateOf(false) }
            var currentScope by remember { mutableStateOf<ContactScope?>(null) }
            // The user's picker choice is explicit UI state: once the user touches it, the
            // poll stops overwriting it from the published scope (a SELECTED entry awaiting
            // permission must survive until the user acts again).
            var option by remember { mutableStateOf(ScopeOption.EMPTY) }
            var userChose by remember { mutableStateOf(false) }
            var contactsGranted by remember { mutableStateOf(false) }
            var deniedPermanently by remember { mutableStateOf(false) }
            var markers by remember { mutableStateOf(listOf<PolicyRepository.MarkerContact>()) }
            var feedback by remember { mutableStateOf("Waiting for the Vector daemon…") }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) {
                    feedback = "Contacts access granted."
                    tick++
                } else {
                    feedback = "Contacts access denied — SELECTED needs it to list contacts."
                    tick++
                }
            }

            androidx.compose.runtime.LaunchedEffect(tick) {
                connected = policyRepository.connected
                currentScope = policyRepository.currentScope()
                contactsGranted = hasReadContactsPermission()
                deniedPermanently = !contactsGranted && !canAskAgain()
                if (!userChose) {
                    option = ScopeOption.from(currentScope)
                    feedback = if (connected) "Reading the scope published in the daemon store."
                    else "Daemon not connected — changes are not published yet."
                }
                markers = if (ContactsAccess.mayQuery(contactsGranted, option == ScopeOption.SELECTED)) {
                    policyRepository.markerContacts()
                } else {
                    emptyList()
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
                    option = option,
                    selectedKeys = (currentScope as? ContactScope.Selected)?.lookupKeys ?: emptySet(),
                    markers = markers,
                    contactsGranted = contactsGranted,
                    deniedPermanently = deniedPermanently,
                    feedback = feedback,
                    onChooseOption = { chosen ->
                        userChose = true
                        option = chosen
                        when (chosen) {
                            ScopeOption.FULL -> scope.launch {
                                val ok = policyRepository.writeScope(ContactScope.Full)
                                feedback = if (ok) "Scope published to the daemon."
                                else "Daemon not connected — not published."
                                tick++
                            }
                            ScopeOption.EMPTY -> scope.launch {
                                val ok = policyRepository.writeScope(ContactScope.Empty)
                                feedback = if (ok) "Scope published to the daemon."
                                else "Daemon not connected — not published."
                                tick++
                            }
                            ScopeOption.SELECTED -> if (!contactsGranted) {
                                feedback = "Contacts access is needed to choose contacts."
                                permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                            }
                        }
                    },
                    onToggleMarker = { key, checked ->
                        scope.launch {
                            val current = (currentScope as? ContactScope.Selected)?.lookupKeys ?: emptySet()
                            val next = if (checked) current + key else current - key
                            val ok = policyRepository.writeScope(ContactScope.from(next))
                            feedback = if (ok) "Scope published to the daemon."
                            else "Daemon not connected — not published."
                            tick++
                        }
                    },
                    onRetryAccess = {
                        if (deniedPermanently) openAppSettings()
                        else permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    },
                    onRefresh = { tick++ },
                )
            }
        }
    }
}