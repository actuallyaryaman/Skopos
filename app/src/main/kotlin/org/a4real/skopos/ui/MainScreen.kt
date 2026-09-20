package org.a4real.skopos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import org.a4real.skopos.data.PolicyRepository.MarkerContact
import org.a4real.skopos.ui.theme.ThemeMode

/**
 * The manager surface: connection state, the authoritative scope, and the picker that writes
 * it. A SELECTED scope with an empty subset is never produced — the empty selection collapses
 * to [ContactScope.Empty], which the checkbox section simply keeps visible until the user's
 * next explicit choice.
 */
@Composable
fun MainScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    connected: Boolean,
    scope: ContactScope?,
    markers: List<MarkerContact>,
    feedback: String,
    onPublish: (ContactScope) -> Unit,
    onRefresh: () -> Unit,
) {
    val selectedKeys = (scope as? ContactScope.Selected)?.lookupKeys ?: emptySet()
    val option = when (scope) {
        is ContactScope.Full -> ScopeOption.FULL
        is ContactScope.Selected -> ScopeOption.SELECTED
        else -> ScopeOption.EMPTY
    }

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
                scope = scope,
                selectedKeys = selectedKeys,
                markers = markers,
                feedback = feedback,
                onPublish = onPublish,
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
    scope: ContactScope?,
    selectedKeys: Set<String>,
    markers: List<MarkerContact>,
    feedback: String,
    onPublish: (ContactScope) -> Unit,
    onRefresh: () -> Unit,
) {
    val option = when (scope) {
        is ContactScope.Full -> ScopeOption.FULL
        is ContactScope.Selected -> ScopeOption.SELECTED
        else -> ScopeOption.EMPTY
    }

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
                        onClick = { onPublish(entry.intoScope(null)) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ScopeOption.entries.size,
                        ),
                    ) { Text(text = entry.label) }
                }
            }

            if (option == ScopeOption.SELECTED) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    markers.forEach { marker ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = marker.lookupKey in selectedKeys,
                                onCheckedChange = { checked ->
                                    val next =
                                        if (checked) selectedKeys + marker.lookupKey
                                        else selectedKeys - marker.lookupKey
                                    onPublish(ContactScope.from(next))
                                },
                            )
                            Text(marker.displayName, style = MaterialTheme.typography.bodyMedium)
                        }
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

    fun intoScope(selectedKeys: Set<String>?): ContactScope = when (this) {
        FULL -> ContactScope.Full
        EMPTY -> ContactScope.Empty
        SELECTED -> ContactScope.from(selectedKeys ?: emptySet())
    }
}