package com.lapisserve.app.modrinth

import com.lapisserve.app.core.Http
import org.json.JSONObject
import java.io.File

data class InstalledPlugin(
    val file: File, val name: String, val enabled: Boolean,
    val projectId: String?, val versionId: String?,
)

/**
 * Manages the jars in a server's plugins/ folder. Installs from Modrinth are tracked in a small
 * metadata file so we can show "Installed" / "Update" and replace old versions cleanly.
 * Disabled plugins keep their jar but get a ".disabled" suffix, which Bukkit-style servers ignore.
 */
class PluginStore(private val dir: File) {
    private val metaFile = File(dir, ".lapis-plugins.json")

    private fun meta(): JSONObject = runCatching { JSONObject(metaFile.readText()) }.getOrElse { JSONObject() }
    private fun saveMeta(m: JSONObject) { dir.mkdirs(); metaFile.writeText(m.toString(2)) }
    private fun baseName(f: File) = f.name.removeSuffix(".disabled")

    fun installed(): List<InstalledPlugin> {
        val m = meta()
        val byFile = HashMap<String, Pair<String, JSONObject>>()
        m.keys().forEach { pid -> val o = m.getJSONObject(pid); byFile[o.optString("file")] = pid to o }
        return dir.listFiles { f -> f.isFile && (f.name.endsWith(".jar") || f.name.endsWith(".jar.disabled")) }.orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { f ->
                val tracked = byFile[baseName(f)]
                InstalledPlugin(
                    file = f, name = tracked?.second?.optString("title")?.ifEmpty { null } ?: baseName(f).removeSuffix(".jar"),
                    enabled = !f.name.endsWith(".disabled"), projectId = tracked?.first, versionId = tracked?.second?.optString("versionId"),
                )
            }
    }

    fun installedProjectIds(): Map<String, String> {
        val m = meta(); val out = HashMap<String, String>()
        m.keys().forEach { out[it] = m.getJSONObject(it).optString("versionId") }
        return out
    }

    fun setEnabled(p: InstalledPlugin, enabled: Boolean) {
        val target = File(dir, if (enabled) baseName(p.file) else baseName(p.file) + ".disabled")
        if (target != p.file) p.file.renameTo(target)
    }

    fun remove(p: InstalledPlugin) {
        p.file.delete()
        p.projectId?.let { val m = meta(); m.remove(it); saveMeta(m) }
    }

    /**
     * Installs the newest compatible version of [projectId] plus any required dependencies.
     * Returns the titles installed. Throws if no compatible version exists for [mcVersion].
     */
    suspend fun install(
        projectId: String, title: String?, mcVersion: String, loaders: List<String>,
        status: (String) -> Unit = {}, depth: Int = 0,
    ): List<String> {
        require(depth <= 3) { "Dependency chain too deep" }
        val name = title ?: Modrinth.projectTitle(projectId)
        status("Finding $name")
        val v = Modrinth.latestVersion(projectId, mcVersion, loaders)
            ?: error("$name has no version for Minecraft ${Modrinth.gameVersion(mcVersion)}")
        val done = ArrayList<String>()
        val have = installedProjectIds()
        for (dep in v.requiredDeps) if (dep !in have) done += install(dep, null, mcVersion, loaders, status, depth + 1)

        dir.mkdirs()
        val dest = File(dir, v.fileName)
        status("Downloading $name ${v.number}")
        Http.download(v.url, dest, sha512 = v.sha512)
        val m = meta()
        // Replace an older jar of the same project.
        m.optJSONObject(projectId)?.optString("file")?.takeIf { it.isNotEmpty() && it != v.fileName }?.let {
            File(dir, it).delete(); File(dir, "$it.disabled").delete()
        }
        m.put(projectId, JSONObject().put("file", v.fileName).put("versionId", v.id).put("title", name))
        saveMeta(m)
        done += name
        return done
    }
}
