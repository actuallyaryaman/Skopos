package org.a4real.skopos.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.core.PolicyState
import org.a4real.skopos.data.AppRow
import org.a4real.skopos.data.ContactsAccess
import org.a4real.skopos.data.ContactsRetry
import org.a4real.skopos.data.PickerPolicy
import org.a4real.skopos.data.PolicyRepository.DeviceContact
import org.a4real.skopos.data.SearchQuery
import org.a4real.skopos.ui.theme.ThemeMode

/**
 * The manager surface: an app list, and per-app detail with the mode tabs, the production
 * contact picker, Vector-scope status, and reset.
 *
 * The picker never converts the published policy on its own: FULL/EMPTY/RESET are published
 * when chosen, SELECTED persists per toggled contact. Absence of policy (UNSET) is shown as
 * "Not configured" — never as an empty scope.
 */
@Composable
fun HomeScreen(
    onOpenContacts: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    Scaffold(bottomBar = bottomBar) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Skopos", style = MaterialTheme.typography.headlineLarge)
                Text(
                    text = "Choose what data apps can access.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenContacts() },
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Contacts", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Control which contacts individual apps can see.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "More privacy controls coming later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenAdvanced: () -> Unit,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    Scaffold(bottomBar = bottomBar) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "← Privacy controls",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onBack() },
            )
            Text(text = "Settings", style = MaterialTheme.typography.headlineLarge)

            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
            )
            ThemeSection(themeMode = themeMode, onChange = onThemeModeChange)

            Text(
                text = "Advanced",
                style = MaterialTheme.typography.titleMedium,
            )
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenAdvanced() },
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Advanced settings", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Manual package tools and power-user options.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun AdvancedSettingsScreen(
    manualInput: String,
    onManualInputChange: (String) -> Unit,
    manualError: String?,
    onManualSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "← Settings",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onBack() },
            )
            Text(text = "Advanced settings", style = MaterialTheme.typography.headlineLarge)

            Text(
                text = "Manual package",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Use this only for apps that do not appear in the normal app list. " +
                    "Add a package that launcher discovery cannot see. " +
                    "Configuration can still be saved; enforcement needs Vector scope.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = manualInput,
                onValueChange = onManualInputChange,
                label = { Text("Package name") },
                placeholder = { Text("com.example.app") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (manualError != null) {
                Text(
                    text = manualError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = onManualSubmit) { Text("Add package") }
        }
    }
}

@Composable
fun AppListScreen(
    connected: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    showSystem: Boolean,
    onShowSystemChange: (Boolean) -> Unit,
    managed: List<AppRow>,
    other: List<AppRow>,
    feedback: String,
    onOpenApp: (AppRow) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "← Privacy controls",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onBack() },
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Contacts", style = MaterialTheme.typography.headlineLarge)
                Text(
                    text = "Choose an app to control which contacts it can see.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showSystem, onCheckedChange = onShowSystemChange)
                Text(
                    text = "Show system apps (launchable)",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (managed.isNotEmpty()) {
                    item(key = "header-managed") {
                        Text(
                            text = "MANAGED APPS",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(managed, key = { "m:${it.packageName}" }) { row ->
                        AppRowCard(row = row, connected = connected, onOpenApp = onOpenApp)
                    }
                }
                if (other.isNotEmpty()) {
                    item(key = "header-other") {
                        Text(
                            text = "APPS WITH CONTACT ACCESS",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(other, key = { "o:${it.packageName}" }) { row ->
                        AppRowCard(row = row, connected = connected, onOpenApp = onOpenApp)
                    }
                }
            }

            Text(
                text = feedback,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (connected) "daemon connected" else "daemon disconnected",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = "Refresh",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.End)
                    .clickable { onRefresh() },
            )
        }
    }
}

@Composable
private fun AppRowCard(
    row: AppRow,
    connected: Boolean,
    onOpenApp: (AppRow) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenApp(row) },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon(icon = row.icon, label = row.label)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = row.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = row.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = appStatusLine(row, connected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.unverified) {
                    Text(
                        text = "Package visibility unavailable — configuration can still be saved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppIcon(
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    label: String,
) {
    if (icon != null) {
        androidx.compose.foundation.Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
    } else {
        Surface(
            shape = MaterialTheme.shapes.small,
            tonalElevation = 2.dp,
            modifier = Modifier.size(40.dp),
        ) {
            androidx.compose.foundation.layout.Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Text(
                    text = label.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun appStatusLine(row: AppRow, connected: Boolean): String {
    if (!connected) return "daemon unreachable"
    val vector = when (row.vectorActive) {
        true -> "Vector active"
        false -> "Vector inactive"
        null -> "Vector unknown"
    }
    val policy = when (val state = row.policy) {
        is PolicyState.Configured -> when (val scope = state.scope) {
            is ContactScope.Full -> "scope: all"
            is ContactScope.Empty -> "scope: none"
            is ContactScope.Selected -> "scope: ${scope.lookupKeys.size} selected"
        }
        is PolicyState.Corrupt -> "scope: unreadable (fail-closed)"
        is PolicyState.Unset, null -> "scope: not configured"
    }
    // Declaration is proven by the manifest; grant state is metadata only, never a filter.
    val access = if (!row.declaresReadContacts) "" else when (row.readContactsGranted) {
        true -> " · Contacts allowed"
        false -> " · Contacts not allowed"
        null -> " · Declares Contacts access"
    }
    return "$vector · $policy$access"
}

@Composable
fun AppDetailScreen(
    entry: AppRow,
    packageName: String,
    connected: Boolean,
    policy: PolicyState?,
    corrupt: Boolean,
    vectorActive: Boolean?,
    vectorRequestPending: Boolean,
    option: ScopeOption?,
    pickerOpen: Boolean,
    selectedKeys: Set<String>,
    resolvedIds: Set<Long>,
    contacts: List<DeviceContact>?,
    contactsLoading: Boolean,
    contactsError: Boolean,
    staleCount: Int,
    search: String,
    onSearchChange: (String) -> Unit,
    contactsGranted: Boolean,
    deniedPermanently: Boolean,
    feedback: String,
    onChooseOption: (ScopeOption) -> Unit,
    onToggleContact: (String, Boolean) -> Unit,
    onRetryAccess: () -> Unit,
    onRefresh: () -> Unit,
    onReset: () -> Unit,
    onRemoveFromScope: () -> Unit,
    hasLaunchIntent: Boolean,
    onOpenApp: () -> Unit,
    onRequestVectorScope: () -> Unit,
    onBack: () -> Unit,
) {
    // Memoized on its true inputs only: typing filters instantly in memory, while
    // unrelated 2 s poll recompositions reuse the cached list. Null contacts means
    // not loaded yet (never stale-inferring); the loading branch below covers it.
    val shown = remember(search, contacts, selectedKeys, resolvedIds) {
        val rows = contacts ?: emptyList()
        PickerPolicy.sortPicker(
            rows.filter { contact ->
                SearchQuery.matches(search, contact.displayName, contact.secondary)
            },
        ) { contact ->
            PickerPolicy.isRowChecked(
                contact.lookupKey,
                contact.id,
                selectedKeys,
                resolvedIds,
            )
        }
    }
    Scaffold { innerPadding ->
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "← All apps",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onBack() },
            )
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(icon = entry.icon, label = entry.label)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        text = entry.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                if (hasLaunchIntent) {
                    IconButton(onClick = onOpenApp) {
                        Icon(
                            imageVector = Icons.Filled.OpenInNew,
                            contentDescription = "Launch app",
                        )
                    }
                }
            }
        }

        // Unknown (not yet read) must never present as unconfigured: only a loaded
        // UNSET shows "Not configured". While disconnected the existing
        // daemon-disconnected presentation below applies unchanged.
        if (policy == null && connected) {
            item {
                Text(
                    text = "Loading policy…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (option == null) {
            item {
                Text(
                    text = "Not configured — ${entry.label} sees contacts normally.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (corrupt) {
            item {
                Text(
                    text = "Stored policy is unreadable — failing closed (no contacts). Reset to clear it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        item {
            VectorSection(
                connected = connected,
                vectorActive = vectorActive,
                pending = vectorRequestPending,
                onRequestVectorScope = onRequestVectorScope,
            )
        }

        item {
            ScopeTabs(
                option = option,
                tabsEnabled = policy != null || !connected,
                feedback = feedback,
                connected = connected,
                onChooseOption = onChooseOption,
                onRefresh = onRefresh,
            )
        }

        item {
            AdvancedSection(
                onReset = onReset,
                onRemoveFromScope = onRemoveFromScope,
            )
        }

        // The picker follows the transient editing state, not the persisted option:
        // a failed write or a lagging poll must never close it mid-selection.
        if (pickerOpen) {
            if (contactsGranted) {
                item {
                    OutlinedTextField(
                        value = search,
                        onValueChange = onSearchChange,
                        label = { Text("Search contacts") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (staleCount > 0) {
                    item {
                        Text(
                            text = "$staleCount previously selected contact(s) no longer on this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                // Memoized above on (search, contacts, selectedKeys, resolvedIds).
                if (contactsLoading) {
                    item {
                        Text(
                            text = "Loading contacts…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (shown.isEmpty()) {
                    item {
                        Text(
                            text = if (contactsError) "Couldn't load contacts — check access and retry."
                            else "No contacts match.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(shown, key = { it.lookupKey }) { contact ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = PickerPolicy.isRowChecked(
                                contact.lookupKey,
                                contact.id,
                                selectedKeys,
                                resolvedIds,
                            ),
                            onCheckedChange = { checked ->
                                onToggleContact(contact.lookupKey, checked)
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(contact.displayName, style = MaterialTheme.typography.bodyMedium)
                            if (contact.secondary != null) {
                                Text(
                                    text = contact.secondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Contacts access is needed to choose contacts.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        val retry = ContactsAccess.retry(deniedPermanently)
                        Button(onClick = onRetryAccess) { Text(retry.label) }
                        if (retry == ContactsRetry.OPEN_SETTINGS) {
                            Text(
                                text = "Permission is turned off for this app. Open settings to enable it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun VectorSection(
    connected: Boolean,
    vectorActive: Boolean?,
    pending: Boolean,
    onRequestVectorScope: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Vector scope", style = MaterialTheme.typography.titleMedium)
            when {
                !connected -> Text(
                    text = "Daemon unreachable.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                vectorActive == true -> Text(
                    text = "Active — Contact Scope is enforced for this app.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> {
                    Text(
                        text = "Policy saved. Add this app to Skopos scope to enforce it. " +
                            "Vector will ask for your approval — Skopos cannot enable this silently.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onRequestVectorScope, enabled = !pending) {
                        Text(if (pending) "Request sent…" else "Add to Skopos scope")
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeTabs(
    option: ScopeOption?,
    tabsEnabled: Boolean,
    feedback: String,
    connected: Boolean,
    onChooseOption: (ScopeOption) -> Unit,
    onRefresh: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Contact scope", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ScopeOption.tabs.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = entry == option,
                        onClick = { onChooseOption(entry) },
                        enabled = tabsEnabled,
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ScopeOption.tabs.size,
                        ),
                    ) { Text(text = entry.label) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = feedback,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (connected) "daemon connected" else "daemon disconnected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Refresh",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onRefresh() },
                )
            }
        }
    }
}

@Composable
private fun AdvancedSection(
    onReset: () -> Unit,
    onRemoveFromScope: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Advanced", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Reset contact policy",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable { onReset() },
            )
            Text(
                text = "Remove from Skopos scope",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable { onRemoveFromScope() },
            )
            Text(
                text = "Removing scope keeps the saved contact policy; enforcement simply stops. " +
                    "Re-adding the app later restores it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ManagerBottomBar(
    current: BottomTab,
    onSelect: (BottomTab) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NavigationBarItem(
                    selected = current == BottomTab.HOME,
                    onClick = { onSelect(BottomTab.HOME) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = "Home",
                        )
                    },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = current == BottomTab.SETTINGS,
                    onClick = { onSelect(BottomTab.SETTINGS) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                        )
                    },
                    label = { Text("Settings") },
                )
            }
        }
    }
}

@Composable
private fun ThemeSection(themeMode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Theme", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = mode == themeMode,
                        onClick = { onChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ThemeMode.entries.size,
                        ),
                    ) { Text(text = mode.label) }
                }
            }
        }
    }
}

enum class ScopeOption(val label: String) {
    FULL("All contacts"),
    EMPTY("No contacts"),
    SELECTED("Only selected");

    companion object {
        /** Writable tabs. UNSET has no tab: absence is displayed, never published. */
        val tabs: List<ScopeOption> = listOf(FULL, EMPTY, SELECTED)

        /**
         * Whether the picker contact list should load: granted permission plus the UI picker
         * being open. Deliberately independent of the persisted policy — opening SELECTED
         * from UNSET/FULL/EMPTY must still load contacts, or the picker deadlocks empty.
         */
        fun pickerLoads(granted: Boolean, pickerOpen: Boolean): Boolean =
            ContactsAccess.mayQuery(granted, pickerOpen)

        fun fromPolicy(state: PolicyState?): ScopeOption? = when (state) {
            is PolicyState.Configured -> when (state.scope) {
                is ContactScope.Full -> FULL
                is ContactScope.Empty -> EMPTY
                is ContactScope.Selected -> SELECTED
            }
            else -> null
        }

        fun from(scope: ContactScope?): ScopeOption = when (scope) {
            is ContactScope.Full -> FULL
            is ContactScope.Selected -> SELECTED
            else -> EMPTY
        }
    }
}
