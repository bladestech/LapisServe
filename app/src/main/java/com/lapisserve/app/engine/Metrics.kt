package com.lapisserve.app.engine

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Result of a Minecraft "server list ping": works against every Java server type, with no console noise. */
data class SlpResult(
    val online: Int, val max: Int, val sample: List<String>,
    val versionName: String, val motd: String, val latencyMs: Int,
)

object Slp {
    fun query(host: String, port: Int, timeoutMs: Int = 1500): SlpResult? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            val out = DataOutputStream(s.getOutputStream())
            val inp = DataInputStream(s.getInputStream())

            // Handshake (next state = status) then a status request.
            val hs = ByteArrayOutputStream().also { b ->
                val d = DataOutputStream(b)
                writeVarInt(d, 0); writeVarInt(d, -1)
                val h = host.toByteArray(); writeVarInt(d, h.size); d.write(h)
                d.writeShort(port); writeVarInt(d, 1)
            }.toByteArray()
            writeVarInt(out, hs.size); out.write(hs)
            writeVarInt(out, 1); writeVarInt(out, 0)
            out.flush()

            readVarInt(inp)                                   // packet length
            if (readVarInt(inp) != 0) throw IOException("unexpected packet")
            val len = readVarInt(inp)
            val json = ByteArray(len).also { inp.readFully(it) }.toString(Charsets.UTF_8)

            // Optional ping/pong to measure latency; the status result is still good if this fails.
            val latency = runCatching {
                val t0 = System.nanoTime()
                writeVarInt(out, 9); writeVarInt(out, 1); out.writeLong(t0); out.flush()
                readVarInt(inp); readVarInt(inp); inp.readLong()
                ((System.nanoTime() - t0) / 1_000_000).toInt()
            }.getOrDefault(-1)
            parse(json, latency)
        }
    }.getOrNull()

    fun parse(json: String, latencyMs: Int = -1): SlpResult {
        val j = JSONObject(json)
        val p = j.optJSONObject("players")
        val sample = p?.optJSONArray("sample")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } } }.orEmpty()
        return SlpResult(
            online = p?.optInt("online") ?: 0, max = p?.optInt("max") ?: 0, sample = sample,
            versionName = j.optJSONObject("version")?.optString("name").orEmpty(),
            motd = ConsoleParsers.stripFormatting(describe(j.opt("description"))), latencyMs = latencyMs,
        )
    }

    private fun describe(d: Any?): String = when (d) {
        null -> ""
        is String -> d
        is JSONObject -> d.optString("text") + (d.optJSONArray("extra")?.let { a -> (0 until a.length()).joinToString("") { describe(a.opt(it)) } } ?: "")
        else -> d.toString()
    }

    private fun writeVarInt(o: DataOutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) { o.writeByte(v); return }
            o.writeByte(v and 0x7F or 0x80); v = v ushr 7
        }
    }

    private fun readVarInt(i: DataInputStream): Int {
        var result = 0; var shift = 0
        while (true) {
            val b = i.readUnsignedByte()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 35) throw IOException("VarInt too long")
        }
    }
}

/**
 * Reads a process's output line by line. When the process is killed (force stop, stop timeout) Android closes the stream under
 * the blocked reader and throws an IOException; that is simply the end of the output, never a reason to crash the app.
 */
fun forEachLineSafely(stream: java.io.InputStream, onLine: (String) -> Unit) {
    try { stream.bufferedReader().forEachLine(onLine) } catch (_: java.io.IOException) { /* stream closed by destroy(): treat as EOF */ }
}

/** Pure parsers for the text servers print. Kept separate so they are easy to unit test. */
object ConsoleParsers {
    private val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    private val legacy = Regex("§.")

    fun stripFormatting(s: String) = s.replace(ansi, "").replace(legacy, "").trim()

    /** "TPS from last 1m, 5m, 15m: 20.0, 19.98, *19.5" (Paper) or "... 5s, 1m, 5m, 15m: ..." (Purpur). Returns (1m, 5m, 15m). */
    fun parseTps(raw: String): Triple<Float, Float?, Float?>? {
        val line = stripFormatting(raw)
        val m = Regex("TPS from last [^:]+:\\s*(.+)$", RegexOption.IGNORE_CASE).find(line) ?: return null
        val nums = Regex("(\\d+(?:\\.\\d+)?)").findAll(m.groupValues[1]).map { it.value.toFloat() }.toList()
        return when {
            nums.size >= 4 -> Triple(nums[1], nums[2], nums[3])
            nums.size == 3 -> Triple(nums[0], nums[1], nums[2])
            nums.size == 2 -> Triple(nums[0], nums[1], null)
            nums.size == 1 -> Triple(nums[0], null, null)
            else -> null
        }.let { t -> t?.copy(first = t.first.coerceAtMost(20f)) }
    }

    /** Vanilla `tick query`: "Average time per tick: 3.5ms (Target: 50.0ms)". Returns milliseconds. */
    fun parseTickQuery(raw: String): Float? =
        Regex("Average time per tick:\\s*(\\d+(?:\\.\\d+)?)\\s*ms", RegexOption.IGNORE_CASE).find(stripFormatting(raw))?.groupValues?.get(1)?.toFloat()

    /** Paper `mspt` second line: "◴ 3.1/2.0/8.5, 3.0/1.9/9.1, ...": returns the first average. */
    fun parseMspt(raw: String): Float? =
        Regex("(\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?)").find(stripFormatting(raw))?.groupValues?.get(1)?.toFloat()

    /** Vanilla tick time to TPS: a server holds 20 TPS until a tick takes longer than 50 ms. */
    fun tpsFromMspt(ms: Float): Float = if (ms <= 50f) 20f else (1000f / ms).coerceAtMost(20f)

    enum class Presence { JOINED, LEFT }
    data class PlayerEvent(val name: String, val presence: Presence)

    fun parsePlayerEvent(raw: String): PlayerEvent? {
        val line = stripFormatting(raw)
        Regex("([A-Za-z0-9_.]{2,16}) (joined|left) the game").find(line)?.let {
            return PlayerEvent(it.groupValues[1], if (it.groupValues[2] == "joined") Presence.JOINED else Presence.LEFT)
        }
        return null
    }

    /** Lines produced by our own polling commands; these are kept out of the user's console. */
    fun isPollingOutput(raw: String): Boolean {
        val l = stripFormatting(raw)
        return l.contains("TPS from last", true) || l.contains("Server tick times", true) || l.contains("Average time per tick", true) ||
            l.contains("Target tick rate", true) || l.startsWith("Percentiles", true) || l.contains("The game is running", true) ||
            l.contains("Current Memory Usage", true) || l.contains("Unknown or incomplete command", true) ||
            l.contains("<--[HERE]") || Regex("\\d+(\\.\\d+)?/\\d+(\\.\\d+)?/\\d+(\\.\\d+)?").containsMatchIn(l)
    }
}
