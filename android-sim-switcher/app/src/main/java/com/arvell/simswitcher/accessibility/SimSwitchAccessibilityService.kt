package com.arvell.simswitcher.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.arvell.simswitcher.service.SwitchRequestBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Executes data-SIM switches by driving the system Settings UI, since no public
 * API lets a normal app change the default data subscription.
 *
 * Flow for one request on the itel P55 (itelOS, Android 13):
 *  1. Open Settings, navigate into "SIM & Network Settings".
 *  2. Under the "Mobile Data" header, find the segmented button for the target
 *     SIM *slot* (1 or 2) — scoped to the band between "Mobile Data" and "SMS".
 *  3. Tap it (node click, falling back to a gesture tap for custom widgets),
 *     then report success/failure on the bus.
 *
 * The service re-runs the attempt on every relevant window change, so the
 * multi-screen navigation happens naturally as Settings transitions.
 */
class SimSwitchAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var pendingLabel: String? = null
    @Volatile private var pendingSubId: Int = -1
    @Volatile private var pendingSlotIndex: Int = -1
    @Volatile private var pendingDiagnostic: Boolean = false
    @Volatile private var scrollAttempts: Int = 0
    @Volatile private var startedAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        SwitchRequestBus.accessibilityConnected = true
        scope.launch {
            SwitchRequestBus.requests.collect { request -> handleRequest(request) }
        }
    }

    private suspend fun handleRequest(request: SwitchRequestBus.SwitchRequest) {
        pendingLabel = request.targetLabel
        pendingSubId = request.targetSubId
        pendingSlotIndex = request.targetSlotIndex
        pendingDiagnostic = request.diagnostic
        scrollAttempts = 0
        startedAt = System.currentTimeMillis()
        openSimSettings()
        // Fallback nudge in case no accessibility event fires after the screen settles.
        delay(SETTLE_MS)
        attemptSwitch()
        // Hard timeout so a request never hangs forever.
        delay(TIMEOUT_MS)
        if (pendingSlotIndex != -1 && System.currentTimeMillis() - startedAt >= TIMEOUT_MS) {
            reportFailure("Timed out locating the data-SIM control — switch manually")
        }
    }

    private fun openSimSettings() {
        // Settings home is the most reliable entry point across Transsion builds;
        // we then click "SIM & Network Settings" via the navigator. The operator
        // deep-link is only a fallback in case home cannot be opened.
        val candidates = listOf(
            Intent(Settings.ACTION_SETTINGS),
            Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
        )
        for (intent in candidates) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
                // try next
            }
        }
        reportFailure("Could not open Settings")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (pendingSlotIndex == -1) return
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> attemptSwitch()
        }
    }

    private fun attemptSwitch() {
        if (pendingSlotIndex == -1) return
        val slot1Based = pendingSlotIndex + 1
        val label = pendingLabel.orEmpty()
        val root = rootInActiveWindow ?: return

        // Diagnostic mode: when we reach the SIM screen, dump it instead of tapping.
        if (pendingDiagnostic) {
            if (SettingsNavigator.hasMobileDataHeader(root)) {
                finishDiagnostic(SettingsNavigator.describeDataScreen(root))
            } else {
                navigateOrScroll(root)
            }
            return
        }

        // 1) On the SIM & Network screen: tap the data-slot button.
        val slotButton = SettingsNavigator.findDataSlotButton(root, slot1Based, label)
        if (slotButton != null) {
            val tapped = tapNode(slotButton)
            finishSuccess(tapped)
            return
        }

        // 2) Elsewhere (e.g. Settings home), then 3) scroll.
        navigateOrScroll(root)
    }

    /** Drill into the SIM screen, or scroll the current screen to reveal it. */
    private fun navigateOrScroll(root: AccessibilityNodeInfo) {
        val entry = SettingsNavigator.findNavigationEntry(root)
        if (entry != null) {
            SettingsNavigator.click(entry)
            return
        }
        if (scrollAttempts < MAX_SCROLL_ATTEMPTS) {
            val scrollable = SettingsNavigator.findScrollable(root)
            if (scrollable != null && SettingsNavigator.scrollForward(scrollable)) {
                scrollAttempts++
            }
        }
    }

    private fun finishDiagnostic(dump: String) {
        val subId = pendingSubId
        clearPending()
        SwitchRequestBus.diagnostic.value = dump
        scope.launch {
            SwitchRequestBus.reportResult(
                SwitchRequestBus.SwitchResult(subId, success = true, detail = "Diagnostic captured"),
            )
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** Click the node; if it isn't natively clickable, dispatch a gesture tap. */
    private fun tapNode(node: AccessibilityNodeInfo): Boolean {
        if (SettingsNavigator.click(node)) return true
        val (x, y) = SettingsNavigator.centerOf(node)
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun finishSuccess(tapped: Boolean) {
        val subId = pendingSubId
        clearPending()
        scope.launch {
            SwitchRequestBus.reportResult(
                SwitchRequestBus.SwitchResult(
                    subId,
                    success = tapped,
                    detail = if (tapped) "Selected target SIM slot" else "Found the control but the tap was rejected",
                ),
            )
            // Let the toggle register before leaving the screen.
            delay(HOME_DELAY_MS)
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
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
        pendingSlotIndex = -1
        pendingDiagnostic = false
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        SwitchRequestBus.accessibilityConnected = false
        scope.cancel()
        return super.onUnbind(intent)
    }

    private companion object {
        const val SETTLE_MS = 1_200L
        const val TIMEOUT_MS = 8_000L
        const val MAX_SCROLL_ATTEMPTS = 6
        const val TAP_DURATION_MS = 60L
        const val HOME_DELAY_MS = 700L
    }
}
