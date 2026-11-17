package com.lapisserve.app.runtime

import com.lapisserve.app.core.Arch
import com.lapisserve.app.core.Http
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.InputStream

/** One package from an Alpine APKINDEX. */
data class AlpinePkg(
    val name: String, val version: String, val repo: String, val size: Long,
    val depends: List<String>, val provides: List<String>,
)

/** Reads Alpine package indexes and resolves dependency closures. */
class AlpineIndex(val packages: Map<String, AlpinePkg>) {
    private val providers: Map<String, AlpinePkg> = buildMap {
        packages.values.forEach { p -> p.provides.forEach { putIfAbsent(it, p) } }
    }

    /** [root] plus everything it depends on, in install order, leaving out names in [skip]. */
    fun closure(root: String, skip: Set<String>): List<AlpinePkg> {
        val out = LinkedHashMap<String, AlpinePkg>()
        fun visit(token: String) {
            val p = packages[token] ?: providers[token] ?: return   // e.g. "/bin/sh": provided by something we skip
            if (p.name in skip || p.name in out) return
            out[p.name] = p
            p.depends.forEach(::visit)
        }
        visit(root)
        return out.values.toList()
    }

    companion object {
        /** Strips version constraints: "so:libz.so.1=1.3" -> "so:libz.so.1", "nss>=3.1" -> "nss". */
        fun bareName(token: String) = token.split('=', '<', '>', '~').first()

        fun parse(text: String, repo: String): List<AlpinePkg> {
            val out = ArrayList<AlpinePkg>()
            val f = HashMap<Char, String>()
            fun flush() {
                val name = f['P']; val ver = f['V']
                if (name != null && ver != null) out += AlpinePkg(
                    name, ver, repo, f['S']?.toLongOrNull() ?: 0L,
                    f['D'].orEmpty().split(' ').filter { it.isNotBlank() && !it.startsWith('!') }.map(::bareName),
                    f['p'].orEmpty().split(' ').filter { it.isNotBlank() }.map(::bareName),
                )
                f.clear()
            }
            text.lineSequence().forEach { line ->
                if (line.isBlank()) flush() else if (line.length > 2 && line[1] == ':') f[line[0]] = line.substring(2)
            }
            flush()
            return out
        }

        suspend fun load(workDir: File, arch: Arch, repos: List<String>): AlpineIndex {
            val all = LinkedHashMap<String, AlpinePkg>()
            for (repo in repos) {
                val f = File(workDir, "APKINDEX-$repo.tar.gz")
                Http.download("${JavaRuntime.ALPINE}/$repo/${arch.alpine}/APKINDEX.tar.gz", f)
                val text = f.inputStream().use(::readApkIndex)
                f.delete()
                parse(text, repo).forEach { all.putIfAbsent(it.name, it) }
            }
            return AlpineIndex(all)
        }

        private fun readApkIndex(input: InputStream): String {
            TarArchiveInputStream(GzipCompressorInputStream(input.buffered(), true)).use { tar ->
                while (true) {
                    val e = tar.nextEntry ?: break
                    if (e.name == "APKINDEX") return tar.bufferedReader().readText()
                }
            }
            error("APKINDEX missing from index archive")
        }
    }
}
