package com.arvell.simswitcher.model

/** A single SIM / mobile subscription as reported by the platform. */
data class SimInfo(
    val subscriptionId: Int,
    val slotIndex: Int,
    val displayName: String,
    val carrierName: String,
) {
    /** Human label used both in the UI and for matching nodes in Settings. */
    val label: String
        get() = displayName.ifBlank { carrierName }.ifBlank { "SIM ${slotIndex + 1}" }
}

/**
 * Snapshot of the data connection quality on the currently active data SIM.
 *
 * @param signalLevel 0..4 as defined by [android.telephony.SignalStrength.getLevel],
 *   or [SIGNAL_UNKNOWN] when it could not be read.
 * @param hasValidatedInternet the active network reports
 *   [android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED].
 * @param reachable an active reachability probe succeeded recently.
 */
data class NetworkQuality(
    val activeDataSubId: Int,
    val signalLevel: Int = SIGNAL_UNKNOWN,
    val hasValidatedInternet: Boolean = false,
    val reachable: Boolean = false,
) {
    companion object {
        const val SIGNAL_UNKNOWN = -1
    }
}

/** What the decision engine wants the rest of the app to do. */
sealed interface SwitchDecision {
    /** Conditions are fine, or we are inside a cooldown / debounce window. */
    data object Hold : SwitchDecision

    /** The active SIM is unhealthy; switch the data SIM to [targetSubId]. */
    data class Switch(
        val fromSubId: Int,
        val targetSubId: Int,
        val reason: String,
    ) : SwitchDecision
}

/** User-tunable thresholds that drive [com.arvell.simswitcher.core.SwitchDecisionEngine]. */
data class SwitchConfig(
    val enabled: Boolean = false,
    /** Switch when the signal level is at or below this (0..4). */
    val minSignalLevel: Int = 1,
    /** A bad condition must persist this long before we switch (anti-flap). */
    val failureWindowMs: Long = 12_000L,
    /** After a switch, ignore further triggers for this long. */
    val cooldownMs: Long = 60_000L,
    /** Treat "no validated internet" as a failure even if signal looks OK. */
    val requireValidatedInternet: Boolean = true,
    /** Auto-switch back to the preferred SIM once it recovers. */
    val preferPrimary: Boolean = true,
    /** subscriptionId the user considers their primary data SIM, or -1 for none. */
    val primarySubId: Int = -1,
)
