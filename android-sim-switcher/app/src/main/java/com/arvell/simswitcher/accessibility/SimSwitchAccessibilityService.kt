package com.arvell.simswitcher.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.arvell.simswitcher.service.SwitchRequestBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Executes data-SIM switches by driving the system Settings UI, since no public
 * API lets a normal app change the default data subscription.
 *
 * Flow for one request:
 *  1. Open the mobile-network settings screen via a deep-link intent.
 *  2. Wait for the window, then locate the data-SIM control and the target SIM
 *     option using [SettingsNavigator] heuristics.
 *  3. Click to select the target SIM, then report success/failure on the bus.
 *
 * Because Settings layouts vary by OEM, this is best-effort: every step reports
 * a clear failure detail so the UI can tell the user to switch manually.
 */
class SimSwitchAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var pendingLabel: String? = null
    @Volatile private var pendingSubId: Int = -1
    @Volatile private var scrollAttempts: Int = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        SwitchRequestBus.accessibilityConnected = true
        scope.launch {
            SwitchRequestBus.requests.collect { request ->
                handleRequest(request)
            }
        }
    }

    private suspend fun handleRequest(request: SwitchRequestBus.SwitchRequest) {
        pendingLabel = request.targetLabel
        pendingSubId = request.targetSubId
        scrollAttempts = 0
        openMobileNetworkSettings()
        // Give Settings time to render; the actual click happens in onAccessibilityEvent
        // as windows change, but we also attempt once after a short delay as a fallback.
        delay(SETTLE_MS)
        attemptSwitch()
    }

    private fun openMobileNetworkSettings() {
        // ACTION_NETWORK_OPERATOR_SETTINGS lands on mobile networks / SIM selection
        // on most devices; fall back to general wireless settings.
        val intent = Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                reportFailure("Could not open mobile-network settings")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (pendingLabel == null) return
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            attemptSwitch()
        }
    }

    private fun attemptSwitch() {
        val label = pendingLabel ?: return
        val root = rootInActiveWindow ?: return

        // If we are on a screen that lists the SIMs for data, click the target SIM.
        val simOption = SettingsNavigator.findSimOptionByLabel(root, label)
        if (simOption != null && SettingsNavigator.click(SettingsNavigator.firstClickable(simOption) ?: simOption)) {
            finishSuccess()
            return
        }

        // Otherwise try to open the data-SIM selection entry first.
        val entry = SettingsNavigator.findDataSettingEntry(root)
        if (entry != null) {
            SettingsNavigator.click(entry)
            // Next window change will re-enter attemptSwitch() and find the SIM option.
            return
        }

        // Target not visible yet (e.g. the row is below the fold on the itelOS
        // "SIM cards & mobile networks" page): scroll and let the next content
        // change re-trigger us. Bounded so we never loop forever.
        if (scrollAttempts < MAX_SCROLL_ATTEMPTS) {
            val scrollable = SettingsNavigator.findScrollable(root)
            if (scrollable != null && SettingsNavigator.scrollForward(scrollable)) {
                scrollAttempts++
                return
            }
        } else {
            reportFailure("Could not locate the data-SIM control automatically — switch manually")
        }
    }

    private fun finishSuccess() {
        val subId = pendingSubId
        clearPending()
        scope.launch {
            SwitchRequestBus.reportResult(
                SwitchRequestBus.SwitchResult(subId, success = true, detail = "Selected target SIM"),
            )
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun reportFailure(detail: String) {
        val subId = pendingSubId
        clearPending()
        scope.launch {
            SwitchRequestBus.reportResult(
                SwitchRequestBus.SwitchResult(subId, success = false, detail = detail),
            )
        }
    }

    private fun clearPending() {
        pendingLabel = null
        pendingSubId = -1
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        SwitchRequestBus.accessibilityConnected = false
        scope.cancel()
        return super.onUnbind(intent)
    }

    private companion object {
        const val SETTLE_MS = 1_200L
        const val MAX_SCROLL_ATTEMPTS = 6
    }
}
