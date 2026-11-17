package com.lapisserve.app.engine

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Keeps a running server's threads on the cores its [CorePlan] asks for, with toybox `taskset -p`.
 * Android lets an app change the affinity of its own child processes' threads (same uid); no root needed.
 *
 * Pinning once at launch is not enough: Android rewrites an app's cpuset as it moves between foreground
 * and background (and some kernels reset per-thread affinity when that happens), and new threads inherit
 * the affinity of whichever thread created them. So this checks every thread periodically and re-applies.
 */
object Affinity {
    private val taskset = File("/system/bin/taskset")
    val available get() = taskset.exists()

    /** Name of the thread that runs the game loop on Vanilla, Paper and Purpur. */
    const val TICK_THREAD = "Server thread"

    fun threads(pid: Long): List<Pair<Int, String>> =
        File("/proc/$pid/task").listFiles().orEmpty().mapNotNull { t ->
            val tid = t.name.toIntOrNull() ?: return@mapNotNull null
            tid to (runCatching { File(t, "comm").readText().trim() }.getOrNull() ?: return@mapNotNull null)
        }

    /** Total CPU time a thread has used, in nanoseconds (-1 if unreadable). */
    fun runtimeNs(pid: Long, tid: Int): Long =
        runCatching { File("/proc/$pid/task/$tid/schedstat").readText().trim().substringBefore(' ').toLong() }.getOrDefault(-1L)

    /**
     * The thread running the game loop. Some versions have more than one thread with that name
     * (one only does start-up work), so pick the one that has used the most CPU.
     */
    fun tickThread(pid: Long, list: List<Pair<Int, String>> = threads(pid)): Int =
        list.filter { it.second == TICK_THREAD }.maxByOrNull { runtimeNs(pid, it.first) }?.first ?: -1

    /** Current affinity of a thread as a bitmask, or -1 if unreadable. */
    fun maskOf(pid: Long, tid: Int): Int = runCatching {
        File("/proc/$pid/task/$tid/status").useLines { ls -> ls.first { it.startsWith("Cpus_allowed:") } }
            .substringAfter(':').trim().replace(",", "").takeLast(8).toLong(16).toInt()
    }.getOrDefault(-1)

    fun pin(tid: Int, mask: Int): Boolean = runCatching {
        val p = ProcessBuilder(taskset.path, "-p", Integer.toHexString(mask), tid.toString()).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    /** The mask a thread should have under [plan]; 0 means "leave it alone". */
    fun desired(plan: CorePlan, isTick: Boolean): Int = when {
        plan.dedicatedTick && isTick -> 1 shl plan.tickCore
        plan.dedicatedTick -> plan.restMask
        else -> plan.mask
    }

    /**
     * Enforces [plan] until cancelled. [javaTick] says whether to look for a tick thread at all.
     * [report] gets status lines; ones starting with "!" are warnings.
     */
    suspend fun enforce(pid: Long, plan: CorePlan, javaTick: Boolean, totalCores: Int, report: (String) -> Unit) {
        if (!available || pid <= 0) return
        if (plan.mask == 0 && !plan.dedicatedTick) return
        val focus = plan.dedicatedTick && javaTick
        val effective = if (focus) plan else plan.copy(tickCore = -1, restMask = 0)
        if (effective.mask == 0 && !focus) return
        val ok = HashSet<Int>()             // tids that already had the right mask last pass
        var tickTid = -1
        var announced = false
        var resets = 0
        var parkedSince = -1                // pass at which the tick core was found parked, or -1
        var parkedNoted = false
        var pass = 0
        while (currentCoroutineContext().isActive) {
            val list = threads(pid)
            if (list.isEmpty() && pass > 0) return   // process gone
            // Re-evaluated during boot and then now and again, in case the busiest "Server thread" changes.
            if (focus && (tickTid < 0 || pass < 90 || pass % 20 == 0)) {
                val t = tickThread(pid, list)
                if (t > 0 && t != tickTid) { tickTid = t; ok.remove(t) }
            }
            var fixed = 0
            for ((tid, _) in list) {
                val isTick = tid == tickTid
                // While the tick core is parked, the tick thread shares the other fast cores; retry every ~15 s.
                val parked = isTick && parkedSince >= 0 && pass - parkedSince < 15
                val want = (if (parked) effective.restMask or (1 shl effective.tickCore) else desired(effective, isTick))
                    .takeIf { it != 0 } ?: continue
                val have = maskOf(pid, tid)
                // The kernel temporarily narrows a thread's mask while cores are parked; a subset is fine.
                if (have > 0 && have and want.inv() == 0) { ok += tid; continue }
                // A thread that was right before and is now wrong was reset by the system, not inherited.
                if (tid in ok) resets++
                if (pin(tid, want)) {
                    ok += tid; fixed++
                    if (isTick && !parked && parkedSince >= 0) parkedSince = -1     // tick core is back
                } else if (isTick) {
                    // Android parks cores (usually the fastest, when hot) and refuses affinity to only parked cores.
                    parkedSince = pass
                    pin(tid, effective.restMask or (1 shl effective.tickCore))
                    if (!parkedNoted) {
                        parkedNoted = true
                        report("cpu${plan.tickCore} is parked by Android (usually to cool down); the tick thread uses the other fast cores until it is back.")
                    }
                }
                if (fixed >= 48) break       // spread a large first pass over a few iterations
            }
            ok.retainAll(list.map { it.first }.toSet())
            if (!announced && (!focus || tickTid > 0) && fixed > 0) {
                announced = true
                report(if (focus) "Tick thread pinned to cpu${plan.tickCore}; other threads on ${CoreTopology.describe(plan.restMask, totalCores)}."
                       else "Threads pinned to ${CoreTopology.describe(plan.mask, totalCores)}.")
            }
            if (resets > 0 && resets % 50 == 1) report("Android reset the server's cores ($resets threads so far); re-applied.")
            pass++
            // Quick passes while the server boots and spawns threads, then a cheap check every few seconds.
            delay(if (pass < 60) 1000 else 3000)
        }
    }
}
