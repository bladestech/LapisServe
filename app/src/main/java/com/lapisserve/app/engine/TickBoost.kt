package com.lapisserve.app.engine

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Tells Android how hard a server's tick pipeline is working, through the same performance-hint API
 * (ADPF) games use for their render threads. When work per tick climbs towards the target, the power HAL
 * raises clocks before the tick runs late, instead of after Android notices on its own.
 *
 * Work per tick is estimated from the threads' real CPU time (/proc/.../schedstat, nanoseconds): the busiest
 * thread's busy fraction over a short window x the 50 ms tick, i.e. the critical path.
 *
 * - Java servers: the single "Server thread".
 * - Pumpkin: its tick loop ("Server-Ticker") plus the "Simulation-N" pool it fans the world out to.
 */
object TickBoost {
    private const val TICK_NS = 50_000_000L
    /** Aim to keep a tick at half its budget; boosting starts as the threads approach it. */
    private const val TARGET_NS = TICK_NS / 2
    private const val WINDOW_MS = 250L

    fun supported() = Build.VERSION.SDK_INT >= 31

    /** Threads that make up the tick's critical path. */
    fun criticalThreads(pid: Long, java: Boolean): IntArray {
        val list = Affinity.threads(pid)
        return if (java) Affinity.tickThread(pid, list).let { if (it > 0) intArrayOf(it) else IntArray(0) }
        else list.filter { it.second == "Server-Ticker" || it.second.startsWith("Simulation-") }.map { it.first }.toIntArray()
    }

    /** Waits for the server to finish booting, then keeps reporting until cancelled. [report] gets one status line. */
    suspend fun run(ctx: Context, pid: Long, java: Boolean, ready: () -> Boolean, report: (String) -> Unit) {
        if (!supported() || pid <= 0) return
        // The busiest threads are only clear once start-up is over.
        for (i in 0 until 600) { if (ready() || !currentCoroutineContext().isActive) break; delay(1000) }
        delay(3000)
        val tids = criticalThreads(pid, java)
        if (tids.isEmpty()) return
        val mgr = ctx.getSystemService(PerformanceHintManager::class.java) ?: return
        val session = try { mgr.createHintSession(tids, TARGET_NS) } catch (e: Throwable) {
            report("!CPU boost hints not available for the server: ${e.message ?: e.javaClass.simpleName}"); return
        }
        if (session == null) { report("CPU boost hints: not supported by this phone's power HAL; running without them."); return }
        report("CPU boost hints active for ${if (tids.size == 1) "the tick thread" else "${tids.size} tick threads"}.")
        try {
            var last = tids.map { Affinity.runtimeNs(pid, it) }; var lastAt = System.nanoTime()
            while (currentCoroutineContext().isActive) {
                delay(WINDOW_MS)
                val now = System.nanoTime(); val rt = tids.map { Affinity.runtimeNs(pid, it) }
                if (rt.all { it < 0 }) break                                   // threads or process gone
                val busy = rt.indices.maxOf { i -> if (rt[i] < 0 || last[i] < 0) 0.0 else (rt[i] - last[i]).toDouble() / (now - lastAt) }
                    .coerceIn(0.0, 1.0)
                last = rt; lastAt = now
                runCatching { session.reportActualWorkDuration((busy * TICK_NS).toLong().coerceAtLeast(1L)) }
            }
        } finally { runCatching { session.close() } }
    }
}
