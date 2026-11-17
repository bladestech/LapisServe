package com.lapisserve.app

import com.lapisserve.app.core.humanBytes
import com.lapisserve.app.core.humanMb
import com.lapisserve.app.engine.DnsHosts
import com.lapisserve.app.model.ServerConfig
import com.lapisserve.app.model.ServerType
import com.lapisserve.app.model.displayVersion
import com.lapisserve.app.model.supportsPlugins
import com.lapisserve.app.modrinth.Modrinth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpersTest {
    @Test fun parseExtraHosts_acceptsSeparatorsAndRejectsJunk() {
        assertEquals(
            listOf("example.com", "api.example.org", "foo.bar"),
            DnsHosts.parseExtra("Example.com, api.example.org;\n foo.bar  localhost not_a_host http://x"),
        )
    }

    @Test fun defaultHosts_coverPaperclipAndMojangAuth() {
        for (h in listOf("piston-data.mojang.com", "sessionserver.mojang.com", "discovery.minecraftservices.com", "api.purpurmc.org")) {
            assertTrue("missing $h", h in DnsHosts.defaultHosts)
        }
    }

    @Test fun modrinthGameVersion_stripsPreReleaseSuffix() {
        assertEquals("1.21.8", Modrinth.gameVersion("1.21.8-rc1"))
        assertEquals("26.3", Modrinth.gameVersion("26.3"))
    }

    @Test fun modrinthLoaders_onlyForPluginServers() {
        assertEquals(listOf("paper", "spigot", "bukkit"), Modrinth.loadersFor(ServerType.PAPER))
        assertEquals("purpur", Modrinth.loadersFor(ServerType.PURPUR).first())
        assertTrue(Modrinth.loadersFor(ServerType.VANILLA).isEmpty())
        assertTrue(Modrinth.loadersFor(ServerType.PUMPKIN).isEmpty())
    }

    @Test fun pluginSupport_matchesServerType() {
        assertTrue(ServerType.PAPER.supportsPlugins && ServerType.PURPUR.supportsPlugins)
        assertFalse(ServerType.VANILLA.supportsPlugins || ServerType.PUMPKIN.supportsPlugins)
    }

    @Test fun displayVersion_trimsPumpkinTags() {
        val p = ServerConfig(id = "p", name = "p", type = ServerType.PUMPKIN, version = "0.2.2+26.3-26.60")
        assertEquals("0.2.2", p.displayVersion)
        val v = ServerConfig(id = "v", name = "v", type = ServerType.VANILLA, version = "1.21.8")
        assertEquals("1.21.8", v.displayVersion)
    }

    @Test fun formatting() {
        assertEquals("512 MB", 512.humanMb())
        assertEquals("1 GB", 1024.humanMb())
        assertEquals("1.5 GB", 1536.humanMb())
        assertEquals("999 B", 999L.humanBytes())
        assertEquals("1.0 KB", 1024L.humanBytes())
    }
}
