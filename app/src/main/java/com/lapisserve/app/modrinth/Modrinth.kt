package com.lapisserve.app.modrinth

import com.lapisserve.app.core.Http
import com.lapisserve.app.model.ServerType
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class MrProject(
    val id: String, val slug: String, val title: String, val description: String,
    val author: String, val downloads: Long, val iconUrl: String?,
)

data class MrVersion(
    val id: String, val projectId: String, val number: String,
    val fileName: String, val url: String, val sha512: String?, val requiredDeps: List<String>,
    val name: String = number, val channel: Channel = Channel.RELEASE, val published: String = "",
    val downloads: Long = 0, val changelog: String = "", val gameVersions: List<String> = emptyList(), val sizeBytes: Long = 0,
) {
    enum class Channel(val label: String) { RELEASE("Release"), BETA("Beta"), ALPHA("Alpha") }
}

data class MrProjectInfo(
    val title: String, val description: String, val downloads: Long, val followers: Long,
    val categories: List<String>, val iconUrl: String?, val sourceUrl: String?, val issuesUrl: String?, val pageUrl: String, val license: String?,
)

/** Minimal client for the public Modrinth API (v2). Only reads; no account or token needed. */
object Modrinth {
    private const val API = "https://api.modrinth.com/v2"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Modrinth "loader" categories a server type can run, most specific first. */
    fun loadersFor(type: ServerType): List<String> = when (type) {
        ServerType.PURPUR -> listOf("purpur", "paper", "spigot", "bukkit")
        ServerType.PAPER -> listOf("paper", "spigot", "bukkit")
        else -> emptyList()
    }

    /** "1.21.8-rc1" -> "1.21.8": Modrinth lists release versions only. */
    fun gameVersion(v: String) = v.substringBefore('-')

    suspend fun search(query: String, mcVersion: String, loaders: List<String>, offset: Int = 0, limit: Int = 20): List<MrProject> {
        val facets = JSONArray()
            .put(JSONArray().put("project_type:plugin"))
            .put(JSONArray().put("versions:${gameVersion(mcVersion)}"))
            .put(JSONArray(loaders.map { "categories:$it" }))
        val index = if (query.isBlank()) "downloads" else "relevance"
        val url = "$API/search?query=${enc(query)}&facets=${enc(facets.toString())}&limit=$limit&offset=$offset&index=$index"
        val hits = Http.json(url).getJSONArray("hits")
        return (0 until hits.length()).map { i ->
            val h = hits.getJSONObject(i)
            MrProject(
                id = h.getString("project_id"), slug = h.optString("slug"), title = h.getString("title"),
                description = h.optString("description"), author = h.optString("author"),
                downloads = h.optLong("downloads"), iconUrl = h.optString("icon_url").ifEmpty { null },
            )
        }
    }

    /** Newest compatible version, preferring stable releases over betas/alphas. */
    suspend fun latestVersion(projectId: String, mcVersion: String, loaders: List<String>): MrVersion? {
        val url = "$API/project/$projectId/version?loaders=${enc(JSONArray(loaders).toString())}" +
            "&game_versions=${enc(JSONArray(listOf(gameVersion(mcVersion))).toString())}"
        val arr = Http.jsonArray(url)
        if (arr.length() == 0) return null
        val all = (0 until arr.length()).map { arr.getJSONObject(it) }
        val v = all.firstOrNull { it.optString("version_type") == "release" } ?: all.first()
        return parseVersion(v)
    }

    /** Every version compatible with this server, newest first, including betas and alphas. */
    suspend fun versions(projectId: String, mcVersion: String, loaders: List<String>): List<MrVersion> {
        val url = "$API/project/$projectId/version?loaders=${enc(JSONArray(loaders).toString())}" +
            "&game_versions=${enc(JSONArray(listOf(gameVersion(mcVersion))).toString())}"
        val arr = Http.jsonArray(url)
        return (0 until arr.length()).mapNotNull { parseVersion(arr.getJSONObject(it)) }
    }

    suspend fun projectInfo(projectId: String): MrProjectInfo? = runCatching {
        val p = Http.json("$API/project/$projectId")
        fun list(k: String) = p.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        MrProjectInfo(
            title = p.getString("title"), description = p.optString("description"), downloads = p.optLong("downloads"), followers = p.optLong("followers"),
            categories = list("categories").filter { it !in setOf("bukkit", "spigot", "paper", "purpur", "folia", "bungeecord", "velocity", "waterfall") },
            iconUrl = p.optString("icon_url").ifEmpty { null }, sourceUrl = p.optString("source_url").ifEmpty { null }, issuesUrl = p.optString("issues_url").ifEmpty { null },
            pageUrl = "https://modrinth.com/plugin/${p.optString("slug", projectId)}", license = p.optJSONObject("license")?.optString("id")?.ifEmpty { null },
        )
    }.getOrNull()

    suspend fun projectTitle(projectId: String): String =
        runCatching { Http.json("$API/project/$projectId").getString("title") }.getOrDefault(projectId)

    internal fun parseVersion(v: JSONObject): MrVersion? {
        val files = v.getJSONArray("files")
        val list = (0 until files.length()).map { files.getJSONObject(it) }
        val f = list.firstOrNull { it.optBoolean("primary") } ?: list.firstOrNull { it.getString("filename").endsWith(".jar") } ?: return null
        val deps = v.optJSONArray("dependencies")
        val required = if (deps == null) emptyList() else (0 until deps.length()).map { deps.getJSONObject(it) }
            .filter { it.optString("dependency_type") == "required" && !it.isNull("project_id") }
            .map { it.getString("project_id") }
        return MrVersion(
            id = v.getString("id"), projectId = v.getString("project_id"), number = v.optString("version_number"),
            fileName = f.getString("filename"), url = f.getString("url"),
            sha512 = f.optJSONObject("hashes")?.optString("sha512")?.ifEmpty { null }, requiredDeps = required,
            name = v.optString("name").ifBlank { v.optString("version_number") },
            channel = when (v.optString("version_type")) { "beta" -> MrVersion.Channel.BETA; "alpha" -> MrVersion.Channel.ALPHA; else -> MrVersion.Channel.RELEASE },
            published = v.optString("date_published"), downloads = v.optLong("downloads"), changelog = v.optString("changelog"),
            gameVersions = v.optJSONArray("game_versions")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(), sizeBytes = f.optLong("size"),
        )
    }
}
