package com.lapisserve.app.engine

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.system.Os
import com.lapisserve.app.core.Arch
import com.lapisserve.app.core.Http
import com.lapisserve.app.core.humanBytes
import com.lapisserve.app.model.*
import com.lapisserve.app.providers.Providers
import com.lapisserve.app.runtime.JavaRuntime
import com.lapisserve.app.service.ServerService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Owns every server: persistence, installation, process lifecycle, logs and metrics. */
class ServerManager(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val root = File(app.filesDir, "servers").also { it.mkdirs() }
    private val indexFile = File(app.filesDir, "servers.json")

    val java = JavaRuntime(app)
    private val debuggable = (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val _servers = MutableStateFlow(loadAll())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()
    private val _states = MutableStateFlow<Map<String, ServerRuntime>>(emptyMap())
    val states: StateFlow<Map<String, ServerRuntime>> = _states.asStateFlow()

    private class Live(val process: Process, val startedAt: Long) {
        val stdin = OutputStreamWriter(process.outputStream)
        @Volatile var stopRequested = false
    }
    private val live = ConcurrentHashMap<String, Live>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val restarts = ConcurrentHashMap<String, Int>()
    private val wakeLocks = ConcurrentHashMap<String, PowerManager.WakeLock>()
    private val wifiLocks = ConcurrentHashMap<String, WifiManager.WifiLock>()

    // ------------------------------------------------------------------ persistence

    private fun loadAll(): List<ServerConfig> = runCatching {
        if (indexFile.exists()) json.decodeFromString<List<ServerConfig>>(indexFile.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private fun persist() { runCatching { indexFile.writeText(json.encodeToString(_servers.value)) } }

    fun dir(id: String) = File(root, id).also { it.mkdirs() }
    fun config(id: String) = _servers.value.firstOrNull { it.id == id }
    fun runtime(id: String) = _states.value[id] ?: ServerRuntime()

    fun update(c: ServerConfig) {
        _servers.update { list -> list.map { if (it.id == c.id) c else it } }
        persist()
    }

    fun create(type: ServerType, version: String, name: String, ramMb: Int): ServerConfig {
        val c = ServerConfig(
            id = UUID.randomUUID().toString().take(8), name = name.ifBlank { "${type.label} server" },
            type = type, version = version, ramMb = ramMb,
            gc = GcPreset.AUTO,
            // Pumpkin ships its own defaults; keep Paper/Vanilla conservative for phones.
            viewDistance = if (type == ServerType.PUMPKIN) 10 else 6,
            simulationDistance = if (type == ServerType.PUMPKIN) 8 else 4,
        )
        _servers.update { it + c }; persist()
        return c
    }

    fun delete(id: String) {
        forceStop(id)
        _servers.update { l -> l.filterNot { it.id == id } }; persist()
        _states.update { it - id }; logs.remove(id)
        File(root, id).deleteRecursively()
    }

    // ------------------------------------------------------------------ logs

    private class LogBuf {
        val lines = ArrayDeque<LogLine>()
        val flow = MutableStateFlow<List<LogLine>>(emptyList())
        val pending = AtomicBoolean(false)
        val seq = AtomicLong()
    }
    private val logs = ConcurrentHashMap<String, LogBuf>()
    private fun buf(id: String) = logs.getOrPut(id) { LogBuf() }
    fun logFlow(id: String): StateFlow<List<LogLine>> = buf(id).flow.asStateFlow()

    fun log(id: String, text: String, kind: LogLine.Kind = LogLine.Kind.INFO) {
        if (debuggable) android.util.Log.d("LapisServer", "[$id] $text")
        val b = buf(id)
        synchronized(b) {
            b.lines.addLast(LogLine(b.seq.incrementAndGet(), text, kind))
            while (b.lines.size > 1500) b.lines.removeFirst()
        }
        if (b.pending.compareAndSet(false, true)) scope.launch {
            delay(90); b.pending.set(false)
            b.flow.value = synchronized(b) { b.lines.toList() }
        }
    }

    fun clearLog(id: String) { val b = buf(id); synchronized(b) { b.lines.clear() }; b.flow.value = emptyList() }

    private fun classify(line: String): LogLine.Kind = when {
        Regex("""\b(ERROR|SEVERE|FATAL)\b""").containsMatchIn(line) || line.contains("Exception") -> LogLine.Kind.ERROR
        Regex("""\bWARN(ING)?\b""").containsMatchIn(line) -> LogLine.Kind.WARN
        else -> LogLine.Kind.INFO
    }

    private fun setState(id: String, f: (ServerRuntime) -> ServerRuntime) = _states.update { it + (id to f(it[id] ?: ServerRuntime())) }

    // ------------------------------------------------------------------ lifecycle

    fun isActive(id: String) = runtime(id).state.let { it != RunState.STOPPED && it != RunState.CRASHED }
    fun anyActive() = _states.value.values.any { it.state != RunState.STOPPED && it.state != RunState.CRASHED }

    fun start(id: String) {
        val c = config(id) ?: return
        if (isActive(id)) return
        restarts[id] = 0
        if (c.type.isJava && !c.acceptEula) {
            setState(id) { it.copy(state = RunState.STOPPED, message = "Accept the Minecraft EULA before starting.") }; return
        }
        ServerService.start(app)
        jobs[id] = scope.launch { runServer(id) }
    }

    fun stop(id: String) {
        val l = live[id]
        if (l == null) { jobs[id]?.cancel(); setState(id) { ServerRuntime() }; return }
        l.stopRequested = true
        setState(id) { it.copy(state = RunState.STOPPING, message = null) }
        scope.launch {
            val timeout = (config(id)?.stopTimeoutSec ?: 30).toLong()
            runCatching { l.stdin.write("stop\n"); l.stdin.flush() }
            val ok = withContext(Dispatchers.IO) { l.process.waitFor(timeout, TimeUnit.SECONDS) }
            if (!ok) {
                log(id, "Server did not stop within ${timeout}s - terminating.", LogLine.Kind.SYSTEM)
                l.process.destroy()
                if (!withContext(Dispatchers.IO) { l.process.waitFor(5, TimeUnit.SECONDS) }) l.process.destroyForcibly()
            }
        }
    }

    fun restart(id: String) {
        stop(id)
        scope.launch {
            withTimeoutOrNull(90_000) { while (live.containsKey(id) || runtime(id).state == RunState.STOPPING) delay(300) }
            start(id)
        }
    }

    fun forceStop(id: String) { live[id]?.let { it.stopRequested = true; it.process.destroyForcibly() }; jobs[id]?.cancel() }
    fun stopAll() = _servers.value.forEach { if (isActive(it.id)) stop(it.id) }

    fun sendCommand(id: String, cmd: String) {
        val l = live[id] ?: return
        log(id, "> $cmd", LogLine.Kind.INPUT)
        scope.launch(Dispatchers.IO) { runCatching { l.stdin.write(cmd + "\n"); l.stdin.flush() } }
    }

    // ------------------------------------------------------------------ install + run

    /** Downloads whatever the server needs (Java, jar/binary). Safe to call repeatedly. */
    suspend fun install(c0: ServerConfig) {
        var c = c0
        val d = dir(c.id)
        val needServer = c.installedVersion != c.version || !File(d, c.jarFile.ifEmpty { "-" }).exists()
        val needJavaMajor: Int
        if (needServer) {
            setProgress(c.id, Progress("Resolving ${c.type.label} ${c.version}", null))
            val art = Providers.resolve(c.type, c.version)
            needJavaMajor = art.javaMajor
            if (c.type.isJava) {
                java.ensure(c.javaMajorOverride.takeIf { it > 0 } ?: art.javaMajor) { setProgress(c.id, it) }
            }
            val target = File(d, art.fileName)
            Http.download(art.url, target, art.sha256, art.sha1, art.md5) { done, total ->
                setProgress(c.id, Progress("Downloading ${c.type.label} ${c.version}", if (total > 0) done.toFloat() / total else null,
                    "${done.humanBytes()} of ${total.humanBytes()}"))
            }
            if (!c.type.isJava) runCatching { Os.chmod(target.path, 493) }
            File(d, ".java-major").writeText(needJavaMajor.toString())
            c = c.copy(installedVersion = c.version, jarFile = art.fileName); update(c)
        } else if (c.type.isJava) {
            val major = c.javaMajorOverride.takeIf { it > 0 } ?: File(d, ".java-major").readTextOrNull()?.trim()?.toIntOrNull() ?: 21
            if (!java.isInstalled(major)) java.ensure(major) { setProgress(c.id, it) }
        }
        setProgress(c.id, null)
    }

    private fun File.readTextOrNull() = runCatching { readText() }.getOrNull()
    private fun setProgress(id: String, p: Progress?) = setState(id) { it.copy(progress = p) }

    private suspend fun runServer(id: String) {
        var c = config(id) ?: return
        val d = dir(id)
        acquireLocks(c)
        try {
            setState(id) { ServerRuntime(state = RunState.INSTALLING) }
            log(id, "-- ${c.name}: preparing ${c.type.label} ${c.version} --", LogLine.Kind.SYSTEM)
            try { install(c) } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                log(id, "Setup failed: ${e.message}", LogLine.Kind.ERROR)
                setState(id) { ServerRuntime(state = RunState.CRASHED, message = e.message ?: "Setup failed") }
                return
            }
            c = config(id) ?: return
            File(d, "tmp").mkdirs()
            val hostsFile = File(d, "lapis-hosts")
            if (c.type.isJava) {
                val (n, failed) = DnsHosts.write(hostsFile, c.extraHosts)
                log(id, "DNS: resolved $n hostnames for the Java runtime." + if (failed.isNotEmpty()) " Could not resolve: ${failed.joinToString()}" else "", LogLine.Kind.SYSTEM)
            }
            if (c.type.isJava) LaunchBuilder.writeJavaConfig(c, d) else {
                if (!File(d, "pumpkin.toml").exists()) bootstrapPumpkin(id, d, File(d, c.jarFile))
                val applied = LaunchBuilder.patchPumpkinConfig(c, d)
                if (applied == null) log(id, "Config: pumpkin.toml was not created, so your settings could not be applied.", LogLine.Kind.WARN)
                else log(id, "Config applied to pumpkin.toml: " + applied.joinToString(", ") { it.replace(" = ", "=") }, LogLine.Kind.SYSTEM)
            }

            val cores = Runtime.getRuntime().availableProcessors()
            val cmd = ArrayList<String>()
            var javaEnv: Map<String, String> = emptyMap()
            cmd += LaunchBuilder.schedulingPrefix(c)
            if (c.type.isJava) {
                val major = c.javaMajorOverride.takeIf { it > 0 } ?: File(d, ".java-major").readTextOrNull()?.trim()?.toIntOrNull() ?: 21
                cmd += java.commandPrefix(major)
                javaEnv = java.environment(major)
                cmd += LaunchBuilder.jvmArgs(c, cores, File(d, "tmp"), hostsFile, major)
                cmd += listOf("-jar", c.jarFile, "nogui")
            } else cmd += File(d, c.jarFile).path
            if (c.extraServerArgs.isNotBlank()) cmd += LaunchBuilder.splitArgs(c.extraServerArgs)

            log(id, "$ " + cmd.joinToString(" ").take(600), LogLine.Kind.SYSTEM)
            val pb = ProcessBuilder(cmd).directory(d).redirectErrorStream(true)
            pb.environment().apply { put("HOME", d.path); put("TMPDIR", File(d, "tmp").path); put("LANG", "C.UTF-8"); putAll(javaEnv) }
            val proc = try { withContext(Dispatchers.IO) { pb.start() } } catch (e: Throwable) {
                log(id, "Could not start process: ${e.message}", LogLine.Kind.ERROR)
                setState(id) { ServerRuntime(state = RunState.CRASHED, message = "Could not start: ${e.message}") }
                return
            }
            val l = Live(proc, System.currentTimeMillis()); live[id] = l
            val pid = pidOf(proc)
            setState(id) { ServerRuntime(state = RunState.STARTING, pid = pid) }
            ServerService.refresh()

            val allowed = LaunchBuilder.effectiveCores(c, cores)
            val metrics = scope.launch { metricsLoop(id, l, pid, allowed) }
            val dnsRefresh = if (c.type.isJava) scope.launch { while (isActive) { delay(5 * 60_000L); runCatching { DnsHosts.write(hostsFile, config(id)?.extraHosts ?: "") } } } else null
            val readyRegex = if (c.type.isJava) Regex("""Done \(.*\)! For help""") else Regex("""(?i)(done|started|listening|ready)""")
            val fallbackReady = if (!c.type.isJava) scope.launch { delay(20_000); markRunning(id) } else null
            withContext(Dispatchers.IO) {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    log(id, line, classify(line))
                    if (readyRegex.containsMatchIn(line)) markRunning(id)
                }
            }
            val code = withContext(Dispatchers.IO) { proc.waitFor() }
            fallbackReady?.cancel(); metrics.cancel(); dnsRefresh?.cancel()
            live.remove(id)
            if (l.stopRequested || code == 0) {
                log(id, "-- server stopped (exit $code) --", LogLine.Kind.SYSTEM)
                setState(id) { ServerRuntime() }
            } else {
                log(id, "-- server exited unexpectedly (exit $code) --", LogLine.Kind.ERROR)
                setState(id) { ServerRuntime(state = RunState.CRASHED, message = "Exited with code $code" + hint(code)) }
                val n = restarts.merge(id, 1, Int::plus) ?: 1
                if (c.autoRestartOnCrash && n <= 3) {
                    log(id, "Auto-restart $n/3 in 5s...", LogLine.Kind.SYSTEM)
                    delay(5000); jobs[id] = scope.launch { runServer(id) }
                    return
                }
            }
        } finally {
            if (!live.containsKey(id)) releaseLocks(id)
            ServerService.refresh()
        }
    }

    private fun hint(code: Int) = when (code) {
        137 -> " (killed - Android likely ran out of memory or its phantom-process limit stopped the server; see Device Tuning)"
        else -> ""
    }

    /** First run only: let Pumpkin write its complete default pumpkin.toml, then stop it so we can patch it. */
    private suspend fun bootstrapPumpkin(id: String, d: File, bin: File) {
        log(id, "First run: letting Pumpkin generate its default config...", LogLine.Kind.SYSTEM)
        val cfg = File(d, "pumpkin.toml")
        val p = runCatching {
            withContext(Dispatchers.IO) { ProcessBuilder(bin.path).directory(d).redirectErrorStream(true).apply { environment()["HOME"] = d.path }.start() }
        }.getOrElse { log(id, "Could not run Pumpkin to create its config: ${it.message}", LogLine.Kind.ERROR); return }
        val drain = scope.launch(Dispatchers.IO) { runCatching { p.inputStream.readBytes() } }   // keep the pipe empty
        withTimeoutOrNull(20_000) { while (!cfg.exists()) delay(250) }
        delay(1500)   // let it finish writing the file
        runCatching { p.outputStream.write("stop\n".toByteArray()); p.outputStream.flush() }
        val exited = withContext(Dispatchers.IO) { p.waitFor(8, TimeUnit.SECONDS) }
        if (!exited) { p.destroy(); withContext(Dispatchers.IO) { p.waitFor(3, TimeUnit.SECONDS) } }
        drain.cancel()
    }

    private fun markRunning(id: String) = setState(id) { if (it.state == RunState.STARTING) it.copy(state = RunState.RUNNING) else it }

    private suspend fun metricsLoop(id: String, l: Live, pid: Long, allowedCores: Int) {
        val hz = runCatching { Os.sysconf(android.system.OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)
        var lastTicks = cpuTicks(pid); var lastAt = System.nanoTime()
        var smoothed = 0f
        while (currentCoroutineContext().isActive) {
            delay(2000)
            val ticks = cpuTicks(pid); val now = System.nanoTime()
            if (ticks >= 0 && lastTicks >= 0) {
                val cores = (ticks - lastTicks).toFloat() / hz / ((now - lastAt) / 1e9f)
                smoothed = if (smoothed == 0f) cores else smoothed * 0.5f + cores * 0.5f   // light smoothing so the gauge doesn't jitter
            }
            lastTicks = ticks; lastAt = now
            val rss = rssMb(pid)
            setState(id) { it.copy(rssMb = rss, cpuCoresUsed = smoothed, cpuCoresAllowed = allowedCores, uptimeSec = (System.currentTimeMillis() - l.startedAt) / 1000) }
        }
    }

    /** utime + stime (clock ticks) of the whole process, or -1 if unreadable. */
    private fun cpuTicks(pid: Long): Long = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        val f = stat.substring(stat.lastIndexOf(')') + 2).split(' ')   // field 3 onwards
        f[11].toLong() + f[12].toLong()
    }.getOrDefault(-1L)

    private fun rssMb(pid: Long): Int = runCatching {
        File("/proc/$pid/status").useLines { ls -> ls.first { it.startsWith("VmRSS:") } }
            .split(Regex("\\s+"))[1].toLong().div(1024).toInt()
    }.getOrDefault(0)

    private fun pidOf(p: Process): Long =
        runCatching { Process::class.java.getMethod("pid").invoke(p) as Long }.getOrNull()
            ?: runCatching { p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p).toLong() }.getOrDefault(0L)

    // ------------------------------------------------------------------ wake locks

    private fun acquireLocks(c: ServerConfig) {
        if (c.keepCpuAwake && !wakeLocks.containsKey(c.id)) {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLocks[c.id] = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LapisServe:${c.id}").apply { setReferenceCounted(false); acquire() }
        }
        if (c.keepWifiAwake && !wifiLocks.containsKey(c.id)) {
            val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLocks[c.id] = wm.createWifiLock(mode, "LapisServe:${c.id}").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks(id: String) {
        wakeLocks.remove(id)?.let { if (it.isHeld) it.release() }
        wifiLocks.remove(id)?.let { if (it.isHeld) it.release() }
    }

    /** Summary for the foreground notification. */
    fun activeSummary(): Pair<Int, String> {
        val active = _servers.value.filter { isActive(it.id) }
        val text = active.joinToString(" · ") { s ->
            val r = runtime(s.id); "${s.name}: ${r.state.name.lowercase().replaceFirstChar { it.uppercase() }}"
        }
        return active.size to text
    }
}
