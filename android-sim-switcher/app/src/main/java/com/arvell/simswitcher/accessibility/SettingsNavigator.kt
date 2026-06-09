package com.arvell.simswitcher.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Heuristics for locating and clicking the right node inside the system Settings
 * to switch the mobile-data SIM.
 *
 * There is no public, stable API to switch the data SIM from a normal app, so we
 * drive the Settings UI. Layout is OEM/version-specific, hence the heuristics.
 *
 * TUNED FOR: itel P55 (Transsion itelOS, Android 13). On this device the data
 * SIM is chosen on the "SIM & Network Settings" screen via a *segmented control*
 * under the "Mobile Data" header whose buttons are labelled by SLOT NUMBER
 * ("1", "2") — not by carrier name. Crucially, identical "1"/"2" buttons also
 * appear under SMS and Calls, so we scope the search to the vertical band
 * between the "Mobile Data" header and the next header ("SMS"/"Calls").
 */
object SettingsNavigator {

    /** Entries on the way to the data-SIM screen (Settings home → SIM screen). */
    private val NAVIGATION_KEYWORDS = listOf(
        "SIM & Network Settings", "SIM cards & mobile networks", "SIM card & mobile data",
        "SIM management", "SIM cards", "Mobile network",
        // Arabic
        "إعدادات SIM والشبكة", "بطاقات SIM والشبكات المحمولة", "إدارة بطاقة SIM",
        "بطاقات SIM", "الشبكة المحمولة",
    )

    /** The header above the data-SIM segmented control. */
    private val MOBILE_DATA_HEADERS = listOf(
        "Mobile Data", "Mobile data", "Cellular data", "Data service",
        // Arabic
        "بيانات الجوال", "بيانات الهاتف المحمول", "البيانات الخلوية", "بيانات الجوّال",
    )

    /** Headers of the sections that follow Mobile Data; used as a lower bound. */
    private val BOUNDARY_HEADERS = listOf(
        "SMS", "Calls", "Call", "Data & Security",
        // Arabic
        "الرسائل", "رسائل", "المكالمات", "مكالمات", "البيانات والأمان",
    )

    fun click(node: AccessibilityNodeInfo?): Boolean =
        node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    /** An entry that drills toward the data-SIM screen (e.g. from Settings home). */
    fun findNavigationEntry(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        for (keyword in NAVIGATION_KEYWORDS) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword) ?: continue
            for (node in nodes) firstClickable(node)?.let { return it }
        }
        return null
    }

    /**
     * Find the clickable button that selects [slot1Based] (1 or 2) for mobile
     * data, scoped to the band beneath the "Mobile Data" header.
     *
     * Match order: (1) the slot-number text within the band, (2) the carrier
     * [label] text, (3) left-to-right position among clickables in the band.
     */
    fun findDataSlotButton(
        root: AccessibilityNodeInfo?,
        slot1Based: Int,
        label: String,
    ): AccessibilityNodeInfo? {
        root ?: return null
        val header = findFirstByTexts(root, MOBILE_DATA_HEADERS) ?: return null
        val headerRect = Rect().also { header.getBoundsInScreen(it) }

        // Lower boundary: top of the next section header below Mobile Data.
        val lower = BOUNDARY_HEADERS
            .mapNotNull { findFirstByTexts(root, listOf(it)) }
            .map { Rect().also { r -> it.getBoundsInScreen(r) } }
            .filter { it.top >= headerRect.bottom }
            .minByOrNull { it.top }
            ?.top ?: Int.MAX_VALUE

        val all = collectAll(root)
        fun inBand(node: AccessibilityNodeInfo): Boolean {
            val r = Rect().also { node.getBoundsInScreen(it) }
            return r.centerY() > headerRect.bottom && r.centerY() < lower
        }

        // (1) Slot-number text inside the band → its clickable ancestor.
        all.firstOrNull { inBand(it) && textOrDesc(it).trim() == slot1Based.toString() }
            ?.let { return firstClickable(it) ?: it }

        // (1b) Some ROMs expose "SIM 1"/"SIM 2" as the content description.
        all.firstOrNull { inBand(it) && textOrDesc(it).contains("SIM $slot1Based", ignoreCase = true) }
            ?.let { return firstClickable(it) ?: it }

        // (2) Carrier label (e.g. "MTN"/"Syriatel") if the button exposes it.
        if (label.isNotBlank()) {
            all.firstOrNull { inBand(it) && textOrDesc(it).contains(label, ignoreCase = true) }
                ?.let { return firstClickable(it) ?: it }
        }

        // (3) Positional: clickable nodes in the band, ordered left→right.
        val clickablesInBand = all
            .filter { inBand(it) && it.isClickable }
            .sortedBy { Rect().also { r -> it.getBoundsInScreen(r) }.left }
        return clickablesInBand.getOrNull(slot1Based - 1)
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

    /** Centre point of a node in screen coordinates, for a gesture-tap fallback. */
    fun centerOf(node: AccessibilityNodeInfo): Pair<Int, Int> {
        val r = Rect().also { node.getBoundsInScreen(it) }
        return r.centerX() to r.centerY()
    }

    fun findScrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            findScrollable(root.getChild(i))?.let { return it }
        }
        return null
    }

    fun scrollForward(node: AccessibilityNodeInfo?): Boolean =
        node?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true

    /** True if the current screen looks like the data-SIM screen. */
    fun hasMobileDataHeader(root: AccessibilityNodeInfo?): Boolean =
        root != null && findFirstByTexts(root, MOBILE_DATA_HEADERS) != null

    /**
     * Human-readable dump of the data-SIM control area, for on-device diagnosis
     * when automatic matching fails. Focuses on the band beneath the "Mobile
     * Data" header (the relevant region); if the header isn't found, lists all
     * labelled/clickable nodes so the real wording can be discovered.
     */
    fun describeDataScreen(root: AccessibilityNodeInfo?): String {
        root ?: return "No active window."
        val sb = StringBuilder()
        val header = findFirstByTexts(root, MOBILE_DATA_HEADERS)
        if (header == null) {
            sb.append("'Mobile Data' header NOT found. All labelled/clickable nodes:\n\n")
            for (node in collectAll(root)) {
                val line = describeNode(node)
                if (line != null) sb.append(line).append('\n')
            }
            return sb.toString().take(MAX_DUMP)
        }
        val hr = Rect().also { header.getBoundsInScreen(it) }
        val lower = BOUNDARY_HEADERS
            .mapNotNull { findFirstByTexts(root, listOf(it)) }
            .map { Rect().also { r -> it.getBoundsInScreen(r) } }
            .filter { it.top >= hr.bottom }
            .minByOrNull { it.top }
        sb.append("Header 'Mobile Data' bounds=$hr\n")
        sb.append("Lower boundary=${lower ?: "none"}\n\n")
        sb.append("Nodes in the Mobile Data band:\n")
        val lowerTop = lower?.top ?: Int.MAX_VALUE
        for (node in collectAll(root)) {
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (r.centerY() > hr.bottom && r.centerY() < lowerTop) {
                describeNode(node, force = true)?.let { sb.append(it).append('\n') }
            }
        }
        return sb.toString().take(MAX_DUMP)
    }

    /** One compact line per node; null when there's nothing worth showing. */
    private fun describeNode(node: AccessibilityNodeInfo, force: Boolean = false): String? {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val id = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        if (!force && text.isBlank() && desc.isBlank() && !node.isClickable) return null
        val r = Rect().also { node.getBoundsInScreen(it) }
        val cls = node.className?.toString()?.substringAfterLast('.').orEmpty()
        return buildString {
            append(if (node.isClickable) "[CLICK] " else "[     ] ")
            append(cls)
            if (text.isNotBlank()) append(" text='").append(text).append('\'')
            if (desc.isNotBlank()) append(" desc='").append(desc).append('\'')
            if (id.isNotBlank()) append(" id=").append(id)
            append(" @").append(r.toShortString())
        }
    }

    private fun findFirstByTexts(
        root: AccessibilityNodeInfo,
        keywords: List<String>,
    ): AccessibilityNodeInfo? {
        for (keyword in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword) ?: continue
            // Prefer an exact (case-insensitive) text match for headers.
            nodes.firstOrNull { textOrDesc(it).equals(keyword, ignoreCase = true) }?.let { return it }
            nodes.firstOrNull()?.let { return it }
        }
        return null
    }

    private fun collectAll(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun dfs(node: AccessibilityNodeInfo?) {
            node ?: return
            out.add(node)
            for (i in 0 until node.childCount) dfs(node.getChild(i))
        }
        dfs(root)
        return out
    }

    private fun textOrDesc(node: AccessibilityNodeInfo): String =
        node.text?.toString() ?: node.contentDescription?.toString() ?: ""

    private const val MAX_ASCEND = 6
    private const val MAX_DUMP = 6000
}
