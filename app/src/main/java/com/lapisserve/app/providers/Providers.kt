package com.lapisserve.app.providers

import com.lapisserve.app.core.Arch
import com.lapisserve.app.core.Http
import com.lapisserve.app.model.ServerType
import com.lapisserve.app.model.VersionEntry

/** A downloadable server artifact, fully resolved from the upstream API. */
data class Artifact(
    val url: String,
    val fileName: String,
    val sha256: String? = null,
    val sha1: String? = null,
    val sizeBytes: Long = 0,
    /** Java major version this server needs, or 0 for native servers. */
    val javaMajor: Int = 0,
    val md5: String? = null,
)

object Providers {
    private const val MOJANG = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private const val PAPER = "https://fill.papermc.io/v3/projects/paper"
    private const val PURPUR = "https://api.purpurmc.org/v2/purpur"
    private const val PUMPKIN = "https://api.github.com/repos/Pumpkin-MC/Pumpkin/releases?per_page=20"

    private var mojangCache: org.json.JSONObject? = null

    private suspend fun mojang(): org.json.JSONObject =
        mojangCache ?: Http.json(MOJANG).also { mojangCache = it }

    suspend fun versions(type: ServerType): List<VersionEntry> = when (type) {
        ServerType.VANILLA -> {
            val arr = mojang().getJSONArray("versions")
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .map { VersionEntry(it.getString("id"), it.getString("type")) }
        }
        ServerType.PAPER -> {
            val groups = Http.json(PAPER).getJSONObject("versions")
            val out = ArrayList<VersionEntry>()
            // Groups are ordered newest-first as the API returns them.
            groups.keys().forEach { g ->
                val a = groups.getJSONArray(g)
                for (i in 0 until a.length()) {
                    val v = a.getString(i)
                    val pre = v.contains("-rc") || v.contains("-pre")
                    out += VersionEntry(v, if (pre) "snapshot" else "release")
                }
            }
            out.sortedWith(compareByDescending<VersionEntry> { versionKey(it.id) })
        }
        ServerType.PURPUR -> {
            val arr = Http.json(PURPUR).getJSONArray("versions")
            (0 until arr.length()).map { arr.getString(it) }
                .map { VersionEntry(it, "release") }.sortedByDescending { versionKey(it.id) }
        }
        ServerType.PUMPKIN -> {
            val arr = Http.jsonArray(PUMPKIN)
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { r -> r.getJSONArray("assets").let { a -> (0 until a.length()).any { a.getJSONObject(it).getString("name") == assetFor(Arch.ARM64) } } }
                .map { VersionEntry(it.getString("tag_name"), if (it.optBoolean("prerelease")) "snapshot" else "release") }
        }
    }

    suspend fun resolve(type: ServerType, version: String): Artifact = when (type) {
        ServerType.VANILLA -> {
            val entry = (0 until mojang().getJSONArray("versions").length())
                .map { mojang().getJSONArray("versions").getJSONObject(it) }
                .firstOrNull { it.getString("id") == version } ?: error("Unknown Minecraft version $version")
            val meta = Http.json(entry.getString("url"))
            val server = meta.getJSONObject("downloads").optJSONObject("server")
                ?: error("Mojang published no server jar for $version")
            Artifact(
                url = server.getString("url"), fileName = "server.jar",
                sha1 = server.optString("sha1").ifEmpty { null }, sizeBytes = server.optLong("size"),
                javaMajor = meta.optJSONObject("javaVersion")?.optInt("majorVersion") ?: heuristicJava(version),
            )
        }
        ServerType.PAPER -> {
            val b = Http.json("$PAPER/versions/$version/builds/latest")
            val d = b.getJSONObject("downloads").getJSONObject("server:default")
            Artifact(
                url = d.getString("url"), fileName = "server.jar",
                sha256 = d.optJSONObject("checksums")?.optString("sha256")?.ifEmpty { null },
                sizeBytes = d.optLong("size"),
                javaMajor = vanillaJava(version) ?: heuristicJava(version),
            )
        }
        ServerType.PURPUR -> {
            val b = Http.json("$PURPUR/$version/latest")
            val build = b.getString("build")
            Artifact(
                url = "$PURPUR/$version/$build/download", fileName = "server.jar",
                md5 = b.optString("md5").ifEmpty { null },
                javaMajor = vanillaJava(version) ?: heuristicJava(version),
            )
        }
        ServerType.PUMPKIN -> {
            val arch = Arch.current
            require(arch == Arch.ARM64) {
                "Pumpkin only publishes an Android build for ARM64 devices. This device is ${arch?.name ?: "unsupported"}."
            }
            val rel = Http.json("https://api.github.com/repos/Pumpkin-MC/Pumpkin/releases/tags/" + java.net.URLEncoder.encode(version, "UTF-8"))
            val assets = rel.getJSONArray("assets")
            val list = (0 until assets.length()).map { assets.getJSONObject(it) }
            val bin = list.firstOrNull { it.getString("name") == assetFor(arch) } ?: error("Release $version has no Android build")
            val sums = list.firstOrNull { it.getString("name") == "checksums.sha256" }
                ?.let { runCatching { Http.text(it.getString("browser_download_url")) }.getOrNull() }
            val sha = sums?.lines()?.firstOrNull { it.trim().endsWith(bin.getString("name")) }?.trim()?.substringBefore(' ')
            Artifact(
                url = bin.getString("browser_download_url"), fileName = "pumpkin",
                sha256 = sha, sizeBytes = bin.optLong("size"),
            )
        }
    }

    private fun assetFor(arch: Arch) = when (arch) { Arch.ARM64 -> "pumpkin-aarch64-android"; Arch.X64 -> "pumpkin-x86_64-android" }

    private suspend fun vanillaJava(version: String): Int? = runCatching {
        val arr = mojang().getJSONArray("versions")
        val e = (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.getString("id") == version.substringBefore("-rc").substringBefore("-pre") }
            ?: return@runCatching null
        Http.json(e.getString("url")).optJSONObject("javaVersion")?.optInt("majorVersion")
    }.getOrNull()

    /** Used only when Mojang metadata is unavailable. */
    private fun heuristicJava(v: String): Int {
        val p = v.split('.', '-').mapNotNull { it.toIntOrNull() }
        val major = p.getOrNull(0) ?: 1
        if (major >= 26) return 25
        val minor = p.getOrNull(1) ?: 0
        val patch = p.getOrNull(2) ?: 0
        return when {
            minor > 20 || (minor == 20 && patch >= 5) -> 21
            minor >= 18 -> 17
            minor == 17 -> 16.coerceAtLeast(17)
            else -> 8
        }
    }

    private fun versionKey(v: String): Long {
        val p = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val pre = if (v.contains('-')) 0 else 1
        return ((p.getOrElse(0) { 0 } * 1000L + p.getOrElse(1) { 0 }) * 1000L + p.getOrElse(2) { 0 }) * 10 + pre
    }
}
