package com.lapisserve.app

import com.lapisserve.app.engine.DeviceHealth
import com.lapisserve.app.engine.Insights
import com.lapisserve.app.engine.Severity
import com.lapisserve.app.model.RunState
import com.lapisserve.app.model.ServerConfig
import com.lapisserve.app.model.ServerRuntime
import com.lapisserve.app.model.ServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {
    private val cfg = ServerConfig(id = "a", name = "a", type = ServerType.PAPER, version = "1.21.8", ramMb = 2048, viewDistance = 8)
    private val healthy = DeviceHealth(batteryPct = 80, charging = true, thermal = "Cool", freeRamMb = 3000, totalRamMb = 8000)
    private fun rt(block: ServerRuntime.() -> ServerRuntime = { this }) =
        ServerRuntime(state = RunState.RUNNING, rssMb = 900, tps = 20f, cpuCoresUsed = 0.4f, cpuCoresAllowed = 8).block()

    @Test fun notRunning_hasNoInsights() {
        assertTrue(Insights.forServer(cfg, ServerRuntime(state = RunState.STOPPED), healthy, 8).isEmpty())
    }

    @Test fun healthyServer_reportsGood() {
        val r = Insights.forServer(cfg, rt(), healthy, 8)
        assertEquals(1, r.size); assertEquals(Severity.GOOD, r[0].severity)
    }

    @Test fun lowTps_isCriticalAndSuggestsLowerViewDistance() {
        val r = Insights.forServer(cfg, rt { copy(tps = 11f) }, healthy, 8)
        assertEquals(Severity.CRITICAL, r[0].severity)
        assertTrue(r[0].text.contains("from 8 to 6"))
    }

    @Test fun slightlyLowTps_isWarning() {
        assertEquals(Severity.WARN, Insights.forServer(cfg, rt { copy(tps = 17.5f) }, healthy, 8)[0].severity)
    }

    @Test fun fullMemory_suggestsRaisingRamOnlyWhenPhoneHasSpare() {
        val spare = Insights.forServer(cfg, rt { copy(rssMb = 2000) }, healthy, 8).first { it.title.contains("Memory") }
        assertTrue(spare.text.contains("raise its RAM"))
        val tight = Insights.forServer(cfg, rt { copy(rssMb = 2000) }, healthy.copy(freeRamMb = 700), 8).first { it.title.contains("Memory") }
        assertTrue(tight.text.contains("view distance"))
    }

    @Test fun phoneLowOnMemory_outranksEverything() {
        val r = Insights.forServer(cfg, rt { copy(tps = 17f) }, healthy.copy(freeRamMb = 200), 8)
        assertEquals(Severity.CRITICAL, r[0].severity); assertTrue(r[0].title.contains("low on memory"))
    }

    @Test fun hotPhoneAndLowBattery() {
        val titles = Insights.forServer(cfg, rt(), healthy.copy(thermal = "Hot", batteryPct = 12, charging = false), 8).map { it.title }
        assertTrue(titles.any { it.contains("hot") }); assertTrue(titles.any { it.contains("Battery") })
    }

    @Test fun pinnedCpuMaxedOut_mentionsPinning() {
        val r = Insights.forServer(cfg.copy(cpuMask = 0b11), rt { copy(cpuCoresUsed = 1.9f, cpuCoresAllowed = 2) }, healthy, 2)
        assertTrue(r.first { it.title.contains("processor") }.text.contains("pinned"))
    }
}
