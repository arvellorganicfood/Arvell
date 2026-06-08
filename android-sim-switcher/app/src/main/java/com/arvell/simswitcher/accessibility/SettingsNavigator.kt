package com.arvell.simswitcher.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Heuristics for finding and clicking the right node inside the system Settings
 * "SIMs / Mobile data" screen.
 *
 * IMPORTANT: the Settings UI layout is **OEM- and version-specific** (AOSP,
 * One UI, MIUI, ColorOS … each differ). There is no public, stable API to
 * switch the data SIM from a normal app, which is exactly why we drive the UI.
 * The matchers below are intentionally label-based and fuzzy so they survive
 * minor layout changes, but real devices will need tuning — keep the matcher
 * lists in one place here.
 */
object SettingsNavigator {

    /** Labels that identify the "use this SIM for mobile data" control. */
    private val DATA_SIM_KEYWORDS = listOf(
        "mobile data", "cellular data", "data sim", "preferred sim for data",
        "mobile data preferred", "data preference", "use sim for data",
    )

    /**
     * Find a clickable node whose visible text contains [label] (the target SIM
     * name), preferring nodes that sit near a data-related control.
     */
    fun findSimOptionByLabel(root: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        root ?: return null
        val matches = root.findAccessibilityNodeInfosByText(label) ?: return null
        // Prefer an actually clickable ancestor.
        for (node in matches) {
            val clickable = firstClickable(node)
            if (clickable != null) return clickable
        }
        return matches.firstOrNull()
    }

    /** Find the row/entry that opens data-SIM selection, by keyword. */
    fun findDataSettingEntry(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        for (keyword in DATA_SIM_KEYWORDS) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword) ?: continue
            for (node in nodes) {
                firstClickable(node)?.let { return it }
            }
        }
        return null
    }

    /** Walk up from [node] to the nearest clickable ancestor (inclusive). */
    fun firstClickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (current != null && depth < MAX_ASCEND) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    fun click(node: AccessibilityNodeInfo?): Boolean =
        node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    private const val MAX_ASCEND = 6
}
