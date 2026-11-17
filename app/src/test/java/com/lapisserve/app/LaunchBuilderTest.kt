package com.lapisserve.app

import com.lapisserve.app.engine.LaunchBuilder
import com.lapisserve.app.model.GcPreset
import com.lapisserve.app.model.ServerConfig
import com.lapisserve.app.model.ServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LaunchBuilderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cfg(type: ServerType = ServerType.PAPER, block: ServerConfig.() -> ServerConfig = { this }) =
        ServerConfig(id = "t", name = "t", type = type, version = "1.21.8").block()

    // A trimmed copy of the layout Pumpkin generates: same key names in several sections.
    private val pumpkinToml = """
        seed = "123"
        default_difficulty = "Normal"
        hardcore = false
        default_gamemode = "Survival"

        [networking.java]
        address = "0.0.0.0:25565"
        online_mode = true
        max_players = 1000
        view_distance = 16
        simulation_distance = 10
        motd = "A blazingly fast Pumpkin server!"

        [networking.java.compression]
        threshold = 256

        [networking.bedrock]
        address = "0.0.0.0:19132"
        online_mode = true
        max_players = 1000
        view_distance = 16
        simulation_distance = 10
        motd = "A blazingly fast Pumpkin server!"
    """.trimIndent()

    @Test fun pumpkinPatch_returnsNullWhenConfigMissing() {
        assertNull(LaunchBuilder.patchPumpkinConfig(cfg(ServerType.PUMPKIN), tmp.root))
    }

    @Test fun pumpkinPatch_appliesSettingsInJavaAndBedrockSections() {
        File(tmp.root, "pumpkin.toml").writeText(pumpkinToml)
        val c = cfg(ServerType.PUMPKIN) { copy(viewDistance = 4, simulationDistance = 3, maxPlayers = 5, port = 25570, motd = "Hi \"there\"", gamemode = "creative") }
        val applied = LaunchBuilder.patchPumpkinConfig(c, tmp.root)
        assertNotNull(applied)
        val out = File(tmp.root, "pumpkin.toml").readText()

        // Java section: everything including the address.
        assertTrue(out.contains("address = \"0.0.0.0:25570\""))
        assertTrue(out.contains("view_distance = 4"))
        assertTrue(out.contains("max_players = 5"))
        assertTrue(out.contains("motd = \"Hi \\\"there\\\"\""))
        // Bedrock section keeps its own address but takes the same limits.
        assertTrue(out.contains("address = \"0.0.0.0:19132\""))
        assertEquals(2, Regex("view_distance = 4").findAll(out).count())
        assertEquals(2, Regex("simulation_distance = 3").findAll(out).count())
        assertFalse(out.contains("view_distance = 16"))
        // Top-level keys and untouched sections.
        assertTrue(out.contains("default_gamemode = \"Creative\""))
        assertTrue(out.contains("threshold = 256"))
        assertTrue(out.contains("seed = \"123\""))
    }

    @Test fun pumpkinPatch_overridesSeedOnlyWhenSet() {
        File(tmp.root, "pumpkin.toml").writeText(pumpkinToml)
        LaunchBuilder.patchPumpkinConfig(cfg(ServerType.PUMPKIN) { copy(levelSeed = "999") }, tmp.root)
        assertTrue(File(tmp.root, "pumpkin.toml").readText().contains("seed = \"999\""))
    }

    @Test fun jvmArgs_heapCoresAndHostsFile() {
        val hosts = File(tmp.root, "lapis-hosts")
        val args = LaunchBuilder.jvmArgs(cfg { copy(ramMb = 2048) }, totalCores = 8, tmpDir = tmp.root, hostsFile = hosts, javaMajor = 21)
        assertTrue(args.contains("-Xmx2048M"))
        assertTrue(args.contains("-XX:ActiveProcessorCount=8"))
        assertTrue(args.contains("-Djdk.net.hosts.file=${hosts.path}"))
    }

    @Test fun jvmArgs_skipsHostsFileOnJava8() {
        val args = LaunchBuilder.jvmArgs(cfg(), 4, tmp.root, File(tmp.root, "h"), javaMajor = 8)
        assertFalse(args.any { it.startsWith("-Djdk.net.hosts.file") })
    }

    @Test fun cpuPinning_limitsActiveProcessorCount() {
        val c = cfg { copy(cpuMask = 0b1111_0000) }   // four cores pinned
        assertEquals(4, LaunchBuilder.effectiveCores(c, 8))
        assertEquals(8, LaunchBuilder.effectiveCores(cfg(), 8))
    }

    @Test fun autoGc_dependsOnHeapAndCores() {
        assertEquals(GcPreset.SERIAL, LaunchBuilder.resolveGc(cfg { copy(ramMb = 1024) }, 8))
        assertEquals(GcPreset.SERIAL, LaunchBuilder.resolveGc(cfg { copy(ramMb = 4096) }, 2))
        assertEquals(GcPreset.G1_AIKAR, LaunchBuilder.resolveGc(cfg { copy(ramMb = 4096) }, 8))
        assertEquals(GcPreset.G1_DEFAULT, LaunchBuilder.resolveGc(cfg(ServerType.VANILLA) { copy(ramMb = 4096) }, 8))
        assertEquals(GcPreset.PARALLEL, LaunchBuilder.resolveGc(cfg { copy(gc = GcPreset.PARALLEL) }, 8))
    }

    @Test fun splitArgs_respectsQuotes() {
        assertEquals(listOf("-Da=b", "two words", "-X"), LaunchBuilder.splitArgs("-Da=b \"two words\" -X"))
    }

    @Test fun javaConfig_writesEulaAndManagedProperties() {
        LaunchBuilder.writeJavaConfig(cfg { copy(acceptEula = true, viewDistance = 7, port = 25570, onlineMode = true) }, tmp.root)
        assertTrue(File(tmp.root, "eula.txt").readText().contains("eula=true"))
        val props = File(tmp.root, "server.properties").readText()
        assertTrue(props.contains("view-distance=7"))
        assertTrue(props.contains("server-port=25570"))
        assertTrue(props.contains("online-mode=true"))
    }

    @Test fun javaConfig_preservesUnmanagedProperties() {
        File(tmp.root, "server.properties").writeText("custom-key=keep-me\nview-distance=99\n")
        LaunchBuilder.writeJavaConfig(cfg { copy(viewDistance = 6) }, tmp.root)
        val props = File(tmp.root, "server.properties").readText()
        assertTrue(props.contains("custom-key=keep-me"))
        assertTrue(props.contains("view-distance=6"))
        assertFalse(props.contains("view-distance=99"))
    }
}
