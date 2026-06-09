package com.arvell.simswitcher.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arvell.simswitcher.accessibility.SimSwitchAccessibilityService
import com.arvell.simswitcher.core.SimInfoProvider
import com.arvell.simswitcher.data.SettingsRepository
import com.arvell.simswitcher.model.SimInfo
import com.arvell.simswitcher.service.SimMonitorService
import com.arvell.simswitcher.service.SwitchRequestBus
import kotlinx.coroutines.launch

/**
 * Single-screen control panel: grant permissions, enable the accessibility
 * service, pick the primary SIM, tune thresholds, and start/stop monitoring.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkThemeCompat()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { MainScreen() }
            }
        }
    }
}

@Composable
private fun isSystemInDarkThemeCompat(): Boolean =
    androidx.compose.foundation.isSystemInDarkTheme()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }
    val simInfo = remember { SimInfoProvider(context) }
    val config by settings.config.collectAsStateWithLifecycle(initialValue = null)
    val status by SimMonitorService.status.collectAsStateWithLifecycle()

    var hasPhonePerm by remember { mutableStateOf(simInfo.hasPhonePermission()) }
    var sims by remember { mutableStateOf<List<SimInfo>>(emptyList()) }
    var accessibilityOn by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    var dataSubId by remember { mutableStateOf(simInfo.activeDataSubId()) }
    var manualMsg by remember { mutableStateOf<String?>(null) }
    val diagnostic by SwitchRequestBus.diagnostic.collectAsStateWithLifecycle()

    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasPhonePerm = simInfo.hasPhonePermission()
        sims = simInfo.activeSims()
        dataSubId = simInfo.activeDataSubId()
    }

    // Refresh transient state when returning to the screen.
    LaunchedEffect(Unit) {
        if (hasPhonePerm) sims = simInfo.activeSims()
        dataSubId = simInfo.activeDataSubId()
    }

    // Surface switch outcomes (manual or automatic) and refresh the active data SIM.
    LaunchedEffect(Unit) {
        SwitchRequestBus.results.collect { r ->
            manualMsg = if (r.success) "Switch command sent ✓" else "Switch failed: ${r.detail}"
            dataSubId = simInfo.activeDataSubId()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("SIM Data Switcher") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(status)

            // Step 1: permissions
            SetupStep(
                title = "1. Phone permission",
                done = hasPhonePerm,
                actionLabel = "Grant",
                onAction = {
                    val perms = buildList {
                        add(Manifest.permission.READ_PHONE_STATE)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }.toTypedArray()
                    permLauncher.launch(perms)
                },
            )

            // Step 2: accessibility service
            SetupStep(
                title = "2. Accessibility service",
                done = accessibilityOn,
                actionLabel = "Open settings",
                onAction = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
            TextButton(onClick = { accessibilityOn = isAccessibilityEnabled(context) }) {
                Text("Re-check accessibility status")
            }

            if (hasPhonePerm) {
                SimPicker(
                    sims = sims,
                    primarySubId = config?.primarySubId ?: -1,
                    onPick = { sub -> scope.launch { settings.update { it.copy(primarySubId = sub) } } },
                )

                SwitchNowCard(
                    sims = sims,
                    currentDataSubId = dataSubId,
                    accessibilityOn = accessibilityOn,
                    lastMessage = manualMsg,
                    onSwitch = { sim ->
                        manualMsg = "Switching data to ${sim.label}…"
                        scope.launch {
                            SwitchRequestBus.requestSwitch(
                                SwitchRequestBus.SwitchRequest(
                                    targetSubId = sim.subscriptionId,
                                    targetSlotIndex = sim.slotIndex,
                                    targetLabel = sim.label,
                                    reason = "Manual switch",
                                ),
                            )
                        }
                    },
                    onRefresh = {
                        sims = simInfo.activeSims()
                        dataSubId = simInfo.activeDataSubId()
                    },
                )

                DiagnosticCard(
                    accessibilityOn = accessibilityOn,
                    dump = diagnostic,
                    onScan = {
                        manualMsg = "Scanning SIM screen…"
                        val target = sims.firstOrNull { it.subscriptionId != dataSubId }
                            ?: sims.firstOrNull()
                        if (target != null) {
                            scope.launch {
                                SwitchRequestBus.requestSwitch(
                                    SwitchRequestBus.SwitchRequest(
                                        targetSubId = target.subscriptionId,
                                        targetSlotIndex = target.slotIndex,
                                        targetLabel = target.label,
                                        reason = "Diagnostic scan",
                                        diagnostic = true,
                                    ),
                                )
                            }
                        }
                    },
                    onShare = { text ->
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "SIM Data Switcher diagnostic")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(
                            Intent.createChooser(send, "Share diagnostic")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }

            config?.let { c ->
                ThresholdControls(
                    minSignalLevel = c.minSignalLevel,
                    onSignal = { v -> scope.launch { settings.update { it.copy(minSignalLevel = v) } } },
                    requireInternet = c.requireValidatedInternet,
                    onRequireInternet = { v -> scope.launch { settings.update { it.copy(requireValidatedInternet = v) } } },
                    preferPrimary = c.preferPrimary,
                    onPreferPrimary = { v -> scope.launch { settings.update { it.copy(preferPrimary = v) } } },
                )

                val ready = hasPhonePerm && accessibilityOn && sims.size >= 2
                Button(
                    onClick = {
                        val enable = !c.enabled
                        scope.launch { settings.update { it.copy(enabled = enable) } }
                        if (enable) SimMonitorService.start(context) else SimMonitorService.stop(context)
                    },
                    enabled = ready || c.enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (c.enabled) "Stop auto-switching" else "Start auto-switching")
                }
                if (!ready && !c.enabled) {
                    Text(
                        "Complete the steps above and ensure two active SIMs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: String) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Status", style = MaterialTheme.typography.labelMedium)
            Text(status, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun SetupStep(title: String, done: Boolean, actionLabel: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$title ${if (done) "✓" else ""}", style = MaterialTheme.typography.titleMedium)
        if (!done) Button(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun SimPicker(sims: List<SimInfo>, primarySubId: Int, onPick: (Int) -> Unit) {
    Column {
        Text("Primary (preferred) SIM", style = MaterialTheme.typography.titleMedium)
        if (sims.isEmpty()) {
            Text("No active SIMs detected.", style = MaterialTheme.typography.bodySmall)
        }
        sims.forEach { sim ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = sim.subscriptionId == primarySubId, onClick = { onPick(sim.subscriptionId) })
                Text("${sim.label} (slot ${sim.slotIndex + 1})")
            }
        }
    }
}

/**
 * Manual "switch now" tester: one tap asks the accessibility service to make
 * the chosen SIM the data SIM, so the switch flow can be verified without
 * waiting for a real signal drop. Requires the accessibility service enabled.
 */
@Composable
private fun SwitchNowCard(
    sims: List<SimInfo>,
    currentDataSubId: Int,
    accessibilityOn: Boolean,
    lastMessage: String?,
    onSwitch: (SimInfo) -> Unit,
    onRefresh: () -> Unit,
) {
    val current = sims.firstOrNull { it.subscriptionId == currentDataSubId }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Switch now (test)", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
            Text(
                "Current data SIM: ${current?.let { "${it.label} (slot ${it.slotIndex + 1})" } ?: "unknown"}",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!accessibilityOn) {
                Text(
                    "Enable the accessibility service (step 2) to switch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            sims.forEach { sim ->
                val isCurrent = sim.subscriptionId == currentDataSubId
                Button(
                    onClick = { onSwitch(sim) },
                    enabled = accessibilityOn && !isCurrent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (isCurrent) "Data on ${sim.label} (active)"
                        else "Switch data to ${sim.label} (slot ${sim.slotIndex + 1})",
                    )
                }
            }

            lastMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Diagnostic helper: asks the accessibility service to open the SIM screen and
 * dump its node tree, so the data-toggle layout can be inspected on-device when
 * automatic matching fails. The dump can be shared as text for tuning.
 */
@Composable
private fun DiagnosticCard(
    accessibilityOn: Boolean,
    dump: String?,
    onScan: () -> Unit,
    onShare: (String) -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Diagnostic", style = MaterialTheme.typography.titleMedium)
            Text(
                "If the switch button opens Settings but taps nothing, run this scan "
                    + "and share the result so the matching can be fixed.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = onScan,
                enabled = accessibilityOn,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Scan SIM screen")
            }
            if (!dump.isNullOrBlank()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Result", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { onShare(dump) }) { Text("Share") }
                }
                SelectionContainer {
                    Text(
                        dump,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThresholdControls(
    minSignalLevel: Int,
    onSignal: (Int) -> Unit,
    requireInternet: Boolean,
    onRequireInternet: (Boolean) -> Unit,
    preferPrimary: Boolean,
    onPreferPrimary: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Switch when signal ≤ level $minSignalLevel (0–4)", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = minSignalLevel.toFloat(),
            onValueChange = { onSignal(it.toInt()) },
            valueRange = 0f..3f,
            steps = 2,
        )
        ToggleRow("Switch on no internet (even if signal looks OK)", requireInternet, onRequireInternet)
        ToggleRow("Return to primary SIM when it recovers", preferPrimary, onPreferPrimary)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Whether our accessibility service is enabled in system settings. */
private fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${SimSwitchAccessibilityService::class.java.name}"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabled)
    while (splitter.hasNext()) {
        if (splitter.next().equals(expected, ignoreCase = true)) return true
    }
    return false
}
