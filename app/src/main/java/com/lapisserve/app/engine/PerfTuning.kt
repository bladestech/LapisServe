package com.lapisserve.app.engine

import com.lapisserve.app.model.ServerConfig
import com.lapisserve.app.model.supportsPlugins
import java.io.File

/**
 * Edits scalar values in an existing YAML file by dotted path ("world-settings.default.mob-spawn-range"),
 * keeping comments, ordering and every key we don't manage. Keys that aren't in the file are left out:
 * servers generate complete files on first run, so a missing key means this version doesn't have it.
 */
object YamlPatch {
    private val keyLine = Regex("""^(\s*)("[^"]+"|'[^']+'|[^\s#:][^:#]*?):(\s*)(.*)$""")

    fun apply(f: File, values: Map<String, String>): List<String> {
        if (!f.exists()) return emptyList()
        val stack = ArrayList<Pair<Int, String>>()   // indent, key
        val applied = ArrayList<String>()
        val out = f.readLines().map { line ->
            val t = line.trimStart()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("- ")) return@map line
            val m = keyLine.matchEntire(line) ?: return@map line
            val indent = m.groupValues[1].length
            val key = m.groupValues[2].trim().trim('"', '\'')
            while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeAt(stack.lastIndex)
            val path = (stack.map { it.second } + key).joinToString(".")
            val rest = m.groupValues[4]
            if (rest.isEmpty() || rest.startsWith("#")) { stack += indent to key; return@map line }
            val v = values[path] ?: return@map line
            // Only replace plain scalars; leave lists, maps and block strings alone.
            if (rest.startsWith("[") || rest.startsWith("{") || rest.startsWith("|") || rest.startsWith(">")) return@map line
            applied += path
            "${m.groupValues[1]}${m.groupValues[2]}: $v"
        }
        if (applied.isNotEmpty()) f.writeText(out.joinToString("\n") + "\n")
        return applied
    }
}

/**
 * Server-side settings that cost little or no gameplay but save a lot of tick time on a phone.
 * Based on the widely used Paper/Spigot optimisation guides, kept on the conservative side.
 */
object PerfTuning {
    val paperWorld = mapOf(
        "chunks.max-auto-save-chunks-per-tick" to "8",
        "chunks.prevent-moving-into-unloaded-chunks" to "true",
        "collisions.max-entity-collisions" to "2",
        "environment.optimize-explosions" to "true",
        "environment.treasure-maps.enabled" to "false",
        "misc.update-pathfinding-on-block-update" to "false",
        "misc.redstone-implementation" to "ALTERNATE_CURRENT",
        "entities.behavior.disable-chest-cat-detection" to "true",
        "tick-rates.mob-spawner" to "2",
        "tick-rates.grass-spread" to "4",
    )
    val paperGlobal = mapOf(
        "timings.enabled" to "false",
    )
    val spigot = mapOf(
        "world-settings.default.mob-spawn-range" to "6",
        "world-settings.default.merge-radius.item" to "3.0",
        "world-settings.default.merge-radius.exp" to "4.0",
        "world-settings.default.entity-activation-range.animals" to "16",
        "world-settings.default.entity-activation-range.monsters" to "24",
        "world-settings.default.entity-activation-range.raiders" to "32",
        "world-settings.default.entity-activation-range.misc" to "8",
        "world-settings.default.entity-activation-range.water" to "8",
        "world-settings.default.entity-activation-range.villagers" to "16",
        "world-settings.default.entity-activation-range.flying-monsters" to "24",
    )
    val bukkit = mapOf(
        "chunk-gc.period-in-ticks" to "400",
        "ticks-per.monster-spawns" to "2",
        "spawn-limits.monsters" to "50",
        "spawn-limits.animals" to "8",
        "spawn-limits.water-animals" to "4",
        "spawn-limits.water-ambient" to "10",
        "spawn-limits.ambient" to "1",
    )

    /** Returns a short summary for the console, or null when there was nothing to tune yet. */
    fun apply(c: ServerConfig, dir: File): String? {
        if (!c.autoTune || !c.type.supportsPlugins) return null
        val n = YamlPatch.apply(File(dir, "config/paper-world-defaults.yml"), paperWorld).size +
            YamlPatch.apply(File(dir, "config/paper-global.yml"), paperGlobal).size +
            YamlPatch.apply(File(dir, "spigot.yml"), spigot).size +
            YamlPatch.apply(File(dir, "bukkit.yml"), bukkit).size
        return if (n == 0) null else "Performance tuning: $n Paper/Spigot/Bukkit settings applied."
    }

    fun firstRunPending(dir: File) = !File(dir, "spigot.yml").exists()
}
