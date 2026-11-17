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
 * Android's libc (bionic) cannot run stock Linux JVMs, so we use JVMs built for musl and fetch, at first use:
 *  1. A tiny musl C runtime (musl, libgcc, libstdc++, zlib) from Alpine's official package repository.
 *  2. The JVM itself:
 *     - Java 21 and 25: Eclipse Temurin for Linux/musl from Adoptium (the only versions Adoptium builds for ARM64 musl).
 *     - Java 8, 11 and 17 (Minecraft 1.20.4 and older): OpenJDK from Alpine's official repository.
 * The JVM is started through the musl dynamic loader, which runs happily alongside bionic. Our copy of the loader is
 * patched to read DNS settings from the server folder (see [MuslPatch]).
 */
class JavaRuntime(private val context: Context) {
    private val root = File(context.filesDir, "runtimes")
    private val muslDir = File(root, "musl")

    companion object {
        const val ALPINE = "https://dl-cdn.alpinelinux.org/alpine/v3.22"

        /** Java versions LapisServe can install. */
        val CHOICES = listOf(8, 11, 17, 21, 25)

        /** The installable Java that runs a server needing [required] (e.g. Minecraft 1.17 asks for 16; 17 runs it). */
        fun runtimeFor(required: Int): Int = when {
            required <= 8 -> 8
            required <= 11 -> 11
            required <= 17 -> 17
            required <= 21 -> 21
            else -> 25
        }

        fun fromAlpine(major: Int) = major < 21

        private fun alpinePackage(major: Int) = when (major) {
            8 -> "openjdk8-jre-base"; 11 -> "openjdk11-jre-headless"; 17 -> "openjdk17-jre-headless"
            else -> error("No Alpine package for Java $major")
        }

        /** Already provided by the musl runtime, or only needed by Alpine's install scripts. */
        private val SKIP = setOf(
            "musl", "libgcc", "libstdc++", "zlib", "busybox", "busybox-binsh", "java-common", "java-cacerts",
            "p11-kit", "p11-kit-trust", "ca-certificates", "ca-certificates-bundle",
        )
    }

    private fun dir(major: Int) = File(root, if (fromAlpine(major)) "openjdk-$major" else "temurin-$major")

    /** The JVM's home directory (the one containing bin/java). */
    fun home(major: Int): File {
        val d = dir(major)
        if (!fromAlpine(major)) return d
        val jvms = File(d, "usr/lib/jvm").listFiles().orEmpty().filter { it.isDirectory }
        for (j in jvms) {
            if (File(j, "jre/bin/java").exists()) return File(j, "jre")   // Java 8 keeps the runtime under jre/
            if (File(j, "bin/java").exists()) return j
        }
        return File(d, "missing")
    }

    fun isInstalled(major: Int) = File(home(major), "bin/java").exists() && muslReady()
    private fun muslReady(): Boolean { val a = Arch.current ?: return false; return File(muslDir, "lib/${a.muslLoader}").exists() }

    fun installed(): List<Pair<Int, Long>> =
        root.listFiles { f -> f.isDirectory && (f.name.startsWith("temurin-") || f.name.startsWith("openjdk-")) }.orEmpty()
            .mapNotNull { f -> f.name.substringAfter('-').toIntOrNull()?.let { it to f.sizeRecursive() } }.sortedBy { it.first }

    fun muslSize(): Long = muslDir.sizeRecursive()

    fun remove(major: Int) { dir(major).deleteRecursively() }

    /** True once the loader has been patched to read the server folder's DNS settings. */
    @Volatile var dnsPatched = false
        private set

    /** True once accept() has been switched to the accept4 syscall that Android allows (see [MuslPatch]). */
    @Volatile var acceptPatched = false
        private set

    private var patchedCache: Pair<Long, ByteArray>? = null
    private fun patchedLoader(arch: Arch): ByteArray {
        val src = File(muslDir, "lib/${arch.muslLoader}")
        patchedCache?.let { (len, bytes) -> if (len == src.length()) return bytes }
        val r = MuslPatch.patch(src.readBytes())
        dnsPatched = r.dns; acceptPatched = r.accept
        val bytes = r.bytes
        patchedCache = src.length() to bytes
        return bytes
    }

    /**
     * Command prefix that launches `java` through the (patched) musl loader.
     *
     * The JVM launcher derives its home directory from /proc/self/exe. When started via a loader that is
     * the loader itself, so it would conclude its libraries are missing and re-exec forever. Placing a
     * copy of the loader inside <home>/bin makes that lookup resolve to the real JVM.
     */
    fun commandPrefix(major: Int, serverDir: File): List<String> {
        val arch = Arch.current ?: error("Unsupported CPU architecture")
        val home = home(major)
        val loader = File(home, "bin/${arch.muslLoader}")
        val bytes = patchedLoader(arch)
        if (!loader.exists() || loader.length() != bytes.size.toLong() || !loader.readBytes().contentEquals(bytes)) {
            loader.delete(); loader.writeBytes(bytes); loader.setExecutable(true, false)
        }
        val java = File(home, "bin/java")
        if (!fromAlpine(major)) return listOf(loader.path, java.path)
        TrustStore.fixSecurityConfig(home)   // runtimes installed before this fix existed
        // Alpine's launchers re-exec themselves, so `java` must be started directly with its own interpreter (see ElfInterp).
        val b = java.readBytes()
        if (ElfInterp.read(b) != ElfInterp.LOCAL) {
            val patched = ElfInterp.withInterp(b, ElfInterp.LOCAL) ?: error("Could not prepare Java $major's launcher")
            val tmp = File(java.path + ".tmp"); tmp.writeBytes(patched); tmp.setExecutable(true, false)
            if (!tmp.renameTo(java)) error("Could not prepare Java $major's launcher")
        }
        val link = File(serverDir, ElfInterp.LOCAL)
        val current = runCatching { android.system.Os.readlink(link.path) }.getOrNull()
        if (current != loader.path) {
            link.delete()
            android.system.Os.symlink(loader.path, link.path)
        }
        return listOf(java.path)
    }

    /** Library search path; must include the JVM's own lib directories exactly so the launcher doesn't re-exec. */
    fun environment(major: Int): Map<String, String> {
        val arch = Arch.current ?: error("Unsupported CPU architecture")
        val home = home(major)
        val java8 = File(home, "lib/${arch.jdk8}/server")
        val jvm = if (java8.exists()) listOf(java8, File(home, "lib/${arch.jdk8}"), File(home, "lib/${arch.jdk8}/jli"))
            else listOf(File(home, "lib/server"), File(home, "lib"), File(home, "lib/jli"))
        val extra = if (fromAlpine(major)) listOf(File(dir(major), "usr/lib"), File(dir(major), "lib")) else emptyList()
        val paths = jvm + extra + listOf(File(muslDir, "lib"), File(muslDir, "usr/lib"))
        return mapOf("LD_LIBRARY_PATH" to paths.joinToString(":") { it.path })
    }

    suspend fun ensure(major: Int, report: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        val arch = Arch.current ?: error("This device's CPU (${android.os.Build.SUPPORTED_ABIS.joinToString()}) is not supported")
        if (isInstalled(major)) return@withContext
        root.mkdirs()
        if (!muslReady()) installMusl(arch, report)
        if (fromAlpine(major)) installAlpineJava(major, arch, report) else installTemurin(major, arch, report)
    }

    private suspend fun installTemurin(major: Int, arch: Arch, report: (Progress) -> Unit) {
        report(Progress("Looking up Java $major", null, "Adoptium API"))
        val url = "https://api.adoptium.net/v3/assets/latest/$major/hotspot?architecture=${arch.adoptium}&image_type=jre&os=alpine-linux"
        val arr = Http.jsonArray(url)
        if (arr.length() == 0) error("Adoptium has no Java $major build for ${arch.adoptium}/musl")
        val pkg = arr.getJSONObject(0).getJSONObject("binary").getJSONObject("package")
        val tmp = File(root, "temurin-$major.tar.gz")
        Http.download(pkg.getString("link"), tmp, sha256 = pkg.optString("checksum").ifEmpty { null }) { d, t ->
            report(Progress("Downloading Java $major", if (t > 0) d.toFloat() / t else null, "${d.humanBytes()} of ${t.humanBytes()}"))
        }
        report(Progress("Unpacking Java $major", null, "This can take a minute"))
        val dest = File(root, "temurin-$major.tmp").also { it.deleteRecursively() }
        Archive.untarGz(tmp, dest, strip = 1)
        tmp.delete()
        dir(major).deleteRecursively()
        if (!dest.renameTo(dir(major))) error("Could not finalize Java $major install")
    }

    /** OpenJDK 8/11/17 from Alpine's community repository, with the libraries it links against. */
    private suspend fun installAlpineJava(major: Int, arch: Arch, report: (Progress) -> Unit) {
        report(Progress("Looking up Java $major", null, "Alpine package index"))
        val index = AlpineIndex.load(root, arch, listOf("main", "community"))
        val pkgs = index.closure(alpinePackage(major), SKIP)
        val total = pkgs.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L
        val tmp = File(root, "openjdk-$major.tmp").also { it.deleteRecursively() }
        for (p in pkgs) {
            val apk = File(root, "${p.name}.apk")
            Http.download("$ALPINE/${p.repo}/${arch.alpine}/${p.name}-${p.version}.apk", apk) { d, _ ->
                report(Progress("Downloading Java $major", (done + d).toFloat() / total, "${p.name} · ${(done + d).humanBytes()} of ${total.humanBytes()}"))
            }
            done += p.size
            Archive.untarGz(apk, tmp, filter = { !it.startsWith(".") })
            apk.delete()
        }
        report(Progress("Setting up Java $major", null, "Trusted certificates"))
        val home = File(tmp, "usr/lib/jvm").listFiles().orEmpty().firstOrNull { it.isDirectory }
            ?.let { if (File(it, "jre/bin/java").exists()) File(it, "jre") else it } ?: error("Java $major package has no JVM")
        TrustStore.writeJks(TrustStore.androidCertificates(), File(home, "lib/security/cacerts"))
        TrustStore.fixSecurityConfig(home)
        dir(major).deleteRecursively()
        if (!tmp.renameTo(dir(major))) error("Could not finalize Java $major install")
    }

    private suspend fun installMusl(arch: Arch, report: (Progress) -> Unit) {
        report(Progress("Looking up C runtime", null, "Alpine package index"))
        val index = AlpineIndex.load(root, arch, listOf("main"))
        val wanted = listOf("musl", "libgcc", "libstdc++", "zlib")
        val tmpDir = File(root, "musl.tmp").also { it.deleteRecursively() }
        wanted.forEachIndexed { i, name ->
            val p = index.packages[name] ?: error("Alpine index has no package '$name'")
            val apk = File(root, "$name.apk")
            Http.download("$ALPINE/${p.repo}/${arch.alpine}/${p.name}-${p.version}.apk", apk) { d, t ->
                report(Progress("Downloading C runtime", (i + (if (t > 0) d.toFloat() / t else 0f)) / wanted.size, "$name ${p.version}"))
            }
            Archive.untarGz(apk, tmpDir, filter = { !it.startsWith(".") })
            apk.delete()
        }
        muslDir.deleteRecursively()
        if (!tmpDir.renameTo(muslDir)) error("Could not finalize C runtime")
    }
}
