package com.lapisserve.app

import com.lapisserve.app.engine.CoreTopology
import com.lapisserve.app.engine.LaunchBuilder
import com.lapisserve.app.engine.YamlPatch
import com.lapisserve.app.model.CoreStrategy
import com.lapisserve.app.model.ServerConfig
import com.lapisserve.app.model.ServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CoreTopologyTest {
    @get:Rule val tmp = TemporaryFolder()

    // Snapdragon 888-style 4 + 3 + 1 layout (kHz).
    private val sd888 = listOf<Long?>(1_804_800, 1_804_800, 1_804_800, 1_804_800, 2_419_200, 2_419_200, 2_419_200, 2_841_600)
    private val flat = List<Long?>(8) { 2_000_000 }
    private fun cfg(type: ServerType = ServerType.PAPER, block: ServerConfig.() -> ServerConfig = { this }) =
        ServerConfig(id = "t", name = "t", type = type, version = "1.21.8").block()

    @Test fun midCoresCountAsPerformance() {
        assertEquals(0b1111_0000, CoreTopology.performanceMask(sd888))
        assertEquals(7, CoreTopology.bestCore(sd888))
    }

    @Test fun unknownLayout_noPinning() {
        assertEquals(0, CoreTopology.performanceMask(List(8) { null }))
        val p = CoreTopology.plan(cfg(), List(8) { null })
        assertEquals(CoreStrategy.ALL, p.strategy); assertEquals(0, p.mask); assertEquals(8, p.activeCores)
    }

    @Test fun uniformCores_stillGiveTickThreadItsOwnCore() {
        assertEquals(0, CoreTopology.performanceMask(flat))
        val p = CoreTopology.plan(cfg(), flat)
        assertEquals(CoreStrategy.FOCUS, p.strategy); assertEquals(0, p.mask)
        assertEquals(7, p.tickCore); assertEquals(0b0111_1111, p.restMask); assertEquals(7, p.workerCores)
    }

    @Test fun allBigChip_reservesOnePrimeCore() {
        // 8 Elite Gen 5 style: 6 performance + 2 prime, no efficiency cores.
        val elite = listOf<Long?>(3_620_000, 3_620_000, 3_620_000, 3_620_000, 3_620_000, 3_620_000, 4_610_000, 4_610_000)
        val p = CoreTopology.plan(cfg(), elite)
        assertEquals(7, p.tickCore); assertEquals(0b0111_1111, p.restMask); assertEquals(8, p.activeCores)
    }

    @Test fun onePrimeTwoBigFiveSmall_letsSmallCoresHelp() {
        val l = listOf<Long?>(2_000_000, 2_000_000, 2_000_000, 2_000_000, 2_000_000, 3_000_000, 3_000_000, 3_300_000)
        val p = CoreTopology.plan(cfg(), l)
        assertEquals(7, p.tickCore); assertEquals(0b0111_1111, p.restMask)
    }

    @Test fun autoOnJava_reservesFastestCoreForTickThread() {
        val p = CoreTopology.plan(cfg(), sd888)
        assertEquals(CoreStrategy.FOCUS, p.strategy)
        assertEquals(0b1111_0000, p.mask); assertEquals(7, p.tickCore); assertEquals(0b0111_0000, p.restMask)
        assertEquals(4, p.activeCores); assertEquals(3, p.workerCores)
    }

    @Test fun autoOnPumpkin_onlyAvoidsEfficiencyCores() {
        val p = CoreTopology.plan(cfg(ServerType.PUMPKIN), sd888)
        assertEquals(CoreStrategy.PERFORMANCE, p.strategy); assertFalse(p.dedicatedTick); assertEquals(0b1111_0000, p.mask)
    }

    @Test fun twoBigSixSmall_tickOnBigRestEverywhereElse() {
        val twoBig = listOf<Long?>(1_800_000, 1_800_000, 1_800_000, 1_800_000, 1_800_000, 1_800_000, 2_800_000, 2_800_000)
        val p = CoreTopology.plan(cfg(), twoBig)
        assertTrue(p.dedicatedTick); assertEquals(7, p.tickCore); assertEquals(0b0111_1111, p.restMask); assertEquals(0, p.mask)
        // Pumpkin would be squeezed onto 2 cores: leave it unpinned instead.
        assertEquals(CoreStrategy.ALL, CoreTopology.plan(cfg(ServerType.PUMPKIN), twoBig).strategy)
    }

    @Test fun legacyManualMask_isRespected() {
        val p = CoreTopology.plan(cfg { copy(cpuMask = 0b0000_0011) }, sd888)
        assertEquals(CoreStrategy.MANUAL, p.strategy); assertEquals(0b11, p.mask); assertEquals(2, p.activeCores)
    }

    @Test fun jvmUsesWorkerCoresForGcThreads() {
        val c = cfg { copy(ramMb = 4096) }
        val args = LaunchBuilder.jvmArgs(c, 8, tmp.root, plan = CoreTopology.plan(c, sd888))
        assertTrue(args.contains("-XX:ActiveProcessorCount=4"))
        assertTrue(args.contains("-XX:ParallelGCThreads=3"))
        assertTrue(args.contains("-XX:CICompilerCount=2"))
        val prefix = LaunchBuilder.schedulingPrefix(c, CoreTopology.plan(c, sd888))
        if (prefix.isNotEmpty()) assertEquals("f0", prefix.last())
    }

    @Test fun ansiCodesAreStrippedFromConsoleLines() {
        val raw = "\u001B[2m12:25:12\u001B[0m \u001B[32m INFO\u001B[0m Server is now running."
        assertEquals("12:25:12  INFO Server is now running.", com.lapisserve.app.engine.ConsoleParsers.stripAnsi(raw))
        assertEquals("plain", com.lapisserve.app.engine.ConsoleParsers.stripAnsi("plain"))
    }

    @Test fun playerSizeRam_isCappedByComfortAndSnapped() {
        val ps = com.lapisserve.app.model.PlayerSize.entries
        assertEquals(2048, ps[0].recommendedRamMb(ServerType.PAPER, 4000))
        assertEquals(3968, ps[2].recommendedRamMb(ServerType.PAPER, 4000))      // 4096 capped to comfort, snapped to 128
        assertEquals(1024, ps[2].recommendedRamMb(ServerType.PAPER, 600))       // never below a Java server's floor
        assertEquals(512, ps[0].recommendedRamMb(ServerType.PUMPKIN, 4000))
    }

    @Test fun describe() {
        assertEquals("cpu4-6", CoreTopology.describe(0b0111_0000, 8))
        assertEquals("cpu0,7", CoreTopology.describe(0b1000_0001, 8))
    }

    @Test fun yamlPatch_editsNestedScalarsOnly() {
        val f = File(tmp.root, "spigot.yml")
        f.writeText("""
            # comment
            settings:
              timeout-time: 60
            world-settings:
              default:
                mob-spawn-range: 8 # inline
                merge-radius:
                  item: 0.5
                  exp: -1.0
                entity-activation-range:
                  animals: 32
                list-thing:
                - a
              other:
                mob-spawn-range: 8
        """.trimIndent())
        val applied = YamlPatch.apply(f, mapOf(
            "world-settings.default.mob-spawn-range" to "6",
            "world-settings.default.merge-radius.item" to "3.0",
            "world-settings.default.entity-activation-range.animals" to "16",
            "world-settings.default.missing" to "1",
        ))
        assertEquals(3, applied.size)
        val t = f.readText()
        assertTrue(t.contains("    mob-spawn-range: 6\n"))
        assertTrue(t.contains("  other:\n    mob-spawn-range: 8"))   // sibling section untouched
        assertTrue(t.contains("      item: 3.0")); assertTrue(t.contains("      exp: -1.0"))
        assertTrue(t.contains("      animals: 16")); assertTrue(t.contains("# comment")); assertTrue(t.contains("timeout-time: 60"))
    }
}
