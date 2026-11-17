package com.lapisserve.app.engine

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.system.Os
import com.lapisserve.app.core.Arch
import com.lapisserve.app.core.Http
import com.lapisserve.app.core.humanBytes
import com.lapisserve.app.core.sizeRecursive
import com.lapisserve.app.model.*
import com.lapisserve.app.providers.Providers
import com.lapisserve.app.runtime.JavaRuntime
import com.lapisserve.app.core.McVersion
import com.lapisserve.app.service.ServerService
import com.lapisserve.app.system.SystemInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
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
    private val _events = MutableStateFlow<List<ServerEvent>>(emptyList())
    val events: StateFlow<List<ServerEvent>> = _events.asStateFlow()
    private val eventSeq = AtomicLong()
    private val tracked = ConcurrentHashMap<String, MutableSet<String>>()     // players seen joining, per server
    private val pollWindow = ConcurrentHashMap<String, Long>()                // swallow our own tps/tick output until this time

    fun postEvent(serverId: String, kind: ServerEvent.Kind, text: String) {
        val name = config(serverId)?.name ?: serverId
        if (kind == ServerEvent.Kind.CRASHED) com.lapisserve.app.service.Alerts.crashed(app, name, text)
        _events.update { (listOf(ServerEvent(eventSeq.incrementAndGet(), System.currentTimeMillis(), serverId, name, kind, text)) + it).take(300) }
    }

    // ------------------------------------------------------------------ backups

    private fun backupDir(id: String) = File(app.filesDir, "backups/$id")
    fun listBackups(id: String): List<Backup> = Backups.list(backupDir(id))
    fun deleteBackup(b: Backup) { b.file.delete() }

    /** Zips worlds and member lists. On a running server it asks the world to flush to disk first. */
    suspend fun createBackup(id: String): Backup = withContext(Dispatchers.IO) {
        val c = config(id) ?: error("Unknown server")
        val l = live[id]
        if (l != null && c.type.isJava) {
            runCatching { l.stdin.write("save-off\n"); l.stdin.write("save-all flush\n"); l.stdin.flush() }
            delay(3500)
        }
        try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val dest = File(backupDir(id), "${c.name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "server" }}-$stamp.zip")
            val size = Backups.create(dir(id), dest)
            postEvent(id, ServerEvent.Kind.INFO, "Backup saved (${size.humanBytes()})")
            Backup(dest, size, dest.lastModified())
        } finally {
            if (l != null && c.type.isJava) runCatching { l.stdin.write("save-on\n"); l.stdin.flush() }
        }
    }

    suspend fun restoreBackup(id: String, b: Backup) = withContext(Dispatchers.IO) {
        check(!isActive(id)) { "Stop the server before restoring a backup" }
        Backups.restore(b.file, dir(id))
        postEvent(id, ServerEvent.Kind.INFO, "Restored backup ${b.name}")
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

    /** Creates a server; [customize] applies the choices made on the create screen on top of the defaults. */
    fun create(type: ServerType, version: String, name: String, ramMb: Int, customize: (ServerConfig) -> ServerConfig = { it }): ServerConfig {
        val base = ServerConfig(
            id = UUID.randomUUID().toString().take(8), name = name.ifBlank { "${type.label} server" },
            type = type, version = version, ramMb = ramMb,
            gc = GcPreset.AUTO,
            // Pumpkin ships its own defaults; keep Paper/Vanilla conservative for phones.
            viewDistance = if (type == ServerType.PUMPKIN) 10 else 6,
            simulationDistance = if (type == ServerType.PUMPKIN) 8 else 4,
        )
        val c = customize(base).copy(id = base.id)
        _servers.update { it + c }; persist()
        return c
    }

    fun delete(id: String) {
        forceStop(id)
        _servers.update { l -> l.filterNot { it.id == id } }; persist()
        _states.update { it - id }; logs.remove(id)
        File(root, id).deleteRecursively()
        File(app.filesDir, "backups/$id").deleteRecursively()
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

    /**
     * Kills the process immediately. While a process is alive the run coroutine is deliberately NOT cancelled: it is the one
     * that notices the exit, resets the state, posts the event and releases the wake locks. Only with no process
     * (still installing) is the job cancelled.
     */
    fun forceStop(id: String) {
        val l = live[id]
        if (l != null) {
            l.stopRequested = true
            setState(id) { it.copy(state = RunState.STOPPING, message = null) }
            l.process.destroyForcibly()
        } else { jobs[id]?.cancel(); setState(id) { ServerRuntime() } }
    }
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
                java.ensure(JavaRuntime.runtimeFor(c.javaMajorOverride.takeIf { it > 0 } ?: art.javaMajor)) { setProgress(c.id, it) }
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
            val major = javaFor(c, d)
            if (!java.isInstalled(major)) java.ensure(major) { setProgress(c.id, it) }
        }
        // Minecraft 1.7-1.16.5 (vanilla) need Mojang's replacement logging config to be safe from Log4Shell.
        Log4jFix.configFor(c.type, c.version)?.let { cfg ->
            val f = File(d, cfg.fileName)
            if (!f.exists()) {
                setProgress(c.id, Progress("Downloading Log4Shell fix", null, cfg.fileName))
                Http.download(cfg.url, f, sha1 = cfg.sha1)
            }
        }
        setProgress(c.id, null)
    }

    private fun File.readTextOrNull() = runCatching { readText() }.getOrNull()

    /** The installed Java this server runs on: the user's choice, or what its Minecraft version asks for. */
    private fun javaFor(c: ServerConfig, d: File): Int = JavaRuntime.runtimeFor(
        c.javaMajorOverride.takeIf { it > 0 } ?: File(d, ".java-major").readTextOrNull()?.trim()?.toIntOrNull() ?: 21,
    )
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
            if (c.type.isJava) {
                File(d, "lapis-hosts").delete()   // left over from the old pre-resolved hosts approach
                val ns = DnsConfig.write(app, d)
                log(id, "DNS: ${ns.joinToString(", ")}", LogLine.Kind.SYSTEM)
            }
            if (c.type.isJava) {
                LaunchBuilder.writeJavaConfig(c, d)
                if (c.autoTune && c.type.supportsPlugins) {
                    val tuned = runCatching { PerfTuning.apply(c, d) }.getOrNull()
                    if (tuned != null) log(id, tuned, LogLine.Kind.SYSTEM)
                    else if (PerfTuning.firstRunPending(d)) log(id, "Performance tuning: the server writes its config files on first start; tuning applies from the next start.", LogLine.Kind.SYSTEM)
                }
            } else {
                if (!File(d, "pumpkin.toml").exists()) bootstrapPumpkin(id, d, File(d, c.jarFile))
                val applied = LaunchBuilder.patchPumpkinConfig(c, d)
                if (applied == null) log(id, "Config: pumpkin.toml was not created, so your settings could not be applied.", LogLine.Kind.WARN)
                else log(id, "Config applied to pumpkin.toml: " + applied.joinToString(", ") { it.replace(" = ", "=") }, LogLine.Kind.SYSTEM)
            }

            val cores = Runtime.getRuntime().availableProcessors()
            val plan = CoreTopology.plan(c, SystemInfo.coreScores())
            log(id, "Cores: " + describePlan(plan, cores), LogLine.Kind.SYSTEM)
            val cmd = ArrayList<String>()
            var javaEnv: Map<String, String> = emptyMap()
            cmd += LaunchBuilder.schedulingPrefix(c, plan)
            if (c.type.isJava) {
                val major = javaFor(c, d)
                cmd += java.commandPrefix(major, d)
                if (!java.dnsPatched) log(id, "DNS: could not set up name lookups for this Java runtime; online mode and plugin downloads may fail.", LogLine.Kind.WARN)
                if (!java.acceptPatched) log(id, "Network: could not apply the accept() fix; players connecting may stop servers that use Java's built-in networking.", LogLine.Kind.WARN)
                javaEnv = java.environment(major)
                log(id, "Java $major (${if (JavaRuntime.fromAlpine(major)) "OpenJDK from Alpine" else "Eclipse Temurin"})", LogLine.Kind.SYSTEM)
                cmd += LaunchBuilder.jvmArgs(c, cores, File(d, "tmp"), major, plan)
                cmd += listOf("-jar", c.jarFile, "nogui")
            } else { cmd += File(d, c.jarFile).path; javaEnv = LaunchBuilder.nativeEnv(plan) }
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

            val allowed = plan.activeCores
            val focus = if (plan.mask != 0 || plan.dedicatedTick) scope.launch {
                Affinity.enforce(pid, plan, c.type.isJava, cores) { msg ->
                    if (msg.startsWith("!")) log(id, msg.drop(1), LogLine.Kind.WARN) else log(id, msg, LogLine.Kind.SYSTEM)
                }
            } else null
            val boost = if (c.autoTune) scope.launch {
                TickBoost.run(app, pid, c.type.isJava, { runtime(id).state == RunState.RUNNING }) { msg ->
                    if (msg.startsWith("!")) log(id, msg.drop(1), LogLine.Kind.WARN) else log(id, msg, LogLine.Kind.SYSTEM)
                }
            } else null
            val metrics = scope.launch { metricsLoop(id, l, pid, allowed) }
            val vitals = scope.launch { vitalsLoop(id, l, allowed) }
            // Follow network changes (Wi-Fi to mobile data and back); the JVM re-reads the file on every lookup.
            val dnsRefresh = if (c.type.isJava) scope.launch { while (isActive) { delay(15_000L); runCatching { DnsConfig.write(app, d) } } } else null
            val readyRegex = if (c.type.isJava) Regex("""Done \(.*\)! For help""") else Regex("""(?i)(done|started|listening|ready)""")
            val fallbackReady = if (!c.type.isJava) scope.launch { delay(20_000); markRunning(id) } else null
            withContext(Dispatchers.IO) {
                forEachLineSafely(proc.inputStream) { raw ->
                    val line = ConsoleParsers.stripAnsi(raw)
                    val ours = System.currentTimeMillis() < (pollWindow[id] ?: 0L) && ConsoleParsers.isPollingOutput(line)
                    if (!ours) log(id, line, classify(line))
                    if (readyRegex.containsMatchIn(line)) markRunning(id)
                    ingest(id, line)
                }
            }
            val code = withContext(Dispatchers.IO) { proc.waitFor() }
            fallbackReady?.cancel(); focus?.cancel(); boost?.cancel(); metrics.cancel(); vitals.cancel(); dnsRefresh?.cancel(); tracked.remove(id); pollWindow.remove(id)
            live.remove(id)
            if (l.stopRequested || code == 0) {
                log(id, "-- server stopped (exit $code) --", LogLine.Kind.SYSTEM)
                postEvent(id, ServerEvent.Kind.STOPPED, "Stopped")
                setState(id) { ServerRuntime() }
            } else {
                log(id, "-- server exited unexpectedly (exit $code) --", LogLine.Kind.ERROR)
                postEvent(id, ServerEvent.Kind.CRASHED, "Stopped unexpectedly (exit $code)")
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

    private fun describePlan(p: CorePlan, total: Int): String = when {
        p.dedicatedTick -> "${p.strategy.label}: tick thread gets cpu${p.tickCore}, everything else ${CoreTopology.describe(p.restMask, total)}."
        p.mask != 0 -> "${p.strategy.label}: pinned to ${CoreTopology.describe(p.mask, total)} (${p.activeCores} of $total)."
        else -> "${p.strategy.label}: all $total cores."
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

    private fun markRunning(id: String) {
        var became = false
        setState(id) { if (it.state == RunState.STARTING) { became = true; it.copy(state = RunState.RUNNING) } else it }
        if (became) postEvent(id, ServerEvent.Kind.STARTED, "Running and ready for players")
    }

    /** Looks at every console line for the things owners care about: TPS, tick time, joins and leaves. */
    private fun ingest(id: String, line: String) {
        ConsoleParsers.parseTps(line)?.let { (t1, t5, t15) -> setState(id) { it.copy(tps = t1, tps5m = t5, tps15m = t15) } }
        ConsoleParsers.parseTickQuery(line)?.let { ms -> setState(id) { it.copy(mspt = ms, tps = ConsoleParsers.tpsFromMspt(ms)) } }
        if (System.currentTimeMillis() < (pollWindow[id] ?: 0L)) ConsoleParsers.parseMspt(line)?.let { ms ->
            if (ms < 1000f) setState(id) { it.copy(mspt = ms) }
        }
        ConsoleParsers.parsePlayerEvent(line)?.let { e ->
            val set = tracked.getOrPut(id) { Collections.newSetFromMap(ConcurrentHashMap()) }
            if (e.presence == ConsoleParsers.Presence.JOINED) { set += e.name; postEvent(id, ServerEvent.Kind.JOIN, "${e.name} joined") }
            else { set -= e.name; postEvent(id, ServerEvent.Kind.LEAVE, "${e.name} left") }
            setState(id) { it.copy(players = set.toList().sorted(), playersOnline = maxOf(set.size, 0)) }
        }
    }

    /**
     * Every few seconds while the server is up: ping it on localhost for players/latency, ask it for TPS where it
     * has a command for that, and add a sample to the chart histories.
     */
    private suspend fun vitalsLoop(id: String, l: Live, allowedCores: Int) {
        var tick = 0
        while (currentCoroutineContext().isActive) {
            delay(5000)
            val c = config(id) ?: continue
            if (runtime(id).state != RunState.RUNNING) continue
            tick++
            val r = withContext(Dispatchers.IO) { Slp.query("127.0.0.1", c.port) }
            if (r != null) {
                val set = tracked.getOrPut(id) { Collections.newSetFromMap(ConcurrentHashMap()) }
                if (r.online == 0) set.clear() else if (r.sample.isNotEmpty() && r.sample.size >= r.online) { set.clear(); set += r.sample }
                setState(id) { it.copy(playersOnline = r.online, playersMax = r.max, pingMs = r.latencyMs, versionSeen = r.versionName, players = set.toList().sorted()) }
            }
            // Ask the server for tick health. Its reply is swallowed from the console but parsed above.
            val cmd = when {
                c.type.supportsPlugins -> "tps"
                c.type == ServerType.VANILLA && McVersion.atLeast(c.version, "1.20.3") -> "tick query"   // added in 1.20.3
                else -> null
            }
            if (cmd != null) {
                pollWindow[id] = System.currentTimeMillis() + 2500
                withContext(Dispatchers.IO) { runCatching { l.stdin.write(cmd + "\n"); l.stdin.flush() } }
                if (c.type.supportsPlugins && tick % 2 == 0 && McVersion.atLeast(c.version, "1.16")) {
                    delay(300)
                    pollWindow[id] = System.currentTimeMillis() + 2500
                    withContext(Dispatchers.IO) { runCatching { l.stdin.write("mspt\n"); l.stdin.flush() } }
                }
            }
            val world = if (tick % 6 == 1) withContext(Dispatchers.IO) { worldSize(dir(id)) } else null
            setState(id) { r0 ->
                fun <T> List<T>.pushed(v: T) = (this + v).takeLast(72)
                r0.copy(
                    worldBytes = world ?: r0.worldBytes,
                    tpsHistory = r0.tps?.let { r0.tpsHistory.pushed(it) } ?: r0.tpsHistory,
                    cpuHistory = r0.cpuHistory.pushed((r0.cpuCoresUsed / allowedCores.coerceAtLeast(1)).coerceIn(0f, 1f)),
                    ramHistory = r0.ramHistory.pushed(if (c.ramMb > 0) (r0.rssMb.toFloat() / c.ramMb).coerceIn(0f, 1.2f) else 0f),
                    playerHistory = r0.playerHistory.pushed(r0.playersOnline.toFloat()),
                )
            }
        }
    }

    private fun worldSize(d: File): Long =
        d.listFiles { f -> f.isDirectory && (f.name.startsWith("world") || f.name == "DIM-1" || f.name == "DIM1") }.orEmpty().sumOf { it.sizeRecursive() }

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
