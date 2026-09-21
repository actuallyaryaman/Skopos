package org.a4real.skopos

import android.Manifest
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import org.a4real.skopos.data.AboutLinks
import org.a4real.skopos.data.AboutMetadata
import org.a4real.skopos.data.AppDiscovery
import org.a4real.skopos.data.AppLaunch
import org.a4real.skopos.data.AppRow
import org.a4real.skopos.data.PickerPolicy
import org.a4real.skopos.data.PolicyRepository
import org.a4real.skopos.data.SearchQuery
import org.a4real.skopos.data.ThemePreferences
import org.a4real.skopos.ui.AdvancedSettingsScreen
import org.a4real.skopos.ui.AboutScreen
import org.a4real.skopos.ui.AppDetailScreen
import org.a4real.skopos.ui.AppListScreen
import org.a4real.skopos.ui.BottomTab
import org.a4real.skopos.ui.HomeScreen
import org.a4real.skopos.ui.ManagerBottomBar
import org.a4real.skopos.ui.Route
import org.a4real.skopos.ui.ScopeOption
import org.a4real.skopos.ui.SettingsScreen
import org.a4real.skopos.ui.TransitionKind
import org.a4real.skopos.ui.transitionKind
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
            val dynamicColors by themePreferences.dynamicColors.collectAsStateWithLifecycle(true)
            val scope = rememberCoroutineScope()
            var route by remember { mutableStateOf<Route>(Route.Home) }
            // Manual packages live above list and Settings so both screens share them.
            var manualPackages by remember { mutableStateOf(emptySet<String>()) }
            var manualInput by remember { mutableStateOf("") }
            var manualError by remember { mutableStateOf<String?>(null) }

            SkoposTheme(themeMode = themeMode, dynamicColors = dynamicColors) {
                // Page content animates per route; the bottom bar is a stationary overlay
                // outside the animation so it never slides with pages.
                Box(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = route,
                        transitionSpec = {
                            when (transitionKind(initialState, targetState)) {
                                TransitionKind.FORWARD ->
                                    (slideInHorizontally(tween(200)) { it / 4 } +
                                        fadeIn(tween(200))) togetherWith
                                        (slideOutHorizontally(tween(200)) { -it / 4 } +
                                            fadeOut(tween(200)))
                                TransitionKind.BACKWARD ->
                                    (slideInHorizontally(tween(200)) { -it / 4 } +
                                        fadeIn(tween(200))) togetherWith
                                        (slideOutHorizontally(tween(200)) { it / 4 } +
                                            fadeOut(tween(200)))
                                TransitionKind.TOP_LEVEL_RIGHT ->
                                    (slideInHorizontally(tween(200)) { it / 7 } +
                                        fadeIn(tween(200))) togetherWith
                                        (slideOutHorizontally(tween(200)) { -it / 7 } +
                                            fadeOut(tween(200)))
                                TransitionKind.TOP_LEVEL_LEFT ->
                                    (slideInHorizontally(tween(200)) { -it / 7 } +
                                        fadeIn(tween(200))) togetherWith
                                        (slideOutHorizontally(tween(200)) { it / 7 } +
                                            fadeOut(tween(200)))
                                TransitionKind.TOP_LEVEL ->
                                    fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                            }
                        },
                        label = "route",
                    ) { current ->
                        when (current) {
                    is Route.Home -> HomeScreen(
                        onOpenContacts = { route = Route.ContactsApps },
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
                        key(current.entry.packageName) {
                            AppDetail(
                                targetPackage = current.entry.packageName,
                                entry = current.entry,
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
                            dynamicColors = dynamicColors,
                            onDynamicColorsChange = { enabled ->
                                scope.launch { themePreferences.setDynamicColors(enabled) }
                            },
                            onOpenAdvanced = { route = Route.AdvancedSettings },
                            onOpenAbout = { route = Route.About },
                        )
                    }
                    is Route.About -> {
                        BackHandler { route = Route.Settings }
                        AboutScreen(
                            metadata = AboutMetadata.load(packageManager, packageName),
                            onOpenUrl = { url ->
                                if (!AboutLinks.open(this@MainActivity, url)) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Could not open link.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            onBack = { route = Route.Settings },
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
                    val tab = route.bottomTab()
                    if (tab != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .navigationBarsPadding()
                                .padding(bottom = 12.dp),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
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
                }
            }
        }
    }

    @Composable
    private fun AppList(
        manualPackages: Set<String>,
        onOpenApp: (AppRow) -> Unit,
        onBack: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        var tick by remember { mutableIntStateOf(0) }
        var inventoryTick by remember { mutableIntStateOf(0) }
        var connected by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        var showSystem by remember { mutableStateOf(false) }
        var rawEntries by remember { mutableStateOf(emptyList<AppDiscovery.AppEntry>()) }
        var enrichment by remember { mutableStateOf<Enrichment?>(null) }
        var feedback by remember { mutableStateOf("Waiting for the Vector daemon…") }

        // Local inventory: PackageManager only, no daemon touch. Runs on entry, on
        // manual-package change, and on explicit Refresh — never on the 5 s tick.
        LaunchedEffect(manualPackages, inventoryTick) {
            rawEntries = withContext(Dispatchers.IO) {
                AppDiscovery.discoverAppsForPermission(
                    this@MainActivity,
                    android.Manifest.permission.READ_CONTACTS,
                )
            }
        }

        // Daemon enrichment: scope + per-package policies merged by package name. Never
        // reruns PackageManager discovery; a missing/slow daemon leaves local rows intact.
        LaunchedEffect(tick) {
            // Capture UI state on the main thread; the IO block below must not read it.
            val manual = manualPackages
            val known = rawEntries.map { it.packageName }
            val result = withContext(Dispatchers.IO) {
                // Connectivity probe: the daemon service is shared, so any repo instance
                // reports the same bound state.
                val probe = repoFor(SkoposContract.TEST_PACKAGE)
                val isConnected = probe.connected
                if (!isConnected) {
                    Enrichment(connected = false, scopePackages = null, policies = emptyMap())
                } else {
                    val scopePkgs = probe.vectorScope() ?: emptyList()
                    val policies = ((scopePkgs + known + manual.toList()).distinct()).associateWith { pkg ->
                        runCatching { repoFor(pkg).currentPolicy() }.getOrNull()
                    }
                    Enrichment(connected = true, scopePackages = scopePkgs, policies = policies)
                }
            }
            connected = result.connected
            enrichment = result
            feedback = if (result.connected) {
                "${rawEntries.size} apps · ${result.scopePackages?.size ?: 0} in Vector scope."
            } else {
                "Daemon not connected — showing discovery only."
            }
        }

        // Pure in-memory merge + filter + grouping: every keystroke updates immediately
        // with zero provider/daemon work. SearchQuery import already present.
        val rows = remember(rawEntries, manualPackages, enrichment, showSystem) {
            AppDiscovery.assembleRows(
                scopePackages = enrichment?.scopePackages,
                discovered = rawEntries,
                manualPackages = manualPackages,
                showSystem = showSystem,
                policyFor = { pkg -> enrichment?.policies?.get(pkg) },
            )
        }
        val visible = remember(query, rows) {
            rows.filter { SearchQuery.matches(query, it.label, it.packageName) }
        }
        val managed = remember(visible) { AppDiscovery.groupApps(visible).first }
        val other = remember(visible) { AppDiscovery.groupApps(visible).second }
        LaunchedEffect(Unit) {
            while (true) {
                delay(5000)
                tick++
            }
        }

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
            onRefresh = {
                tick++
                inventoryTick++
            },
            onBack = onBack,
        )
    }

    /** Daemon-side enrichment merged onto local inventory by package name. */
    private data class Enrichment(
        val connected: Boolean,
        val scopePackages: List<String>?,
        val policies: Map<String, PolicyState?>,
    )

    @Composable
    private fun AppDetail(
        targetPackage: String,
        entry: AppRow,
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
        // Launchability resolves once per detail session off the poll path; null means
        // not yet resolved, false hides the Open action.
        var hasLaunchIntent by remember { mutableStateOf<Boolean?>(null) }
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
            hasLaunchIntent = withContext(Dispatchers.IO) {
                AppLaunch.launchSenderOrNull(packageManager, targetPackage) != null
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
            entry = entry,
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
                            repository.setFullPreservingSelection()
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
                            repository.setEmptyPreservingSelection()
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
                        // Opening the picker publishes only when remembered keys exist
                        // (an explicit scope choice restoring them); otherwise navigation
                        // only — never ContactScope.from(emptySet()) == EMPTY.
                        // A SELECTED policy otherwise persists exclusively via toggles.
                        // Toggle writes below never touch pickerOpen, so even a failed
                        // write leaves the picker open on the durable (unchanged) state.
                        PickerPolicy.PickerOpenAction.OpenPicker -> scope.launch {
                            val published = withContext(Dispatchers.IO) {
                                repository.selectRemembered()
                            }
                            option = ScopeOption.SELECTED
                            userChose = true
                            pickerOpen = true
                            feedback = when (published) {
                                true -> "Restored remembered selection."
                                false -> "Daemon not connected — not published."
                                null -> "Choose contacts to publish a selection."
                            }
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
                    // Deselect-last becomes explicit EMPTY + cleared memory via setSelected.
                    val ok = withContext(Dispatchers.IO) { repository.setSelected(next) }
                    if (ok) {
                        option = ScopeOption.from(ContactScope.from(next))
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
                    val ok = withContext(Dispatchers.IO) { repository.resetContactPolicy() }
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
            hasLaunchIntent = hasLaunchIntent == true,
            onOpenApp = {
                val sender = AppLaunch.launchSenderOrNull(packageManager, targetPackage)
                if (sender == null) {
                    feedback = "Could not open app."
                } else {
                    try {
                        sender.sendIntent(this@MainActivity, 0, null, null, null)
                    } catch (_: IntentSender.SendIntentException) {
                        feedback = "Could not open app."
                    }
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
