package org.a4real.skopos.test

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.a4real.skopos.core.SkoposContract

class MainActivity : ComponentActivity() {

    private val contactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        contactsPermission.launch(
            arrayOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS),
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HarnessScreen()
                }
            }
        }
    }
}

@Composable
private fun HarnessScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var probe by remember { mutableStateOf("") }
    var report by remember { mutableStateOf(ContactHarness.Report(0, "-", 0, "-", ContactHarness.VisibleKeys(0, emptyList()))) }
    var seedOutcome by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            probe = withContext(Dispatchers.IO) { SkoposProbe.value() }
            report = withContext(Dispatchers.IO) { ContactHarness.report(context) }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Skopos Test App", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Probe: $probe",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = if (probe == SkoposContract.HOOKED_RESULT)
                "Runtime active: the ContactsInterceptor is wired into this process."
            else
                "Runtime inactive (unhooked probe or plain build).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = {
                scope.launch {
                    seedOutcome = withContext(Dispatchers.IO) {
                        val r = ContactHarness.seed(context)
                        "Seeded: ${r.inserted} new, ${r.totalSeeded} total markers present."
                    }
                    refresh()
                }
            }) { Text("Seed markers") }
            OutlinedButton(onClick = {
                scope.launch {
                    seedOutcome = withContext(Dispatchers.IO) {
                        val deleted = ContactHarness.cleanup(context)
                        "Cleaned up: $deleted rows."
                    }
                    refresh()
                }
            }) { Text("Cleanup markers") }
        }
        if (seedOutcome.isNotEmpty()) {
            Text(
                text = seedOutcome,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        Text(text = "Scope report", style = MaterialTheme.typography.titleMedium)
        ReportRow("Contacts visible", report.contactCount.toString())
        ReportRow("Contacts cursor", report.contactCursorClass)
        ReportRow("PhoneLookup hits (marker 0)", report.lookupCount.toString())
        ReportRow("PhoneLookup cursor", report.lookupCursorClass)
        ReportRow("Lookup keys visible", report.visibleKeys.keys.toString())

        Text(
            text = "Expect Contacts to be empty and PhoneLookup to drop the marker under an EMPTY "
                + "scope; under SELECTED only the chosen marker keys remain.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReportRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}