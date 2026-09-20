package org.a4real.skopos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.a4real.skopos.core.ContactScope
import org.a4real.skopos.data.ContactsAccess
import org.a4real.skopos.data.ContactsRetry
import org.a4real.skopos.data.PolicyRepository.MarkerContact
import org.a4real.skopos.ui.theme.ThemeMode

/**
 * The manager surface: connection state, the mode the user has chosen, and — when that mode
 * is SELECTED — either the marker picker (READ_CONTACTS granted) or a small pane explaining
 * that contact access is needed, with a retry/settings control.
 *
 * The picker never converts the published policy on its own: FULL/EMPTY are published when
 * chosen, SELECTED is entered locally and only persists once a marker is toggled.
 */
@Composable
fun MainScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    connected: Boolean,
    option: ScopeOption,
    selectedKeys: Set<String>,
    markers: List<MarkerContact>,
    contactsGranted: Boolean,
    deniedPermanently: Boolean,
    feedback: String,
    onChooseOption: (ScopeOption) -> Unit,
    onToggleMarker: (String, Boolean) -> Unit,
    onRetryAccess: () -> Unit,
    onRefresh: () -> Unit,
) {
    Scaffold { innerPadding ->
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
                    text = "Privacy scoping for rooted devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ScopeSection(
                connected = connected,
                option = option,
                selectedKeys = selectedKeys,
                markers = markers,
                contactsGranted = contactsGranted,
                deniedPermanently = deniedPermanently,
                feedback = feedback,
                onChooseOption = onChooseOption,
                onToggleMarker = onToggleMarker,
                onRetryAccess = onRetryAccess,
                onRefresh = onRefresh,
            )

            ThemeSection(themeMode = themeMode, onChange = onThemeModeChange)

            Text(
                text = "Scope is published to org.a4real.skopos.test through the Vector daemon.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScopeSection(
    connected: Boolean,
    option: ScopeOption,
    selectedKeys: Set<String>,
    markers: List<MarkerContact>,
    contactsGranted: Boolean,
    deniedPermanently: Boolean,
    feedback: String,
    onChooseOption: (ScopeOption) -> Unit,
    onToggleMarker: (String, Boolean) -> Unit,
    onRetryAccess: () -> Unit,
    onRefresh: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Contact scope", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ScopeOption.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = entry == option,
                        onClick = { onChooseOption(entry) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ScopeOption.entries.size,
                        ),
                    ) { Text(text = entry.label) }
                }
            }

            if (option == ScopeOption.SELECTED) {
                if (contactsGranted) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (markers.isEmpty()) {
                            Text(
                                text = "No marker contacts found on this device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        markers.forEach { marker ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = marker.lookupKey in selectedKeys,
                                    onCheckedChange = { checked ->
                                        onToggleMarker(marker.lookupKey, checked)
                                    },
                                )
                                Text(marker.displayName, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                } else {
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

            Text(
                text = "Refresh",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .align(Alignment.End)
                    .clickable { onRefresh() },
            )

            HorizontalDivider()

            Text(
                text = "Marker contacts on device: ${markers.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        fun from(scope: ContactScope?): ScopeOption = when (scope) {
            is ContactScope.Full -> FULL
            is ContactScope.Selected -> SELECTED
            else -> EMPTY
        }
    }
}