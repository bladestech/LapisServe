package com.lapisserve.app.engine

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class Backup(val file: File, val sizeBytes: Long, val time: Long) {
    val name: String get() = file.nameWithoutExtension
}

/**
 * World backups as plain zip files: the world folders plus the small files that define who may join
 * (ops, whitelist, bans) and how the server is set up. Server jars, logs and plugin jars are not included.
 */
object Backups {
    private fun isWorldFolder(f: File) = f.isDirectory && (f.name.startsWith("world") || f.name == "DIM-1" || f.name == "DIM1")
    private val extraFiles = listOf(
        "server.properties", "ops.json", "whitelist.json", "banned-players.json", "banned-ips.json", "usercache.json",
        "pumpkin.toml", "bukkit.yml", "spigot.yml", "commands.yml",
    )

    /** Everything a backup covers, as paths relative to [serverDir]. */
    fun targets(serverDir: File): List<File> =
        serverDir.listFiles().orEmpty().filter { isWorldFolder(it) } + extraFiles.map { File(serverDir, it) }.filter { it.isFile }

    fun create(serverDir: File, dest: File): Long {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            val root = serverDir.canonicalFile
            fun add(f: File) {
                if (f.isDirectory) f.listFiles().orEmpty().forEach { add(it) }
                else if (f.isFile && !f.name.endsWith(".lock")) {
                    zip.putNextEntry(ZipEntry(f.canonicalFile.relativeTo(root).path.replace('\\', '/')).apply { time = f.lastModified() })
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            targets(serverDir).forEach { add(it) }
        }
        if (dest.exists()) dest.delete()
        check(tmp.renameTo(dest)) { "Could not finish the backup file" }
        return dest.length()
    }

    /** Replaces the server's worlds and member lists with the contents of [zip]. The server must be stopped. */
    fun restore(zip: File, serverDir: File) {
        val root = serverDir.canonicalFile
        // Validate before deleting anything: a corrupt or empty zip must never destroy the current world.
        // (ZipInputStream silently reads garbage as "no entries", so use ZipFile, which rejects it.)
        val entryCount = java.util.zip.ZipFile(zip).use { it.size() }
        require(entryCount > 0) { "This backup is empty or damaged" }
        targets(serverDir).forEach { if (it.isDirectory) it.deleteRecursively() else it.delete() }
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(root, e.name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) continue    // zip-slip guard
                if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
            }
        }
    }

    fun list(dir: File): List<Backup> =
        dir.listFiles { f -> f.isFile && f.extension == "zip" }.orEmpty().map { Backup(it, it.length(), it.lastModified()) }.sortedByDescending { it.time }
}
