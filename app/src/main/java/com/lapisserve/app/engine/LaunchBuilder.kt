package com.lapisserve.app.engine

import com.lapisserve.app.model.GcPreset
import com.lapisserve.app.model.ServerConfig
import com.lapisserve.app.model.ServerType
import com.lapisserve.app.model.supportsPlugins
import java.io.File

/** Turns a [ServerConfig] into the files and command line a server needs. Pure functions, no I/O besides writing config files. */
object LaunchBuilder {

    /** Cores the server may use. [freqs] are per-core max clocks; unknown clocks mean no automatic pinning. */
    fun effectiveCores(c: ServerConfig, total: Int, freqs: List<Long?> = List(total) { null }): Int =
        CoreTopology.plan(c, freqs.ifEmpty { List(total) { null } }).activeCores.coerceIn(1, total.coerceAtLeast(1))

    fun resolveGc(c: ServerConfig, cores: Int): GcPreset = when {
        c.gc != GcPreset.AUTO -> c.gc
        c.ramMb <= 1024 || cores <= 2 -> GcPreset.SERIAL
        c.type.supportsPlugins && c.ramMb >= 4096 -> GcPreset.G1_AIKAR
        else -> GcPreset.G1_DEFAULT
    }

    fun jvmArgs(c: ServerConfig, totalCores: Int, tmpDir: File, hostsFile: File? = null, javaMajor: Int = 21, plan: CorePlan? = null): List<String> {
        val p = plan ?: CoreTopology.plan(c, List(totalCores) { null })
        val cores = p.activeCores.coerceIn(1, totalCores.coerceAtLeast(1))
        // GC and JIT threads should not compete with the tick thread for its reserved core.
        val workers = p.workerCores.coerceIn(1, cores)
        val a = ArrayList<String>()
        a += "-Xmx${c.ramMb}M"
        // Starting at half the heap avoids the burst of GC-and-grow cycles when players start exploring,
        // while Android still only sees memory once it is actually used.
        a += when {
            c.preTouch -> "-Xms${c.ramMb}M"
            c.autoTune -> "-Xms${maxOf(minOf(c.ramMb, 512), c.ramMb / 2)}M"
            else -> "-Xms${minOf(c.ramMb, 256)}M"
        }
        if (c.preTouch) a += "-XX:+AlwaysPreTouch"
        a += "-XX:ActiveProcessorCount=$cores"
        if (c.autoTune) {
            // Few JIT threads on a phone: they otherwise steal whole cores for minutes after start-up.
            a += "-XX:CICompilerCount=${if (workers >= 6) 3 else 2}"
            // Java 25: 8-byte object headers. Less memory and better cache use for entity/chunk-heavy heaps.
            if (javaMajor >= 25) a += "-XX:+UseCompactObjectHeaders"
        }
        a += "-Djava.io.tmpdir=${tmpDir.path}"
        a += "-Dfile.encoding=UTF-8"
        // musl has no resolver config on Android; give the JVM a pre-resolved hosts file (supported on Java 9+).
        if (hostsFile != null && javaMajor >= 9) a += "-Djdk.net.hosts.file=${hostsFile.path}"
        a += "-XX:-UsePerfData"          // /tmp/hsperfdata is pointless on Android
        a += "-XX:+DisableExplicitGC"
        a += "-Xshare:auto"
        val conc = "-XX:ConcGCThreads=${(workers / 4).coerceAtLeast(1)}"
        when (resolveGc(c, cores)) {
            GcPreset.SERIAL -> a += "-XX:+UseSerialGC"
            GcPreset.PARALLEL -> { a += "-XX:+UseParallelGC"; a += "-XX:ParallelGCThreads=$workers" }
            GcPreset.G1_DEFAULT -> { a += listOf("-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:ParallelGCThreads=$workers", conc) }
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
                    "-XX:ParallelGCThreads=$workers", conc,
                )
            }
            GcPreset.ZGC -> when {
                javaMajor >= 21 -> {
                    a += "-XX:+UseZGC"
                    if (javaMajor < 23) a += "-XX:+ZGenerational"   // default (and the only mode) from Java 23
                    a += "-XX:ConcGCThreads=${(workers / 2).coerceAtLeast(1)}"
                }
                else -> a += listOf("-XX:+UseG1GC", "-XX:ParallelGCThreads=$workers", conc)   // ZGC needs Java 21+
            }
            GcPreset.AUTO -> {}
        }
        if (c.extraJvmArgs.isNotBlank()) a += splitArgs(c.extraJvmArgs)
        return a
    }

    /** Wrappers available in toybox on every Android since 8: pin to cores and lower scheduling priority. */
    fun schedulingPrefix(c: ServerConfig, plan: CorePlan? = null): List<String> {
        val out = ArrayList<String>()
        val nice = File("/system/bin/nice")
        if (c.cpuPriority.nice > 0 && nice.exists()) out += listOf(nice.path, "-n", c.cpuPriority.nice.toString())
        val mask = plan?.mask ?: c.cpuMask
        val taskset = File("/system/bin/taskset")
        if (mask != 0 && taskset.exists()) out += listOf(taskset.path, Integer.toHexString(mask))
        return out
    }

    /** Thread-count hints for Pumpkin's async runtime, so it doesn't spawn workers for cores it may not use. */
    fun nativeEnv(plan: CorePlan): Map<String, String> =
        mapOf("TOKIO_WORKER_THREADS" to plan.activeCores.toString(), "RAYON_NUM_THREADS" to plan.activeCores.toString())

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
        if (c.autoTune) {
            props["entity-broadcast-range-percentage"] = "75"   // send far-away entities less often
            props["use-native-transport"] = "true"
        }
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
        // Fastest zlib level: much less CPU per chunk sent, slightly bigger packets. Matters most while players explore.
        val compression = if (c.autoTune) mapOf("level" to "1") else emptyMap()
        val applied = ArrayList<String>()
        var section = ""
        val out = f.readLines().map { line ->
            val t = line.trim()
            if (t.startsWith("[")) { section = t.trim('[', ']'); return@map line }
            val key = t.substringBefore('=', "").trim()
            val v = when (section) {
                "" -> top[key]; "networking.java" -> java[key]; "networking.bedrock" -> bedrock[key]
                "networking.java.compression", "networking.bedrock.compression" -> compression[key]
                // LapisServe promises no telemetry; Pumpkin's anonymous heartbeat is on by default upstream.
                "telemetry" -> if (key == "enabled") "false" else null
                else -> null
            }
            if (v != null && '=' in t) { applied += "$key = $v"; "$key = $v" } else line
        }
        f.writeText(out.joinToString("\n") + "\n")
        return applied
    }
}
