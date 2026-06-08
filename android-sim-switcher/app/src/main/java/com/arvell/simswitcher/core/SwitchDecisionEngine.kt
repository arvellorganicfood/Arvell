package com.arvell.simswitcher.core

import com.arvell.simswitcher.model.NetworkQuality
import com.arvell.simswitcher.model.SwitchConfig
import com.arvell.simswitcher.model.SwitchDecision

/**
 * Pure decision logic, deliberately free of Android dependencies so it can be
 * unit-tested. The hosting service feeds it quality snapshots plus the set of
 * available SIMs and a monotonic clock; the engine decides whether to switch.
 *
 * Anti-flapping is handled with two timers:
 *  - a *failure window*: a bad condition must persist before we act, so a brief
 *    dip while walking past a wall does not trigger a switch.
 *  - a *cooldown*: after switching we hold, so we never ping-pong between SIMs.
 */
class SwitchDecisionEngine(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var firstBadAt: Long = NONE
    private var lastSwitchAt: Long = NONE

    /** Reset internal timers, e.g. when monitoring (re)starts. */
    fun reset() {
        firstBadAt = NONE
        lastSwitchAt = NONE
    }

    /**
     * @param quality latest snapshot for the active data SIM.
     * @param availableSubIds subscriptionIds of all SIMs that can carry data.
     * @param config user thresholds.
     */
    fun evaluate(
        quality: NetworkQuality,
        availableSubIds: List<Int>,
        config: SwitchConfig,
    ): SwitchDecision {
        if (!config.enabled) return SwitchDecision.Hold

        val now = clock()
        val healthy = isHealthy(quality, config)

        if (healthy) {
            firstBadAt = NONE
            return maybeReturnToPrimary(quality, availableSubIds, config, now)
        }

        // Need a different, available SIM to switch to.
        val target = pickTarget(quality.activeDataSubId, availableSubIds, config) ?: return SwitchDecision.Hold

        if (firstBadAt == NONE) firstBadAt = now
        if (now - firstBadAt < config.failureWindowMs) return SwitchDecision.Hold
        if (lastSwitchAt != NONE && now - lastSwitchAt < config.cooldownMs) return SwitchDecision.Hold

        lastSwitchAt = now
        firstBadAt = NONE
        return SwitchDecision.Switch(
            fromSubId = quality.activeDataSubId,
            targetSubId = target,
            reason = describeFailure(quality, config),
        )
    }

    private fun isHealthy(q: NetworkQuality, config: SwitchConfig): Boolean {
        val signalOk = q.signalLevel == NetworkQuality.SIGNAL_UNKNOWN || q.signalLevel > config.minSignalLevel
        val internetOk = if (config.requireValidatedInternet) q.hasValidatedInternet || q.reachable else true
        return signalOk && internetOk
    }

    private fun pickTarget(active: Int, available: List<Int>, config: SwitchConfig): Int? {
        val others = available.filter { it != active }
        if (others.isEmpty()) return null
        // Prefer the user's primary SIM when it is the alternative, else the first other.
        return others.firstOrNull { it == config.primarySubId } ?: others.first()
    }

    /** When the primary SIM has recovered and we are currently on another SIM, go back. */
    private fun maybeReturnToPrimary(
        q: NetworkQuality,
        available: List<Int>,
        config: SwitchConfig,
        now: Long,
    ): SwitchDecision {
        if (!config.preferPrimary) return SwitchDecision.Hold
        if (config.primarySubId == -1) return SwitchDecision.Hold
        if (q.activeDataSubId == config.primarySubId) return SwitchDecision.Hold
        if (config.primarySubId !in available) return SwitchDecision.Hold
        if (lastSwitchAt != NONE && now - lastSwitchAt < config.cooldownMs) return SwitchDecision.Hold

        // The active (non-primary) SIM is healthy; we can only assume the primary
        // recovered. The accessibility switch + next snapshot will confirm.
        lastSwitchAt = now
        return SwitchDecision.Switch(
            fromSubId = q.activeDataSubId,
            targetSubId = config.primarySubId,
            reason = "Primary SIM available again",
        )
    }

    private fun describeFailure(q: NetworkQuality, config: SwitchConfig): String = buildString {
        if (config.requireValidatedInternet && !q.hasValidatedInternet && !q.reachable) {
            append("No internet on active SIM")
        }
        if (q.signalLevel != NetworkQuality.SIGNAL_UNKNOWN && q.signalLevel <= config.minSignalLevel) {
            if (isNotEmpty()) append("; ")
            append("Weak signal (level ${q.signalLevel})")
        }
        if (isEmpty()) append("Active SIM unhealthy")
    }

    private companion object {
        const val NONE = -1L
    }
}
