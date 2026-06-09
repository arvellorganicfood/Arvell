package com.arvell.simswitcher.core

import com.arvell.simswitcher.model.NetworkQuality
import com.arvell.simswitcher.model.SwitchConfig
import com.arvell.simswitcher.model.SwitchDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchDecisionEngineTest {

    private var now = 0L
    private val engine = SwitchDecisionEngine(clock = { now })

    private val baseConfig = SwitchConfig(
        enabled = true,
        minSignalLevel = 1,
        failureWindowMs = 10_000,
        cooldownMs = 60_000,
        requireValidatedInternet = true,
        preferPrimary = false,
        primarySubId = -1,
    )

    private val sims = listOf(1, 2)

    @Test
    fun `disabled config never switches`() {
        val q = NetworkQuality(activeDataSubId = 1, signalLevel = 0, hasValidatedInternet = false)
        val d = engine.evaluate(q, sims, baseConfig.copy(enabled = false))
        assertEquals(SwitchDecision.Hold, d)
    }

    @Test
    fun `healthy SIM holds`() {
        val q = NetworkQuality(activeDataSubId = 1, signalLevel = 4, hasValidatedInternet = true)
        assertEquals(SwitchDecision.Hold, engine.evaluate(q, sims, baseConfig))
    }

    @Test
    fun `brief failure within window does not switch`() {
        val bad = NetworkQuality(activeDataSubId = 1, signalLevel = 0, hasValidatedInternet = false)
        now = 0
        assertEquals(SwitchDecision.Hold, engine.evaluate(bad, sims, baseConfig))
        now = 5_000 // still within 10s window
        assertEquals(SwitchDecision.Hold, engine.evaluate(bad, sims, baseConfig))
    }

    @Test
    fun `sustained failure switches to the other SIM`() {
        val bad = NetworkQuality(activeDataSubId = 1, signalLevel = 0, hasValidatedInternet = false)
        now = 0
        engine.evaluate(bad, sims, baseConfig)
        now = 11_000
        val d = engine.evaluate(bad, sims, baseConfig)
        assertTrue(d is SwitchDecision.Switch)
        d as SwitchDecision.Switch
        assertEquals(1, d.fromSubId)
        assertEquals(2, d.targetSubId)
    }

    @Test
    fun `no switch when only one SIM available`() {
        val bad = NetworkQuality(activeDataSubId = 1, signalLevel = 0, hasValidatedInternet = false)
        now = 0
        engine.evaluate(bad, listOf(1), baseConfig)
        now = 20_000
        assertEquals(SwitchDecision.Hold, engine.evaluate(bad, listOf(1), baseConfig))
    }

    @Test
    fun `cooldown prevents immediate re-switch`() {
        val bad = NetworkQuality(activeDataSubId = 1, signalLevel = 0, hasValidatedInternet = false)
        now = 0
        engine.evaluate(bad, sims, baseConfig)
        now = 11_000
        assertTrue(engine.evaluate(bad, sims, baseConfig) is SwitchDecision.Switch)
        // Now active SIM is conceptually 2, still bad, but within cooldown.
        val badOn2 = bad.copy(activeDataSubId = 2)
        now = 30_000
        assertEquals(SwitchDecision.Hold, engine.evaluate(badOn2, sims, baseConfig))
    }

    @Test
    fun `weak signal alone triggers switch when sustained`() {
        val weak = NetworkQuality(activeDataSubId = 1, signalLevel = 1, hasValidatedInternet = true)
        now = 0
        engine.evaluate(weak, sims, baseConfig)
        now = 11_000
        assertTrue(engine.evaluate(weak, sims, baseConfig) is SwitchDecision.Switch)
    }

    @Test
    fun `returns to primary when on secondary and healthy`() {
        val cfg = baseConfig.copy(preferPrimary = true, primarySubId = 1)
        val healthyOn2 = NetworkQuality(activeDataSubId = 2, signalLevel = 4, hasValidatedInternet = true)
        now = 0
        val d = engine.evaluate(healthyOn2, sims, cfg)
        assertTrue(d is SwitchDecision.Switch)
        assertEquals(1, (d as SwitchDecision.Switch).targetSubId)
    }
}
