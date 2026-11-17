package com.lapisserve.app

import com.lapisserve.app.modrinth.Modrinth
import com.lapisserve.app.modrinth.MrVersion
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ModrinthVersionsTest {
    private fun version(type: String, files: String = """[{"primary":true,"filename":"p.jar","url":"https://cdn/p.jar","size":1234,"hashes":{"sha512":"abc"}}]""") = JSONObject(
        """{"id":"v1","project_id":"P","version_number":"2.0.0-beta.1","name":"Big update","version_type":"$type","date_published":"2025-09-06T21:50:11.982Z",
           "downloads":42,"changelog":"- fixed things","game_versions":["1.21.7","1.21.8"],"files":$files,
           "dependencies":[{"dependency_type":"required","project_id":"DEP1"},{"dependency_type":"optional","project_id":"OPT"},{"dependency_type":"required","project_id":null}]}""",
    )

    @Test fun channels_areMapped() {
        assertEquals(MrVersion.Channel.RELEASE, Modrinth.parseVersion(version("release"))!!.channel)
        assertEquals(MrVersion.Channel.BETA, Modrinth.parseVersion(version("beta"))!!.channel)
        assertEquals(MrVersion.Channel.ALPHA, Modrinth.parseVersion(version("alpha"))!!.channel)
    }

    @Test fun details_areParsed() {
        val v = Modrinth.parseVersion(version("beta"))!!
        assertEquals("2.0.0-beta.1", v.number); assertEquals("Big update", v.name)
        assertEquals("2025-09-06T21:50:11.982Z", v.published); assertEquals(42L, v.downloads); assertEquals(1234L, v.sizeBytes)
        assertEquals("abc", v.sha512); assertEquals(listOf("1.21.7", "1.21.8"), v.gameVersions); assertEquals("- fixed things", v.changelog)
    }

    @Test fun onlyRequiredDependenciesWithAProjectAreKept() {
        assertEquals(listOf("DEP1"), Modrinth.parseVersion(version("release"))!!.requiredDeps)
    }

    @Test fun primaryFileIsPreferredAndJarFallbackWorks() {
        val two = """[{"primary":false,"filename":"sources.jar","url":"u1","size":1,"hashes":{}},{"primary":true,"filename":"main.jar","url":"u2","size":2,"hashes":{}}]"""
        assertEquals("main.jar", Modrinth.parseVersion(version("release", two))!!.fileName)
        val noPrimary = """[{"primary":false,"filename":"readme.txt","url":"u1","size":1,"hashes":{}},{"primary":false,"filename":"x.jar","url":"u2","size":2,"hashes":{}}]"""
        assertEquals("x.jar", Modrinth.parseVersion(version("release", noPrimary))!!.fileName)
    }

    @Test fun versionWithoutAnyFileIsSkipped() {
        assertNull(Modrinth.parseVersion(version("release", "[]")))
        assertNotNull(Modrinth.parseVersion(version("release")))
    }
}
