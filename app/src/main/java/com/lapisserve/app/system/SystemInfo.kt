package com.lapisserve.app.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.net.NetworkInterface

/** A point-in-time view of device memory, used by the RAM slider and tuning screen. */
data class MemorySnapshot(
    val totalMb: Int,
    val availableMb: Int,
    val lowMemory: Boolean,
) {
    val usedMb get() = totalMb - availableMb
    /** Hard ceiling LapisServe will let you allocate. */
    val maxAllocMb get() = (totalMb * 0.90).toInt()
    /** Up to here the allocation is considered safe. */
    val comfortMb get() = (totalMb * 0.55).toInt()
    /** Past here Android will start killing things. Shown as the red zone. */
    val riskyMb get() = (totalMb * 0.75).toInt()
    /** What can be given to a server without evicting other apps. */
    val freeNowMb get() = availableMb
}

data class DeviceInfo(
    val model: String, val androidVersion: String, val sdk: Int, val abi: String,
    val cores: Int, val batteryPct: Int, val charging: Boolean, val thermal: String,
    val ipAddresses: List<String>,
    val processor: String = "Unknown processor",
    val coreLayout: String = "",
)

object SystemInfo {
    fun memory(ctx: Context): MemorySnapshot {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val avail = readMemAvailableMb() ?: (mi.availMem / 1048576).toInt()
        return MemorySnapshot((mi.totalMem / 1048576).toInt(), avail, mi.lowMemory)
    }

    /** /proc/meminfo's MemAvailable counts reclaimable cache, which matches what Android can really hand out. */
    private fun readMemAvailableMb(): Int? = runCatching {
        File("/proc/meminfo").useLines { ls -> ls.first { it.startsWith("MemAvailable:") } }
            .split(Regex("\\s+"))[1].toLong().div(1024).toInt()
    }.getOrNull()

    fun memoryFlow(ctx: Context, everyMs: Long = 2000): Flow<MemorySnapshot> = flow {
        while (true) { emit(memory(ctx)); delay(everyMs) }
    }

    fun device(ctx: Context): DeviceInfo {
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return DeviceInfo(
            model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE, sdk = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?", cores = Runtime.getRuntime().availableProcessors(),
            batteryPct = if (level >= 0) level * 100 / scale else -1, charging = charging,
            thermal = thermal(ctx), ipAddresses = localIps(),
            processor = processorName(), coreLayout = coreLayout(),
        )
    }

    private fun thermal(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 29) return "Unknown"
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Cool"
            PowerManager.THERMAL_STATUS_LIGHT -> "Warm"
            PowerManager.THERMAL_STATUS_MODERATE -> "Throttling"
            PowerManager.THERMAL_STATUS_SEVERE -> "Hot"
            PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "Critical"
            else -> "Unknown"
        }
    }

    fun localIps(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filter { it is java.net.Inet4Address && !it.isLoopbackAddress }.map { it.hostAddress ?: "" } }
            .filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    /**
     * Best-effort chip name. Android 12+ exposes the SoC maker and model (e.g. "Qualcomm SM7550"); older
     * versions fall back to /proc/cpuinfo or the hardware codename. Marketing names (Snapdragon 7 Gen 3)
     * are not available to apps, so the model code is what we can show.
     */
    fun processorName(): String {
        val parts = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            val maker = Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
            val model = Build.SOC_MODEL.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
            if (maker != null) parts += maker
            if (model != null) parts += model
        }
        if (parts.isEmpty()) {
            val fromCpuInfo = runCatching {
                File("/proc/cpuinfo").readLines()
                    .firstOrNull { it.startsWith("Hardware", true) || it.startsWith("model name", true) }
                    ?.substringAfter(':')?.trim()
            }.getOrNull()
            parts += (fromCpuInfo?.takeIf { it.isNotBlank() } ?: Build.HARDWARE.takeIf { it.isNotBlank() } ?: "Unknown processor")
        }
        val raw = parts.joinToString(" ")
        // "QTI" is Qualcomm Technologies Inc. Map well-known Snapdragon model codes to their marketing names.
        val code = Regex("""SM\d{4}""").find(raw)?.value
        val friendly = code?.let { snapdragonNames[it] }
        return if (friendly != null) "$friendly ($code)" else raw.replace("QTI", "Qualcomm")
    }

    private val snapdragonNames = mapOf(
        "SM8250" to "Snapdragon 865", "SM8350" to "Snapdragon 888", "SM8450" to "Snapdragon 8 Gen 1",
        "SM8475" to "Snapdragon 8+ Gen 1", "SM8550" to "Snapdragon 8 Gen 2", "SM8650" to "Snapdragon 8 Gen 3",
        "SM8750" to "Snapdragon 8 Elite", "SM7325" to "Snapdragon 778G", "SM7450" to "Snapdragon 7 Gen 1",
        "SM7475" to "Snapdragon 7+ Gen 2", "SM7550" to "Snapdragon 7 Gen 3",
    )

    /** e.g. "1x 3.2 GHz + 3x 2.8 GHz + 4x 2.0 GHz", grouping cores by max frequency; empty if unreadable. */
    fun coreLayout(): String {
        val freqs = coreMaxFreqs().filterNotNull()
        if (freqs.isEmpty()) return ""
        return freqs.groupingBy { it }.eachCount().toSortedMap(compareByDescending { it })
            .entries.joinToString(" + ") { (khz, n) -> "${n}x %.1f GHz".format(khz / 1_000_000.0) }
    }

    /**
     * Relative speed of each core for planning: the kernel's cpu_capacity (which accounts for
     * micro-architecture, not just clock) when every core exposes it, otherwise max frequency.
     */
    fun coreScores(): List<Long?> {
        val n = Runtime.getRuntime().availableProcessors()
        val caps = (0 until n).map { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpu_capacity").readText().trim().toLong() }.getOrNull()
        }
        return if (caps.all { it != null && it > 0 }) caps else coreMaxFreqs()
    }

    /** Per-core max frequency (kHz) if readable; used to label efficiency vs performance cores. */
    fun coreMaxFreqs(): List<Long?> {
        val n = Runtime.getRuntime().availableProcessors()
        return (0 until n).map { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
    }
}

// ------------------------------------------------------------------ developer-options advisor

enum class Verdict { GOOD, RECOMMENDED, UNKNOWN }

data class Advice(
    val id: String,
    val title: String,
    val why: String,
    val how: String,
    val verdict: Verdict,
    val current: String,
    val action: AdviceAction? = null,
    /** True when this setting is critical for server stability. */
    val important: Boolean = false,
)

enum class AdviceAction(val label: String) {
    OPEN_DEV_OPTIONS("Open developer options"),
    OPEN_BATTERY_EXEMPTION("Allow background use"),
    OPEN_APP_BATTERY("Open battery settings"),
    OPEN_ABOUT("Open About phone"),
}

object DevOptionsAdvisor {
    private fun globalInt(ctx: Context, key: String): Int? =
        runCatching { Settings.Global.getInt(ctx.contentResolver, key) }.getOrNull()
    private fun globalFloat(ctx: Context, key: String): Float? =
        runCatching { Settings.Global.getFloat(ctx.contentResolver, key) }.getOrNull()

    fun evaluate(ctx: Context): List<Advice> {
        val out = ArrayList<Advice>()
        val devEnabled = globalInt(ctx, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1
        out += Advice(
            "dev", "Developer options", "Unlocks the settings below that keep a server alive in the background.",
            "Settings → About phone → tap Build number 7 times.",
            if (devEnabled) Verdict.GOOD else Verdict.RECOMMENDED,
            if (devEnabled) "Enabled" else "Not enabled",
            if (devEnabled) AdviceAction.OPEN_DEV_OPTIONS else AdviceAction.OPEN_ABOUT, important = true,
        )

        // Battery optimisation exemption — the single biggest cause of "my server just stopped".
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        out += Advice(
            "battery", "Unrestricted battery use", "Doze and app standby freeze background apps, which silently pauses the server and drops players.",
            "Allow LapisServe to ignore battery optimisation.",
            if (exempt) Verdict.GOOD else Verdict.RECOMMENDED, if (exempt) "Unrestricted" else "Optimised",
            if (exempt) AdviceAction.OPEN_APP_BATTERY else AdviceAction.OPEN_BATTERY_EXEMPTION, important = true,
        )

        // Android 12+ kills child processes beyond a small limit. Android 14+ exposes a toggle for it.
        if (Build.VERSION.SDK_INT >= 31) {
            val monitor = globalInt(ctx, "settings_enable_monitor_phantom_procs")
            val ok = monitor == 0
            out += Advice(
                "phantom", "Disable child process restrictions",
                "Android 12+ kills background child processes (like the Java or Pumpkin process) once the system exceeds a small limit. The server then dies with exit code 137.",
                if (Build.VERSION.SDK_INT >= 34) "Developer options → turn on “Disable child process restrictions”."
                else "Run once from a computer: adb shell settings put global settings_enable_monitor_phantom_procs false",
                when { ok -> Verdict.GOOD; monitor == null -> Verdict.UNKNOWN; else -> Verdict.RECOMMENDED },
                when { ok -> "Disabled"; monitor == null -> "Can't be read - check manually"; else -> "Enabled (default)" },
                AdviceAction.OPEN_DEV_OPTIONS, important = true,
            )
        }

        val stayAwake = globalInt(ctx, Settings.Global.STAY_ON_WHILE_PLUGGED_IN) ?: 0
        out += Advice(
            "stayawake", "Stay awake while charging", "Keeps the screen and CPU at full speed while plugged in; the best setup for a phone that is a dedicated server.",
            "Developer options → Stay awake.",
            if (stayAwake != 0) Verdict.GOOD else Verdict.RECOMMENDED, if (stayAwake != 0) "On" else "Off",
            AdviceAction.OPEN_DEV_OPTIONS,
        )

        val animsOff = listOf(Settings.Global.WINDOW_ANIMATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.ANIMATOR_DURATION_SCALE)
            .all { (globalFloat(ctx, it) ?: 1f) == 0f }
        out += Advice(
            "anims", "Turn animations off", "Saves a little GPU and CPU time when the screen is on. Handy on mid-range phones.",
            "Developer options → set Window, Transition and Animator scale to “Animation off”.",
            if (animsOff) Verdict.GOOD else Verdict.RECOMMENDED, if (animsOff) "Off" else "On",
            AdviceAction.OPEN_DEV_OPTIONS,
        )

        val mobileAlways = globalInt(ctx, "mobile_data_always_on")
        out += Advice(
            "mobiledata", "Mobile data always active", "Lets the phone switch networks faster. Turn OFF to avoid it stealing the connection from Wi-Fi when hosting for a LAN.",
            "Developer options → Mobile data always active.",
            when (mobileAlways) { 0 -> Verdict.GOOD; null -> Verdict.UNKNOWN; else -> Verdict.RECOMMENDED },
            when (mobileAlways) { 0 -> "Off"; null -> "Unknown"; else -> "On" }, AdviceAction.OPEN_DEV_OPTIONS,
        )

        val bgLimit = globalInt(ctx, "always_finish_activities")
        out += Advice(
            "dontkeep", "Don't keep activities", "Must be OFF. When on, Android destroys the app UI the moment you leave, which makes the console lose its state.",
            "Developer options → make sure “Don't keep activities” is off.",
            when (bgLimit) { 0 -> Verdict.GOOD; null -> Verdict.UNKNOWN; else -> Verdict.RECOMMENDED },
            when (bgLimit) { 0 -> "Off"; null -> "Unknown"; else -> "On" }, AdviceAction.OPEN_DEV_OPTIONS,
        )

        out += Advice(
            "bgprocs", "Background process limit", "Leave at “Standard limit”. Lower limits make Android kill the server first.",
            "Developer options → Background process limit → Standard limit.",
            Verdict.UNKNOWN, "Cannot be read by apps", AdviceAction.OPEN_DEV_OPTIONS,
        )
        return out
    }

    fun act(ctx: Context, a: AdviceAction) {
        val i = when (a) {
            AdviceAction.OPEN_DEV_OPTIONS -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            AdviceAction.OPEN_BATTERY_EXEMPTION -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            AdviceAction.OPEN_APP_BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            AdviceAction.OPEN_ABOUT -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
