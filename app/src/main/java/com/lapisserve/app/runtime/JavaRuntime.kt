package com.lapisserve.app.runtime

import android.content.Context
import com.lapisserve.app.core.Arch
import com.lapisserve.app.core.Archive
import com.lapisserve.app.core.Http
import com.lapisserve.app.core.humanBytes
import com.lapisserve.app.core.sizeRecursive
import com.lapisserve.app.model.Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads and manages Java runtimes. Nothing is bundled in the APK.
 *
 * Android's libc (bionic) cannot run stock Linux JVMs, so we fetch two things at first use:
 *  1. Eclipse Temurin (Adoptium) for Linux/musl — the official API, always the latest patch release.
 *  2. A tiny musl C runtime (musl, libgcc, libstdc++, zlib) from Alpine's official package repository.
 * The JVM is then started through the musl dynamic loader, which runs happily alongside bionic.
 */
class JavaRuntime(context: Context) {
    private val root = File(context.filesDir, "runtimes")
    private val muslDir = File(root, "musl")
    fun jreDir(major: Int) = File(root, "temurin-$major")

    fun isInstalled(major: Int) = File(jreDir(major), "bin/java").exists() && muslReady()
    private fun muslReady(): Boolean { val a = Arch.current ?: return false; return File(muslDir, "lib/${a.muslLoader}").exists() }

    fun installed(): List<Pair<Int, Long>> =
        root.listFiles { f -> f.isDirectory && f.name.startsWith("temurin-") }.orEmpty()
            .mapNotNull { f -> f.name.removePrefix("temurin-").toIntOrNull()?.let { it to f.sizeRecursive() } }.sortedBy { it.first }

    fun muslSize(): Long = muslDir.sizeRecursive()

    fun remove(major: Int) { jreDir(major).deleteRecursively() }

    /**
     * Command prefix that launches `java` through the musl loader.
     *
     * The JVM launcher derives its home directory from /proc/self/exe. When started via a loader that is
     * the loader itself, so it would conclude its libraries are missing and re-exec forever. Placing a
     * copy of the loader inside <jre>/bin makes that lookup resolve to the real JRE.
     */
    fun commandPrefix(major: Int): List<String> {
        val arch = Arch.current ?: error("Unsupported CPU architecture")
        val jre = jreDir(major)
        val loader = File(jre, "bin/${arch.muslLoader}")
        val src = File(muslDir, "lib/${arch.muslLoader}")
        if (!loader.exists() || loader.length() != src.length()) {
            src.copyTo(loader, overwrite = true); loader.setExecutable(true, false)
        }
        return listOf(loader.path, File(jre, "bin/java").path)
    }

    /** Library search path; must include <jre>/lib and <jre>/lib/server exactly so the launcher doesn't re-exec. */
    fun environment(major: Int): Map<String, String> {
        val jre = jreDir(major)
        val paths = listOf(File(jre, "lib/server"), File(jre, "lib"), File(jre, "lib/jli"), File(muslDir, "lib"), File(muslDir, "usr/lib"))
        return mapOf("LD_LIBRARY_PATH" to paths.joinToString(":") { it.path })
    }

    suspend fun ensure(major: Int, report: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        val arch = Arch.current ?: error("This device's CPU (${android.os.Build.SUPPORTED_ABIS.joinToString()}) is not supported")
        if (isInstalled(major)) return@withContext
        root.mkdirs()
        if (!muslReady()) installMusl(arch, report)
        installTemurin(major, arch, report)
    }

    private suspend fun installTemurin(major: Int, arch: Arch, report: (Progress) -> Unit) {
        report(Progress("Looking up Java $major", null, "Adoptium API"))
        val url = "https://api.adoptium.net/v3/assets/latest/$major/hotspot?architecture=${arch.adoptium}&image_type=jre&os=alpine-linux"
        val arr = Http.jsonArray(url)
        if (arr.length() == 0) error("Adoptium has no Java $major build for ${arch.adoptium}/musl")
        val pkg = arr.getJSONObject(0).getJSONObject("binary").getJSONObject("package")
        val release = arr.getJSONObject(0).optString("version_data").ifEmpty { "" }
        val tmp = File(root, "temurin-$major.tar.gz")
        Http.download(pkg.getString("link"), tmp, sha256 = pkg.optString("checksum").ifEmpty { null }) { d, t ->
            report(Progress("Downloading Java $major", if (t > 0) d.toFloat() / t else null, "${d.humanBytes()} of ${t.humanBytes()}"))
        }
        report(Progress("Unpacking Java $major", null, "This can take a minute"))
        val dest = File(root, "temurin-$major.tmp").also { it.deleteRecursively() }
        Archive.untarGz(tmp, dest, strip = 1)
        tmp.delete()
        jreDir(major).deleteRecursively()
        if (!dest.renameTo(jreDir(major))) error("Could not finalize Java $major install")
    }

    private suspend fun installMusl(arch: Arch, report: (Progress) -> Unit) {
        val base = "https://dl-cdn.alpinelinux.org/alpine/v3.22/main/${arch.alpine}/"
        report(Progress("Looking up C runtime", null, "Alpine package index"))
        val idx = File(root, "APKINDEX.tar.gz")
        Http.download(base + "APKINDEX.tar.gz", idx)
        val versions = readIndex(idx)
        idx.delete()
        val wanted = listOf("musl", "libgcc", "libstdc++", "zlib")
        val tmpDir = File(root, "musl.tmp").also { it.deleteRecursively() }
        wanted.forEachIndexed { i, name ->
            val ver = versions[name] ?: error("Alpine index has no package '$name'")
            val apk = File(root, "$name.apk")
            Http.download("$base$name-$ver.apk", apk) { d, t ->
                report(Progress("Downloading C runtime", (i + (if (t > 0) d.toFloat() / t else 0f)) / wanted.size, "$name $ver"))
            }
            Archive.untarGz(apk, tmpDir, filter = { !it.startsWith(".") })
            apk.delete()
        }
        muslDir.deleteRecursively()
        if (!tmpDir.renameTo(muslDir)) error("Could not finalize C runtime")
    }

    private fun readIndex(f: File): Map<String, String> {
        val out = HashMap<String, String>()
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(f.inputStream().buffered(), true)
        ).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                if (e.name != "APKINDEX") continue
                var p: String? = null
                tar.bufferedReader().forEachLine { line ->
                    when {
                        line.startsWith("P:") -> p = line.substring(2)
                        line.startsWith("V:") -> p?.let { out.putIfAbsent(it, line.substring(2)) }
                    }
                }
                break
            }
        }
        return out
    }
}
