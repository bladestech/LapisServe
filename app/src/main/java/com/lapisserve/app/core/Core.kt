package com.lapisserve.app.core

import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** CPU architectures LapisServe knows how to fetch runtimes for. */
enum class Arch(val adoptium: String, val alpine: String, val muslLoader: String) {
    ARM64("aarch64", "aarch64", "ld-musl-aarch64.so.1"),
    X64("x64", "x86_64", "ld-musl-x86_64.so.1");

    companion object {
        val current: Arch? by lazy {
            Build.SUPPORTED_ABIS.firstNotNullOfOrNull {
                when (it) { "arm64-v8a" -> ARM64; "x86_64" -> X64; else -> null }
            }
        }
    }
}

object Http {
    private const val UA = "bladestech/LapisServe/0.3.0 (+https://github.com/bladestech/LapisServe)"

    private fun open(url: String): HttpURLConnection {
        var u = url
        repeat(6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", UA)
            c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location")).toString(); c.disconnect() }
            else if (code in 200..299) return c
            else { c.disconnect(); throw java.io.IOException("HTTP $code for $url") }
        }
        throw java.io.IOException("Too many redirects: $url")
    }

    suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        val c = open(url); try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }
    suspend fun json(url: String) = JSONObject(text(url))
    suspend fun bytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val c = open(url); try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }
    suspend fun jsonArray(url: String) = JSONArray(text(url))

    /** Streams [url] to [dest] (via a .part file), reporting progress and optionally verifying a hash. */
    suspend fun download(
        url: String, dest: File, sha256: String? = null, sha1: String? = null,
        md5: String? = null, sha512: String? = null,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        val c = open(url)
        try {
            val total = c.contentLengthLong
            val md = when {
                sha512 != null -> MessageDigest.getInstance("SHA-512")
                sha256 != null -> MessageDigest.getInstance("SHA-256")
                sha1 != null -> MessageDigest.getInstance("SHA-1")
                md5 != null -> MessageDigest.getInstance("MD5")
                else -> null
            }
            var done = 0L; var last = 0L
            c.inputStream.use { ins ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); md?.update(buf, 0, n); done += n
                        val now = System.nanoTime()
                        if (now - last > 80_000_000L) { last = now; onProgress(done, total) }
                    }
                }
            }
            onProgress(done, total)
            if (md != null) {
                val got = md.digest().joinToString("") { "%02x".format(it) }
                val want = (sha512 ?: sha256 ?: sha1 ?: md5)!!.lowercase()
                if (got != want) { part.delete(); throw java.io.IOException("Checksum mismatch for ${dest.name}") }
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw java.io.IOException("Could not move download into place")
        } catch (t: Throwable) {
            part.delete(); throw t
        } finally { c.disconnect() }
    }
}

object Archive {
    /** Extracts a .tar.gz, preserving symlinks and the executable bit. [strip] removes leading path components. */
    suspend fun untarGz(src: File, dest: File, strip: Int = 0, filter: (String) -> Boolean = { true }) = withContext(Dispatchers.IO) {
        dest.mkdirs()
        val root = dest.canonicalPath
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(src.inputStream()), true)).use { tar ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val e = tar.nextEntry ?: break
                val parts = e.name.split('/').filter { it.isNotEmpty() && it != "." }
                if (parts.size <= strip) continue
                val rel = parts.drop(strip).joinToString("/")
                if (!filter(rel)) continue
                val out = File(dest, rel)
                if (!out.canonicalPath.startsWith(root + File.separator)) continue // path traversal guard
                when {
                    e.isDirectory -> out.mkdirs()
                    e.isSymbolicLink -> {
                        out.parentFile?.mkdirs(); out.delete()
                        runCatching { Os.symlink(e.linkName, out.path) }
                    }
                    e.isFile -> {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { tar.copyTo(it) }
                        val exec = e.mode and 73 != 0   // any x bit
                        runCatching { Os.chmod(out.path, if (exec) 493 else 420) } // 0755 / 0644
                    }
                }
            }
        }
    }
}

fun File.sizeRecursive(): Long = if (isFile) length() else (listFiles()?.sumOf { it.sizeRecursive() } ?: 0L)

fun Long.humanBytes(): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = this.toDouble(); var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "$this B" else "%.1f %s".format(v, units[i])
}

fun Int.humanMb(): String =
    if (this >= 1024) "%.1f GB".format(this / 1024.0).replace(".0 GB", " GB") else "$this MB"
