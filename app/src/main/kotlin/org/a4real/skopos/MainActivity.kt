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
import org.a4real.skopos.data.SearchQuery
import org.a4real.skopos.data.ThemePreferences
import org.a4real.skopos.ui.AdvancedSettingsScreen
import org.a4real.skopos.ui.AppDetailScreen
import org.a4real.skopos.ui.AppListScreen
import org.a4real.skopos.ui.BottomTab
import org.a4real.skopos.ui.HomeScreen
import org.a4real.skopos.ui.ManagerBottomBar
import org.a4real.skopos.ui.Route
import org.a4real.skopos.ui.ScopeOption
import org.a4real.skopos.ui.SettingsScreen
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
            var route by remember { mutableStateOf<Route>(Route.Home) }
            // Manual packages live above list and Settings so both screens share them.
            var manualPackages by remember { mutableStateOf(emptySet<String>()) }
            var manualInput by remember { mutableStateOf("") }
            var manualError by remember { mutableStateOf<String?>(null) }

            SkoposTheme(themeMode = themeMode) {
                // Single bottom-bar instance shared by top-level screens; drill-down
                // screens (ContactsApps, Detail, AdvancedSettings) use back navigation.
                val bottomBar: @Composable () -> Unit = {
                    val tab = route.bottomTab()
                    if (tab != null) {
                        ManagerBottomBar(
                            current = tab,
                            onSelect = {
                                route = when (it) {
                                    BottomTab.HOME -> Route.Home
                                    BottomTab.SETTINGS -> Route.Settings
                                }
                            },
                        )
                    }
                }
                when (val current = route) {
                    is Route.Home -> HomeScreen(
                        onOpenContacts = { route = Route.ContactsApps },
                        onOpenSettings = { route = Route.Settings },
                        bottomBar = bottomBar,
                    )
                    is Route.ContactsApps -> {
                        BackHandler { route = Route.Home }
                        AppList(
                            manualPackages = manualPackages,
                            onOpenApp = { route = Route.Detail(it) },
                            onBack = { route = Route.Home },
                        )
                    }
                    is Route.Detail -> {
                        BackHandler { route = Route.ContactsApps }
                        key(current.packageName) {
                            AppDetail(
                                targetPackage = current.packageName,
                                onBack = { route = Route.ContactsApps },
                            )
                        }
                    }
                    is Route.Settings -> {
                        BackHandler { route = Route.Home }
                        SettingsScreen(
                            themeMode = themeMode,
                            onThemeModeChange = { mode ->
                                scope.launch { themePreferences.setThemeMode(mode) }
                            },
                            onOpenAdvanced = { route = Route.AdvancedSettings },
                            onBack = { route = Route.Home },
                            bottomBar = bottomBar,
                        )
                    }
                    is Route.AdvancedSettings -> {
                        BackHandler { route = Route.Settings }
                        AdvancedSettingsScreen(
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
                                }
                            },
                            onBack = { route = Route.Settings },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun AppList(
        manualPackages: Set<String>,
        onOpenApp: (String) -> Unit,
        onBack: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        var tick by remember { mutableIntStateOf(0) }
        var connected by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        var showSystem by remember { mutableStateOf(false) }
        var rawRows by remember { mutableStateOf(emptyList<AppRow>()) }
        var feedback by remember { mutableStateOf("Waiting for the Vector daemon…") }

        LaunchedEffect(tick, manualPackages) {
            // Capture UI state on the main thread; the IO block below must not read it.
            val manual = manualPackages
            val system = showSystem
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
                )
                Triple(isConnected, listRows, scopePkgs.size)
            }
            connected = result.first
            rawRows = result.second
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

        // Pure in-memory filter + grouping: every keystroke updates immediately with
        // zero provider/daemon work. SearchQuery import already present.
        val visible = remember(query, rawRows) {
            rawRows.filter { SearchQuery.matches(query, it.label, it.packageName) }
        }
        val managed = remember(visible) { AppDiscovery.groupApps(visible).first }
        val other = remember(visible) { AppDiscovery.groupApps(visible).second }

        AppListScreen(
            connected = connected,
            query = query,
            onQueryChange = { query = it },
            showSystem = showSystem,
            onShowSystemChange = { showSystem = it },
            managed = managed,
            other = other,
            feedback = feedback,
            onOpenApp = onOpenApp,
            onRefresh = { tick++ },
            onBack = onBack,
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
        var contacts by remember { mutableStateOf<List<PolicyRepository.DeviceContact>?>(null) }
        var contactsError by remember { mutableStateOf(false) }
        // Durable resolution of persisted keys to current aggregate ids; empty when the
        // picker is closed, permission is missing, or nothing is selected.
        var resolved by remember { mutableStateOf(emptyMap<String, Long?>()) }
        // One-shot restore: the first successful read carrying a persisted non-empty
        // SELECTED scope opens the picker so the stored selection is immediately visible
        // and editable. Never writes, never re-fires: after this flag is set, polls and
        // user actions own pickerOpen exclusively.
        var pickerInitDone by remember { mutableStateOf(false) }
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
            val keysSnapshot =
                (policy as? PolicyState.Configured)?.scope?.let { it as? ContactScope.Selected }
                    ?.lookupKeys ?: emptySet()
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
                val resolvedNow =
                    if (granted && keysSnapshot.isNotEmpty()) {
                        repository.resolveSelectedKeys(keysSnapshot)
                    } else emptyMap()
                LoadedDetail(isConnected, state, active, granted, opt, list, loadFailed, resolvedNow)
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
            // Null means not loaded yet (never infer stale/deleted from it); a successful
            // load always replaces it, error or closed picker leaves the last snapshot.
            if (open) {
                contacts = loaded.contacts
                contactsError = loaded.loadFailed
            }
            resolved = loaded.resolved
            if (!pickerInitDone && loaded.policy != null) {
                pickerInitDone = true
                if (PickerPolicy.shouldAutoOpenPicker(loaded.policy)) {
                    pickerOpen = true
                }
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                delay(2000)
                tick++
            }
        }

        val selectedKeys = (policy as? PolicyState.Configured)
            ?.scope?.let { it as? ContactScope.Selected }?.lookupKeys ?: emptySet()
        // Durably resolved current ids for the persisted keys; a row counts checked when
        // its current key is stored or its id was resolved from a stored (possibly
        // since-changed) key. Stale is only computed from a loaded snapshot.
        val resolvedIds = resolved.values.filterNotNull().toSet()
        val staleCount = if (!contactsGranted) 0
        else PickerPolicy.staleKeys(selectedKeys, contacts, resolved).size
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
            resolvedIds = resolvedIds,
            contacts = contacts ?: emptyList(),
            contactsLoading = contacts == null,
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
                    // Map the tapped row back to persisted keys: its own key plus any
                    // persisted keys resolving to the same row (covers key-changed
                    // selections), so unchecking removes the durable entry and checking
                    // normalizes to the current key without duplicates.
                    val rowId = contacts?.singleOrNull { it.lookupKey == key }?.id
                    val next = PickerPolicy.toggledKeys(selectedKeys, key, rowId, resolved, checked)
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
            onRemoveFromScope = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        repository.removeVectorScope(targetPackage)
                    }
                    feedback = if (ok) "Removed from Skopos scope. Saved contact policy was kept."
                    else "Daemon not connected — still in scope."
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
        val resolved: Map<String, Long?>,
    )
}
