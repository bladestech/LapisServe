package com.lapisserve.app.engine

import com.lapisserve.app.model.GcPreset
import com.lapisserve.app.model.ServerConfig
import com.lapisserve.app.model.ServerType
import com.lapisserve.app.model.supportsPlugins
import java.io.File

/** Turns a [ServerConfig] into the files and command line a server needs. Pure functions, no I/O besides writing config files. */
object LaunchBuilder {

    fun effectiveCores(c: ServerConfig, total: Int): Int =
        if (c.cpuMask == 0) total else Integer.bitCount(c.cpuMask).coerceIn(1, total)

    fun resolveGc(c: ServerConfig, cores: Int): GcPreset = when {
        c.gc != GcPreset.AUTO -> c.gc
        c.ramMb <= 1024 || cores <= 2 -> GcPreset.SERIAL
        c.type.supportsPlugins && c.ramMb >= 4096 -> GcPreset.G1_AIKAR
        else -> GcPreset.G1_DEFAULT
    }

    fun jvmArgs(c: ServerConfig, totalCores: Int, tmpDir: File, hostsFile: File? = null, javaMajor: Int = 21): List<String> {
        val cores = effectiveCores(c, totalCores)
        val a = ArrayList<String>()
        a += "-Xmx${c.ramMb}M"
        a += if (c.preTouch) "-Xms${c.ramMb}M" else "-Xms${minOf(c.ramMb, 256)}M"
        if (c.preTouch) a += "-XX:+AlwaysPreTouch"
        a += "-XX:ActiveProcessorCount=$cores"
        a += "-Djava.io.tmpdir=${tmpDir.path}"
        a += "-Dfile.encoding=UTF-8"
        // musl has no resolver config on Android; give the JVM a pre-resolved hosts file (supported on Java 9+).
        if (hostsFile != null && javaMajor >= 9) a += "-Djdk.net.hosts.file=${hostsFile.path}"
        a += "-XX:-UsePerfData"          // /tmp/hsperfdata is pointless on Android
        a += "-XX:+DisableExplicitGC"
        a += "-Xshare:auto"
        when (resolveGc(c, cores)) {
            GcPreset.SERIAL -> a += "-XX:+UseSerialGC"
            GcPreset.PARALLEL -> { a += "-XX:+UseParallelGC"; a += "-XX:ParallelGCThreads=$cores" }
            GcPreset.G1_DEFAULT -> { a += "-XX:+UseG1GC"; a += "-XX:ParallelGCThreads=$cores" }
            GcPreset.G1_AIKAR -> {
                val big = c.ramMb >= 12 * 1024
                a += listOf(
                    "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
                    "-XX:+UnlockExperimentalVMOptions",
                    "-XX:G1NewSizePercent=${if (big) 40 else 30}", "-XX:G1MaxNewSizePercent=${if (big) 50 else 40}",
                    "-XX:G1HeapRegionSize=${if (big) 16 else 8}M", "-XX:G1ReservePercent=${if (big) 15 else 20}",
                    "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4",
                    "-XX:InitiatingHeapOccupancyPercent=${if (big) 20 else 15}",
                    "-XX:G1MixedGCLiveThresholdPercent=90", "-XX:G1RSetUpdatingPauseTimePercent=5",
                    "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1",
                    "-XX:ParallelGCThreads=$cores",
                )
            }
            GcPreset.AUTO -> {}
        }
        if (c.extraJvmArgs.isNotBlank()) a += splitArgs(c.extraJvmArgs)
        return a
    }

    /** Wrappers available in toybox on every Android since 8: pin to cores and lower scheduling priority. */
    fun schedulingPrefix(c: ServerConfig): List<String> {
        val out = ArrayList<String>()
        val nice = File("/system/bin/nice")
        if (c.cpuPriority.nice > 0 && nice.exists()) out += listOf(nice.path, "-n", c.cpuPriority.nice.toString())
        val taskset = File("/system/bin/taskset")
        if (c.cpuMask != 0 && taskset.exists()) out += listOf(taskset.path, Integer.toHexString(c.cpuMask))
        return out
    }

    fun splitArgs(s: String): List<String> =
        Regex("\"([^\"]*)\"|(\\S+)").findAll(s).map { it.groups[1]?.value ?: it.groups[2]!!.value }.toList()

    // ------------------------------------------------------------------ config files

    fun writeJavaConfig(c: ServerConfig, dir: File) {
        File(dir, "eula.txt").writeText("# Accepted in LapisServe\neula=${c.acceptEula}\n")
        val f = File(dir, "server.properties")
        val props = LinkedHashMap<String, String>()
        if (f.exists()) f.readLines().forEach { l ->
            if (l.isNotBlank() && !l.startsWith("#") && '=' in l) props[l.substringBefore('=')] = l.substringAfter('=')
        }
        props["server-port"] = c.port.toString()
        props["motd"] = c.motd
        props["max-players"] = c.maxPlayers.toString()
        props["difficulty"] = c.difficulty
        props["gamemode"] = c.gamemode
        props["view-distance"] = c.viewDistance.toString()
        props["simulation-distance"] = c.simulationDistance.toString()
        props["online-mode"] = c.onlineMode.toString()
        props["white-list"] = c.whitelist.toString()
        props["enforce-whitelist"] = c.whitelist.toString()
        props["pvp"] = c.pvp.toString()
        props["allow-flight"] = c.allowFlight.toString()
        props["hardcore"] = c.hardcore.toString()
        props["enable-command-block"] = c.commandBlocks.toString()
        props["spawn-protection"] = c.spawnProtection.toString()
        props["spawn-animals"] = c.spawnAnimals.toString()
        props["spawn-monsters"] = c.spawnMonsters.toString()
        props["spawn-npcs"] = c.spawnNpcs.toString()
        props["generate-structures"] = c.generateStructures.toString()
        props["network-compression-threshold"] = c.networkCompression.toString()
        if (c.levelSeed.isNotBlank()) props["level-seed"] = c.levelSeed
        props["enable-jmx-monitoring"] = "false"
        props["sync-chunk-writes"] = "false"   // flash storage: avoid fsync stalls
        f.writeText("# Managed by LapisServe - edit values in the app\n" + props.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
    }

    /**
     * Pumpkin keeps everything in <server>/pumpkin.toml, which it writes (with full defaults) on first run.
     * A partial file does not work: Pumpkin refills missing sections with defaults and discards our values.
     * So we patch only the keys we manage in the generated file, section-aware.
     * Returns the "key = value" lines applied, or null if the file doesn't exist yet.
     */
    fun patchPumpkinConfig(c: ServerConfig, dir: File): List<String>? {
        val f = File(dir, "pumpkin.toml")
        if (!f.exists()) return null
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
        val top = buildMap {
            put("hardcore", c.hardcore.toString())
            put("default_gamemode", q(cap(c.gamemode)))
            put("default_difficulty", q(cap(c.difficulty)))
            if (c.levelSeed.isNotBlank()) put("seed", q(c.levelSeed))
        }
        val java = mapOf(
            "address" to q("0.0.0.0:${c.port}"),
            "online_mode" to c.onlineMode.toString(),
            "max_players" to c.maxPlayers.toString(),
            "view_distance" to c.viewDistance.toString(),
            "simulation_distance" to c.simulationDistance.toString(),
            "motd" to q(c.motd),
        )
        // Pumpkin also listens for Bedrock clients with its own copy of these limits; keep them in step.
        val bedrock = java - "address"
        val applied = ArrayList<String>()
        var section = ""
        val out = f.readLines().map { line ->
            val t = line.trim()
            if (t.startsWith("[")) { section = t.trim('[', ']'); return@map line }
            val key = t.substringBefore('=', "").trim()
            val v = when (section) { "" -> top[key]; "networking.java" -> java[key]; "networking.bedrock" -> bedrock[key]; else -> null }
            if (v != null && '=' in t) { applied += "$key = $v"; "$key = $v" } else line
        }
        f.writeText(out.joinToString("\n") + "\n")
        return applied
    }
}
