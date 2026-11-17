package com.lapisserve.app.engine

import com.lapisserve.app.model.CoreStrategy
import com.lapisserve.app.model.ServerConfig
import com.lapisserve.app.model.ServerType

/**
 * Where a server's threads are allowed to run.
 *
 * @property mask process-wide affinity applied at launch (0 = no pinning).
 * @property tickCore core reserved for the main tick thread, or -1 when the tick thread is not singled out.
 * @property restMask affinity for every other thread once the tick thread has its own core (0 = leave as [mask]).
 * @property activeCores cores the server may use in total; drives JVM thread-pool sizing and the CPU gauge.
 */
data class CorePlan(
    val strategy: CoreStrategy,
    val mask: Int,
    val tickCore: Int,
    val restMask: Int,
    val activeCores: Int,
) {
    val dedicatedTick get() = tickCore >= 0 && restMask != 0
    /** Cores left for GC, JIT and worker threads. */
    val workerCores get() = if (dedicatedTick) Integer.bitCount(restMask) else activeCores
}

/**
 * Reads the phone's big.LITTLE layout from per-core max frequencies and decides where a server should run.
 *
 * Java Minecraft servers do almost all game logic on one "Server thread". Android's energy-aware scheduler
 * places threads by recent load, so a bursty tick thread can be parked on an efficiency core between ticks
 * and pay for the migration (and the lower clock) on the next one. Keeping the whole JVM on performance
 * cores, and giving the tick thread the fastest core to itself, removes both costs.
 */
object CoreTopology {
    /** A core counts as "performance" when its max clock is at least this fraction of the fastest core. */
    private const val PERFORMANCE_RATIO = 0.7

    fun fullMask(total: Int) = if (total >= 31) Int.MAX_VALUE else (1 shl total) - 1

    /** Cores clocked close to the fastest one, or 0 when the layout is unknown or every core is the same. */
    fun performanceMask(freqs: List<Long?>): Int {
        val known = freqs.filterNotNull()
        if (known.size != freqs.size || known.isEmpty()) return 0
        val max = known.max()
        if (known.min() == max) return 0
        var m = 0
        freqs.forEachIndexed { i, f -> if (i < 31 && f!! >= max * PERFORMANCE_RATIO) m = m or (1 shl i) }
        return if (m == fullMask(freqs.size)) 0 else m
    }

    /** The fastest core; ties go to the highest index, which Android SoCs use for the prime core. */
    fun bestCore(freqs: List<Long?>): Int {
        var best = -1; var bestF = -1L
        freqs.forEachIndexed { i, f -> if (f != null && i < 31 && f >= bestF) { best = i; bestF = f } }
        return best
    }

    /** Fewest cores worth keeping a server (or its non-tick threads) on; below this, small cores join in. */
    private const val MIN_POOL = 3

    /** What [CoreStrategy.AUTO] turns into for this server on this phone. */
    fun resolve(c: ServerConfig, freqs: List<Long?>): CoreStrategy {
        if (c.coreStrategy != CoreStrategy.AUTO) return c.coreStrategy
        if (c.cpuMask != 0) return CoreStrategy.MANUAL      // configs saved before strategies existed
        val known = freqs.isNotEmpty() && freqs.all { it != null }
        return when {
            !known || freqs.size < 4 -> CoreStrategy.ALL
            // Pumpkin spreads work over an async runtime: no single thread to single out, and it needs many cores.
            c.type == ServerType.PUMPKIN ->
                if (Integer.bitCount(performanceMask(freqs)) >= 4) CoreStrategy.PERFORMANCE else CoreStrategy.ALL
            else -> CoreStrategy.FOCUS
        }
    }

    /**
     * Layout-agnostic: works from per-core speed scores, so 1+3+4, 2+6, 1+2+5, 4+4, all-big and uniform chips
     * all get a sensible plan without knowing chip names.
     */
    fun plan(c: ServerConfig, freqs: List<Long?>): CorePlan {
        val total = freqs.size.coerceAtLeast(1)
        val all = fullMask(total)
        val strategy = resolve(c, freqs)
        val perf = performanceMask(freqs)
        fun pinned(mask: Int) = CorePlan(strategy, mask, -1, 0, if (mask == 0) total else Integer.bitCount(mask).coerceIn(1, total))
        return when (strategy) {
            CoreStrategy.ALL, CoreStrategy.AUTO -> pinned(0)
            CoreStrategy.MANUAL -> pinned(c.cpuMask and all)
            CoreStrategy.PERFORMANCE -> pinned(perf)
            CoreStrategy.FOCUS -> {
                val best = bestCore(freqs)
                if (best < 0 || total < 4) return pinned(perf)
                val tick = 1 shl best
                // Everything else stays on the other fast cores when there are enough of them,
                // otherwise (e.g. 2 big + 6 small) it may use every remaining core.
                val fastRest = (if (perf == 0) all else perf) and tick.inv()
                val rest = if (Integer.bitCount(fastRest) >= MIN_POOL) fastRest else all and tick.inv()
                val mask = rest or tick
                CorePlan(strategy, if (mask == all) 0 else mask, best, rest, Integer.bitCount(mask))
            }
        }
    }

    /** "cpu4-6" / "cpu0,2,5" style list of the cores in [mask]. */
    fun describe(mask: Int, total: Int): String {
        val ids = (0 until total).filter { (mask shr it) and 1 == 1 }
        if (ids.isEmpty()) return "none"
        val contiguous = ids.last() - ids.first() == ids.size - 1
        return "cpu" + if (ids.size > 2 && contiguous) "${ids.first()}-${ids.last()}" else ids.joinToString(",")
    }
}
