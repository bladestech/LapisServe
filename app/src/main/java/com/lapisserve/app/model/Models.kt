package com.lapisserve.app.model

import kotlinx.serialization.Serializable

enum class ServerType(val label: String, val tagline: String, val isJava: Boolean) {
    VANILLA("Vanilla", "The official Mojang server. Pure, unmodified.", true),
    PAPER("Paper", "High-performance fork with plugin support.", true),
    PURPUR("Purpur", "Paper fork with extra gameplay options.", true),
    PUMPKIN("Pumpkin", "Experimental server written in Rust. Tiny footprint.", false),
}

/** Short version for display: Pumpkin tags like "0.2.2+26.3-26.60" are trimmed to "0.2.2". */
val ServerConfig.displayVersion: String get() = if (type == ServerType.PUMPKIN) version.substringBefore('+') else version

/** Servers that load Bukkit-style plugins (and therefore get the Modrinth plugin browser). */
val ServerType.supportsPlugins get() = this == ServerType.PAPER || this == ServerType.PURPUR

enum class GcPreset(val label: String, val blurb: String) {
    AUTO("Automatic", "Let LapisServe pick based on heap size and core count."),
    G1_AIKAR("G1 · Aikar tuned", "Best for Paper with 4 GB+. Lower pauses."),
    G1_DEFAULT("G1 · Default", "JVM default. Solid all-rounder."),
    PARALLEL("Parallel", "Higher throughput, longer pauses. Fine on small heaps."),
    SERIAL("Serial", "Lowest overhead. Best for 1 GB or less, or 1-2 cores."),
}

enum class CpuPriority(val label: String, val nice: Int) {
    NORMAL("Normal", 0),
    GENTLE("Gentle", 5),
    BACKGROUND("Background", 10),
}

@Serializable
data class ServerConfig(
    val id: String,
    val name: String,
    val type: ServerType,
    val version: String,
    val createdAt: Long = System.currentTimeMillis(),
    // ---- resources
    val ramMb: Int = 1024,
    val cpuMask: Int = 0,               // bitmask of pinned cores, 0 = no pinning
    val cpuPriority: CpuPriority = CpuPriority.NORMAL,
    val gc: GcPreset = GcPreset.AUTO,
    val preTouch: Boolean = false,
    val extraJvmArgs: String = "",
    val extraServerArgs: String = "",
    /** Extra hostnames (space/comma separated) the server should be able to resolve, e.g. for plugins. */
    val extraHosts: String = "",
    val javaMajorOverride: Int = 0,     // 0 = auto
    // ---- game
    val port: Int = 25565,
    val motd: String = "A LapisServe server",
    val maxPlayers: Int = 10,
    val difficulty: String = "normal",
    val gamemode: String = "survival",
    val viewDistance: Int = 8,
    val simulationDistance: Int = 6,
    val onlineMode: Boolean = false,
    val whitelist: Boolean = false,
    val pvp: Boolean = true,
    val allowFlight: Boolean = false,
    val hardcore: Boolean = false,
    val commandBlocks: Boolean = false,
    val spawnProtection: Int = 16,
    val levelSeed: String = "",
    val spawnAnimals: Boolean = true,
    val spawnMonsters: Boolean = true,
    val spawnNpcs: Boolean = true,
    val generateStructures: Boolean = true,
    val networkCompression: Int = 256,
    // ---- behaviour
    val acceptEula: Boolean = false,
    val autoRestartOnCrash: Boolean = false,
    val keepCpuAwake: Boolean = true,
    val keepWifiAwake: Boolean = true,
    val stopTimeoutSec: Int = 30,
    // ---- install state
    val installedVersion: String = "",
    val jarFile: String = "",
)

enum class RunState { STOPPED, INSTALLING, STARTING, RUNNING, STOPPING, CRASHED }

data class LogLine(val id: Long, val text: String, val kind: Kind = Kind.INFO) {
    enum class Kind { INFO, WARN, ERROR, SYSTEM, INPUT }
}

data class Progress(val label: String, val fraction: Float?, val detail: String = "")

data class ServerRuntime(
    val state: RunState = RunState.STOPPED,
    val progress: Progress? = null,
    val rssMb: Int = 0,
    /** CPU time in use, in cores (e.g. 1.5 = one and a half cores busy). */
    val cpuCoresUsed: Float = 0f,
    /** Cores this server is allowed to use (after pinning). */
    val cpuCoresAllowed: Int = 0,
    val uptimeSec: Long = 0,
    val pid: Long = 0,
    val message: String? = null,
)

data class VersionEntry(val id: String, val channel: String = "release")
