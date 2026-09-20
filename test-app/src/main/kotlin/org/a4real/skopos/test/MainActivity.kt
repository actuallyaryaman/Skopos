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
import org.a4real.skopos.test.ContactHarness.Check
import org.a4real.skopos.test.ContactHarness.Verdict

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
    var report by remember { mutableStateOf(
        ContactHarness.Report(0, "-", 0, "-", emptyList(), 0),
    ) }
    var seedOutcome by remember { mutableStateOf("") }
    var basicLines by remember { mutableStateOf(emptyList<Check>()) }
    var directLines by remember { mutableStateOf(emptyList<Check>()) }
    var bypassLines by remember { mutableStateOf(emptyList<Check>()) }
    var phoneLines by remember { mutableStateOf(emptyList<Check>()) }
    var sortLines by remember { mutableStateOf(emptyList<Check>()) }

    fun io(task: suspend () -> Unit) {
        scope.launch { withContext(Dispatchers.IO) { task() } }
    }

    fun refreshProbeAndReport() = io {
        probe = SkoposProbe.value()
        report = ContactHarness.report(context)
        basicLines = listOf(
            Check(Verdict.PASS, "contacts rows visible: ${report.contactCount}"),
            Check(Verdict.PASS, "contacts cursor: ${report.contactCursorClass}"),
            Check(Verdict.PASS, "phonelookup rows (first marker): ${report.lookupCount}"),
            Check(Verdict.PASS, "visible markers: ${report.visibleLabels}"),
            Check(Verdict.PASS, "cached marker refs: ${report.refsCached}"),
        )
    }

    LaunchedEffect(Unit) { refreshProbeAndReport() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Skopos Test App", style = MaterialTheme.typography.titleLarge)
        Text(text = "Probe: $probe", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = if (probe == SkoposContract.HOOKED_RESULT)
                "Runtime active: the ContactsInterceptor is wired into this process."
            else
                "Runtime inactive (unhooked probe or plain build).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section("Markers") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    io {
                        val r = ContactHarness.seed(context)
                        seedOutcome = "Seeded: ${r.inserted} new, ${r.totalSeeded} tracked."
                        refreshProbeAndReport()
                    }
                }) { Text("Seed markers") }
                OutlinedButton(onClick = {
                    io {
                        val refs = ContactHarness.resolveMarkers(context)
                        seedOutcome = "Resolved ${refs.size} marker refs (cached for hidden-marker tests)."
                        refreshProbeAndReport()
                    }
                }) { Text("Resolve markers") }
                OutlinedButton(onClick = {
                    io {
                        val r = ContactHarness.cleanup(context)
                        seedOutcome = "Cleanup: attempted ${r.attempted}, deleted ${r.deleted}, " +
                            "still tracked ${r.remaining}."
                        refreshProbeAndReport()
                    }
                }) { Text("Cleanup markers") }
            }
            if (seedOutcome.isNotEmpty()) Note(seedOutcome)
        }

        Section("Basic report", onRerun = { refreshProbeAndReport() }) {
            CheckList(basicLines)
            Note(
                "Rerun without restarting to validate live policy updates: change the manager " +
                    "policy, return here, rerun. Visible markers must follow the new policy.",
            )
        }

        Section("Direct + lookup URIs", onRerun = {
            io { directLines = ContactHarness.directAndLookupChecks(context) }
        }) {
            CheckList(directLines)
        }

        Section("Hidden-ID bypass", onRerun = {
            io { bypassLines = ContactHarness.bypassChecks(context) }
        }) {
            CheckList(bypassLines)
        }

        Section("PhoneLookup", onRerun = {
            io { phoneLines = ContactHarness.phoneLookupChecks(context) }
        }) {
            CheckList(phoneLines)
        }

        Section("Sort regression", onRerun = {
            io { sortLines = ContactHarness.sortRegressionChecks(context) }
        }) {
            CheckList(sortLines)
            Note("Null selection + non-null sortOrder under the current scope; protects the index-2 fix.")
        }
    }
}

@Composable
private fun Section(
    title: String,
    onRerun: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        if (onRerun != null) {
            OutlinedButton(onClick = onRerun) { Text("Rerun") }
        }
    }
    content()
}

@Composable
private fun CheckList(lines: List<Check>) {
    if (lines.isEmpty()) {
        Note("Not run yet — press Rerun.")
        return
    }
    val color = MaterialTheme.colorScheme
    for (check in lines) {
        val (label, c) = when (check.verdict) {
            Verdict.PASS -> "PASS" to color.primary
            Verdict.FAIL -> "FAIL" to color.error
            Verdict.NOT_TESTABLE -> "N/T" to color.onSurfaceVariant
        }
        Text(
            text = "[$label] ${check.text}",
            style = MaterialTheme.typography.bodySmall,
            color = c,
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
