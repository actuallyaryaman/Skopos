package org.a4real.skopos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.core.PolicyState
import org.a4real.skopos.core.SkoposContract
import org.a4real.skopos.data.AppDiscovery
import org.a4real.skopos.data.AppRow
import org.a4real.skopos.data.PickerPolicy
import org.a4real.skopos.data.PolicyRepository
import org.a4real.skopos.data.ThemePreferences
import org.a4real.skopos.ui.AppDetailScreen
import org.a4real.skopos.ui.AppListScreen
import org.a4real.skopos.ui.ScopeOption
import org.a4real.skopos.ui.theme.SkoposTheme
import org.a4real.skopos.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    private val themePreferences by lazy { ThemePreferences(applicationContext) }

    private fun repoFor(targetPackage: String): PolicyRepository =
        PolicyRepository.get(this, targetPackage)

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
            var selectedPackage by remember { mutableStateOf<String?>(null) }

            SkoposTheme(themeMode = themeMode) {
                val pkg = selectedPackage
                if (pkg == null) {
                    AppList(
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            scope.launch { themePreferences.setThemeMode(mode) }
                        },
                        onOpenApp = { selectedPackage = it },
                    )
                } else {
                    BackHandler { selectedPackage = null }
                    key(pkg) {
                        AppDetail(
                            targetPackage = pkg,
                            onBack = { selectedPackage = null },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun AppList(
        themeMode: ThemeMode,
        onThemeModeChange: (ThemeMode) -> Unit,
        onOpenApp: (String) -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        var tick by remember { mutableIntStateOf(0) }
        var connected by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        var showSystem by remember { mutableStateOf(false) }
        var rows by remember { mutableStateOf(emptyList<AppRow>()) }
        var feedback by remember { mutableStateOf("Waiting for the Vector daemon…") }
        var manualInput by remember { mutableStateOf("") }
        var manualError by remember { mutableStateOf<String?>(null) }
        var manualPackages by remember { mutableStateOf(emptySet<String>()) }

        LaunchedEffect(tick) {
            // Capture UI state on the main thread; the IO block below must not read it.
            val manual = manualPackages
            val system = showSystem
            val q = query
            val result = withContext(Dispatchers.IO) {
                // Connectivity probe: the daemon service is shared, so any repo instance
                // reports the same bound state.
                val probe = repoFor(SkoposContract.TEST_PACKAGE)
                val isConnected = probe.connected
                val scopePkgs = if (isConnected) {
                    probe.vectorScope() ?: emptyList()
                } else emptyList()
                val discovered = AppDiscovery.launchableApps(this@MainActivity)
                val listRows = AppDiscovery.assembleRows(
                    scopePackages = if (isConnected) scopePkgs else null,
                    discovered = discovered,
                    manualPackages = manual,
                    showSystem = system,
                    policyFor = { pkg ->
                        if (isConnected) repoFor(pkg).currentPolicy() else null
                    },
                ).filter {
                    q.isBlank() ||
                        it.label.contains(q, ignoreCase = true) ||
                        it.packageName.contains(q, ignoreCase = true)
                }
                Triple(isConnected, listRows, scopePkgs.size)
            }
            connected = result.first
            rows = result.second
            feedback = if (result.first) {
                "${result.second.size} apps · ${result.third} in Vector scope."
            } else {
                "Daemon not connected — showing discovery only."
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                delay(5000)
                tick++
            }
        }

        AppListScreen(
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            connected = connected,
            query = query,
            onQueryChange = { query = it },
            showSystem = showSystem,
            onShowSystemChange = { showSystem = it },
            rows = rows,
            feedback = feedback,
            manualInput = manualInput,
            onManualInputChange = {
                manualInput = it
                manualError = null
            },
            manualError = manualError,
            onManualSubmit = {
                val pkg = manualInput.trim()
                if (!AppDiscovery.isValidPackageName(pkg)) {
                    manualError = "Not a valid package name (e.g. com.example.app)."
                } else {
                    manualPackages = manualPackages + pkg
                    manualInput = ""
                    manualError = null
                    tick++
                }
            },
            onOpenApp = onOpenApp,
            onRefresh = { tick++ },
        )
    }

    @Composable
    private fun AppDetail(
        targetPackage: String,
        onBack: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        val repository = remember(targetPackage) { repoFor(targetPackage) }

        var tick by remember { mutableIntStateOf(0) }
        var connected by remember { mutableStateOf(false) }
        var policy by remember { mutableStateOf<PolicyState?>(null) }
        // Tab/option latches only after a successful daemon write; a failed write clears the
        // latch so the poll below resyncs the display from the store.
        var option by remember { mutableStateOf<ScopeOption?>(null) }
        var userChose by remember { mutableStateOf(false) }
        // Transient editing state, independent of the durable policy: the picker stays open
        // across polls and failed writes, and closes only on an explicit mode change,
        // reset, or navigation. The poll below never touches it.
        var pickerOpen by remember { mutableStateOf(false) }
        var vectorActive by remember { mutableStateOf<Boolean?>(null) }
        var vectorPending by remember { mutableStateOf(false) }
        var contactsGranted by remember { mutableStateOf(false) }
        var deniedPermanently by remember { mutableStateOf(false) }
        var contacts by remember { mutableStateOf(emptyList<PolicyRepository.DeviceContact>()) }
        var contactsError by remember { mutableStateOf(false) }
        var search by remember { mutableStateOf("") }
        var feedback by remember { mutableStateOf("Waiting for the Vector daemon…") }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            feedback = if (granted) "Contacts access granted."
            else "Contacts access denied — SELECTED needs it to list contacts."
            tick++
        }

        LaunchedEffect(tick) {
            // Capture UI state on the main thread; the IO block below must not read it.
            val open = pickerOpen
            val loaded = withContext(Dispatchers.IO) {
                val isConnected = repository.connected
                val state = repository.currentPolicy()
                val active = if (isConnected) {
                    repository.vectorScope()?.contains(targetPackage)
                } else null
                val granted = hasReadContactsPermission()
                val opt = ScopeOption.fromPolicy(state)
                var loadFailed = false
                val list = if (ScopeOption.pickerLoads(granted, open)) {
                    try {
                        repository.deviceContacts()
                    } catch (_: Throwable) {
                        loadFailed = true
                        emptyList()
                    }
                } else emptyList()
                LoadedDetail(isConnected, state, active, granted, opt, list, loadFailed)
            }
            connected = loaded.connected
            policy = loaded.policy
            vectorActive = loaded.vectorActive
            contactsGranted = loaded.granted
            deniedPermanently = !loaded.granted && !canAskAgain()
            if (!userChose) {
                option = loaded.option
                feedback = if (loaded.connected) "Reading the scope published in the daemon store."
                else "Daemon not connected — changes are not published yet."
            }
            // Selection and picker rows always follow the durable store, never local edits.
            contacts = loaded.contacts
            contactsError = loaded.loadFailed
        }
        LaunchedEffect(Unit) {
            while (true) {
                delay(2000)
                tick++
            }
        }

        val selectedKeys = (policy as? PolicyState.Configured)
            ?.scope?.let { it as? ContactScope.Selected }?.lookupKeys ?: emptySet()
        val staleCount = if (selectedKeys.isEmpty() || contacts.isEmpty()) 0
        else selectedKeys.count { key -> contacts.none { it.lookupKey == key } }
        val corrupt = policy is PolicyState.Corrupt

        // Device rows keyed by durable lookup key; used only for display/selection identity.
        AppDetailScreen(
            appLabel = targetPackage,
            packageName = targetPackage,
            connected = connected,
            policy = policy,
            corrupt = corrupt,
            vectorActive = vectorActive,
            vectorRequestPending = vectorPending,
            option = option,
            pickerOpen = pickerOpen,
            selectedKeys = selectedKeys,
            contacts = contacts,
            contactsError = contactsError,
            staleCount = staleCount,
            search = search,
            onSearchChange = { search = it },
            contactsGranted = contactsGranted,
            deniedPermanently = deniedPermanently,
            feedback = feedback,
            onChooseOption = { chosen ->
                when (chosen) {
                    ScopeOption.FULL -> scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            repository.writeScope(ContactScope.Full)
                        }
                        if (ok) {
                            option = ScopeOption.FULL
                            userChose = true
                            pickerOpen = false
                            feedback = "Scope published to the daemon."
                        } else {
                            userChose = false
                            feedback = "Daemon not connected — not published."
                        }
                        tick++
                    }
                    ScopeOption.EMPTY -> scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            repository.writeScope(ContactScope.Empty)
                        }
                        if (ok) {
                            option = ScopeOption.EMPTY
                            userChose = true
                            pickerOpen = false
                            feedback = "Scope published to the daemon."
                        } else {
                            userChose = false
                            feedback = "Daemon not connected — not published."
                        }
                        tick++
                    }
                    ScopeOption.SELECTED -> when (PickerPolicy.decideOpenSelected(contactsGranted, policy)) {
                        PickerPolicy.PickerOpenAction.RequestPermission -> {
                            feedback = "Contacts access is needed to choose contacts."
                            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                        }
                        // Opening the picker is navigation only: it must never publish
                        // (in particular never ContactScope.from(emptySet()) == EMPTY).
                        // A SELECTED policy persists exclusively via contact toggles.
                        // Toggle writes below never touch pickerOpen, so even a failed
                        // write leaves the picker open on the durable (unchanged) state.
                        PickerPolicy.PickerOpenAction.OpenPicker -> {
                            option = ScopeOption.SELECTED
                            userChose = true
                            pickerOpen = true
                            feedback = "Choose contacts to publish a selection."
                            tick++
                        }
                    }
                }
            },
            onToggleContact = { key, checked ->
                scope.launch {
                    val next = if (checked) selectedKeys + key else selectedKeys - key
                    val published = ContactScope.from(next)
                    val ok = withContext(Dispatchers.IO) { repository.writeScope(published) }
                    if (ok) {
                        option = ScopeOption.from(published)
                        userChose = true
                        feedback = "Scope published to the daemon."
                    } else {
                        userChose = false
                        feedback = "Daemon not connected — not published."
                    }
                    tick++
                }
            },
            onRetryAccess = {
                if (deniedPermanently) openAppSettings()
                else permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            },
            onRefresh = { tick++ },
            onReset = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { repository.resetPolicy() }
                    userChose = false
                    if (ok) pickerOpen = false
                    feedback = if (ok) "Policy cleared — native behavior."
                    else "Daemon not connected — not cleared."
                    tick++
                }
            },
            onRequestVectorScope = {
                vectorPending = true
                val started = repository.requestVectorScope(
                    targetPackage,
                    object : XposedService.OnScopeEventListener {
                        override fun onScopeRequestApproved(packages: List<String>) {
                            scope.launch {
                                vectorPending = false
                                feedback = "Vector scope approved."
                                tick++
                            }
                        }

                        override fun onScopeRequestFailed(reason: String) {
                            scope.launch {
                                vectorPending = false
                                feedback = "Vector scope request failed: $reason"
                                tick++
                            }
                        }
                    },
                )
                if (!started) {
                    vectorPending = false
                    feedback = "Daemon not connected — cannot request scope."
                }
            },
            onBack = onBack,
        )
    }

    private data class LoadedDetail(
        val connected: Boolean,
        val policy: PolicyState?,
        val vectorActive: Boolean?,
        val granted: Boolean,
        val option: ScopeOption?,
        val contacts: List<PolicyRepository.DeviceContact>,
        val loadFailed: Boolean,
    )
}
