package com.lapisserve.app.engine

import com.lapisserve.app.model.RunState
import com.lapisserve.app.model.ServerConfig
import com.lapisserve.app.model.ServerRuntime

enum class Severity { GOOD, INFO, WARN, CRITICAL }
enum class InsightAction(val label: String) { OPEN_SETTINGS("Open settings"), OPEN_DEVICE("Device tuning") }

data class Insight(val severity: Severity, val title: String, val text: String, val action: InsightAction? = null)

/** What the phone is doing right now, as far as it affects a server. */
data class DeviceHealth(
    val batteryPct: Int = -1, val charging: Boolean = false, val thermal: String = "Unknown",
    val freeRamMb: Int = Int.MAX_VALUE, val totalRamMb: Int = 0,
)

/** Plain-language advice for a running server, derived from live numbers. Order is most important first. */
object Insights {
    fun forServer(cfg: ServerConfig, rt: ServerRuntime, dev: DeviceHealth, cores: Int): List<Insight> {
        if (rt.state != RunState.RUNNING) return emptyList()
        val out = ArrayList<Insight>()
        val ramFrac = if (cfg.ramMb > 0) rt.rssMb.toFloat() / cfg.ramMb else 0f
        val cpuFrac = rt.cpuCoresUsed / (rt.cpuCoresAllowed.coerceAtLeast(1))
        val tps = rt.tps
        val vd = cfg.viewDistance

        if (dev.freeRamMb < 400) out += Insight(
            Severity.CRITICAL, "Android is low on memory",
            "Only ${dev.freeRamMb} MB is free on the phone. Android may close the server to make room. Close other apps or lower this server's RAM.",
            InsightAction.OPEN_DEVICE,
        )
        if (tps != null && tps < 15f) out += Insight(
            Severity.CRITICAL, "The server is lagging",
            "TPS is ${"%.1f".format(tps)} (20 is perfect). Lower the view distance from $vd to ${(vd - 2).coerceAtLeast(3)}, or give the server more CPU cores.",
            InsightAction.OPEN_SETTINGS,
        ) else if (tps != null && tps < 19f) out += Insight(
            Severity.WARN, "Ticks are slipping",
            "TPS is ${"%.1f".format(tps)}. It may be catching up after loading chunks. If it stays low, reduce the view distance.",
            InsightAction.OPEN_SETTINGS,
        )
        val mspt = rt.mspt
        if (mspt != null && mspt >= 45f && (tps == null || tps >= 19f)) out += Insight(
            Severity.WARN, "Close to the tick limit",
            "Each tick takes ${"%.0f".format(mspt)} ms of the 50 ms available, so there is little headroom for more players.",
            InsightAction.OPEN_SETTINGS,
        )
        if (ramFrac > 0.92f) out += Insight(
            Severity.WARN, "Memory is nearly full",
            "The server is using ${"%.0f".format(ramFrac * 100)}% of its ${cfg.ramMb} MB. " +
                if (dev.freeRamMb > 1024) "There is spare memory on the phone, so you can raise its RAM." else "Lower the view distance, since the phone has little spare memory.",
            InsightAction.OPEN_SETTINGS,
        )
        if (cpuFrac > 0.9f) out += Insight(
            Severity.WARN, "The processor is maxed out",
            "The server is using nearly all of its $cores allocated core${if (cores == 1) "" else "s"}. " +
                if (cfg.cpuMask != 0) "It is pinned to specific cores; unpinning may help." else "Reduce the view distance or the number of players.",
            InsightAction.OPEN_SETTINGS,
        )
        if (dev.thermal in setOf("Throttling", "Hot", "Critical")) out += Insight(
            Severity.WARN, "The phone is running hot",
            "Android is slowing the processor to cool down (${dev.thermal.lowercase()}), which lowers TPS. Let it cool, remove the case, or use the Battery saver preset.",
            InsightAction.OPEN_DEVICE,
        )
        if (dev.batteryPct in 0..19 && !dev.charging) out += Insight(
            Severity.WARN, "Battery is low",
            "${dev.batteryPct}% left and not charging. Plug in to keep the server running.", InsightAction.OPEN_DEVICE,
        )
        if (out.isEmpty()) out += Insight(Severity.GOOD, "Everything looks healthy", "TPS, memory and processor are all comfortable.")
        return out.sortedByDescending { it.severity.ordinal }
    }
}
