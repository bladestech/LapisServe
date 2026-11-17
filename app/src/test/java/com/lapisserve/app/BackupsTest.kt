package com.lapisserve.app

import com.lapisserve.app.engine.Backups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun server(): File = tmp.newFolder("srv").apply {
        File(this, "world/region").mkdirs(); File(this, "world/region/r.0.0.mca").writeText("chunks")
        File(this, "world/session.lock").writeText("lock")
        File(this, "world_nether/DIM-1").mkdirs(); File(this, "world_nether/DIM-1/data.dat").writeText("nether")
        File(this, "ops.json").writeText("[ops]"); File(this, "whitelist.json").writeText("[wl]")
        File(this, "server.properties").writeText("motd=hi")
        File(this, "server.jar").writeText("JAR"); File(this, "logs").mkdirs(); File(this, "logs/latest.log").writeText("log")
        File(this, "plugins").mkdirs(); File(this, "plugins/Big.jar").writeText("plugin")
    }

    private fun entries(zip: File): List<String> =
        java.util.zip.ZipFile(zip).use { z -> z.entries().asSequence().map { it.name }.sorted().toList() }

    @Test fun backup_includesWorldsAndMemberListsOnly() {
        val s = server(); val out = File(tmp.root, "b/one.zip")
        val size = Backups.create(s, out)
        assertTrue(size > 0)
        val names = entries(out)
        assertTrue("world/region/r.0.0.mca" in names)
        assertTrue("world_nether/DIM-1/data.dat" in names)
        assertTrue("ops.json" in names && "whitelist.json" in names && "server.properties" in names)
        assertFalse("server.jar" in names); assertFalse(names.any { it.startsWith("logs/") || it.startsWith("plugins/") })
        assertFalse("world/session.lock" in names)
    }

    @Test fun restore_replacesCurrentWorldWithBackup() {
        val s = server(); val out = File(tmp.root, "b/one.zip")
        Backups.create(s, out)
        File(s, "world/region/r.0.0.mca").writeText("CORRUPTED"); File(s, "world/extra.dat").writeText("new stuff")
        File(s, "ops.json").writeText("[changed]")
        Backups.restore(out, s)
        assertEquals("chunks", File(s, "world/region/r.0.0.mca").readText())
        assertFalse(File(s, "world/extra.dat").exists())
        assertEquals("[ops]", File(s, "ops.json").readText())
        assertTrue(File(s, "server.jar").exists())       // untouched
    }

    @Test fun restore_rejectsCorruptZipWithoutDeletingTheWorld() {
        val s = server(); val bad = File(tmp.root, "bad.zip").apply { writeText("this is not a zip") }
        var failed = false
        try { Backups.restore(bad, s) } catch (e: Exception) { failed = true }
        assertTrue(failed)
        assertEquals("chunks", File(s, "world/region/r.0.0.mca").readText())
    }

    @Test fun restore_ignoresPathTraversalEntries() {
        val s = server()
        val evil = File(tmp.root, "evil.zip")
        ZipOutputStream(evil.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("../escaped.txt")); z.write("x".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("ops.json")); z.write("[ok]".toByteArray()); z.closeEntry()
        }
        Backups.restore(evil, s)
        assertFalse(File(tmp.root, "escaped.txt").exists())
        assertEquals("[ok]", File(s, "ops.json").readText())
    }

    @Test fun list_isNewestFirst() {
        val d = tmp.newFolder("lst")
        File(d, "a.zip").apply { writeText("1"); setLastModified(1000) }
        File(d, "b.zip").apply { writeText("22"); setLastModified(5000) }
        File(d, "note.txt").writeText("ignored")
        assertEquals(listOf("b", "a"), Backups.list(d).map { it.name })
    }
}
