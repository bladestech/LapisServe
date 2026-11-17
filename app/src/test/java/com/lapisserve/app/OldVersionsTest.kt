package com.lapisserve.app

import com.lapisserve.app.core.McVersion
import com.lapisserve.app.engine.DnsConfig
import com.lapisserve.app.engine.Log4jFix
import com.lapisserve.app.model.ServerType
import com.lapisserve.app.runtime.AlpineIndex
import com.lapisserve.app.runtime.JavaRuntime
import com.lapisserve.app.runtime.MuslPatch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OldVersionsTest {
    @Test fun muslPatch_swapsBothPathsInPlace() {
        val bin = "xx\u0000/etc/resolv.conf\u0000\u0000option\u0000/etc/hosts\u0000/etc/hostsfile\u0000end".toByteArray(Charsets.US_ASCII)
        val r = MuslPatch.patch(bin); val out = r.bytes
        assertTrue(r.dns)
        assertEquals(bin.size, out.size)
        val s = String(out, Charsets.US_ASCII)
        assertTrue(s.contains("\u0000lapis-resolv\u0000\u0000\u0000\u0000\u0000\u0000option"))
        assertTrue(s.contains("\u0000lapis-host\u0000/etc/hostsfile\u0000"))   // a longer string that merely starts the same is untouched
    }

    @Test fun muslPatch_reportsWhenPathsAreMissing() {
        val bin = "nothing here".toByteArray()
        val r = MuslPatch.patch(bin)
        assertFalse(r.dns); assertFalse(r.accept); assertArrayEquals(bin, r.bytes)
    }

    @Test fun javaRuntimeFor_mapsMinecraftRequirements() {
        assertEquals(8, JavaRuntime.runtimeFor(8))      // 1.16.5 and older
        assertEquals(17, JavaRuntime.runtimeFor(16))    // 1.17 asks for 16; there is no Java 16 build, 17 runs it
        assertEquals(17, JavaRuntime.runtimeFor(17))
        assertEquals(21, JavaRuntime.runtimeFor(21))
        assertEquals(25, JavaRuntime.runtimeFor(25))
        assertTrue(JavaRuntime.fromAlpine(8) && JavaRuntime.fromAlpine(17) && !JavaRuntime.fromAlpine(21))
    }

    @Test fun mcVersion_ordering() {
        assertTrue(McVersion.atLeast("1.20.3", "1.20.3"))
        assertFalse(McVersion.atLeast("1.20.2", "1.20.3"))
        assertTrue(McVersion.atLeast("1.21", "1.20.3"))
        assertTrue(McVersion.atLeast("26.3", "1.21.11"))
        assertFalse(McVersion.atLeast("1.18-pre1", "1.18"))
        assertTrue(McVersion.atLeast("1.8.8", "1.7"))
    }

    @Test fun log4j_followsMojangAdvisory() {
        assertNull(Log4jFix.configFor(ServerType.VANILLA, "1.6.4"))
        assertEquals("log4j2_17-111.xml", Log4jFix.configFor(ServerType.VANILLA, "1.8.9")?.fileName)
        assertEquals("log4j2_112-116.xml", Log4jFix.configFor(ServerType.VANILLA, "1.16.5")?.fileName)
        assertNull(Log4jFix.configFor(ServerType.VANILLA, "1.17.1"))
        assertNull(Log4jFix.configFor(ServerType.PAPER, "1.12.2"))
        assertTrue(Log4jFix.jvmArgs(ServerType.VANILLA, "1.17.1").contains("-Dlog4j2.formatMsgNoLookups=true"))
        assertTrue(Log4jFix.jvmArgs(ServerType.VANILLA, "1.18.1").isEmpty())
        assertTrue(Log4jFix.jvmArgs(ServerType.VANILLA, "1.12.2").contains("-Dlog4j.configurationFile=log4j2_112-116.xml"))
        assertTrue(Log4jFix.jvmArgs(ServerType.PUMPKIN, "1.12").isEmpty())
    }

    @Test fun dnsConfig_usesNetworkServersFirstAndCapsAtThree() {
        val text = DnsConfig.render(listOf("192.168.1.1", "1.1.1.1"))
        val ns = text.lines().filter { it.startsWith("nameserver") }
        assertEquals(listOf("nameserver 192.168.1.1", "nameserver 1.1.1.1", "nameserver 8.8.8.8"), ns)
        assertEquals(listOf("nameserver 1.1.1.1", "nameserver 8.8.8.8"), DnsConfig.render(emptyList()).lines().filter { it.startsWith("nameserver") })
    }

    @Test fun alpineIndex_resolvesDependenciesThroughProvides() {
        val idx = """
            P:openjdk8-jre-base
            V:8.452-r0
            S:100
            D:openjdk8-jre-lib so:libnss3.so java-common /bin/sh

            P:openjdk8-jre-lib
            V:8.452-r0
            S:200

            P:nss
            V:3.114-r0
            S:300
            p:so:libnss3.so=0 so:libsmime3.so=0
            D:nspr

            P:nspr
            V:4.36-r0
            S:10

            P:java-common
            V:1.0-r0
        """.trimIndent()
        val index = AlpineIndex(AlpineIndex.parse(idx, "community").associateBy { it.name })
        val names = index.closure("openjdk8-jre-base", setOf("java-common")).map { it.name }
        assertEquals(listOf("openjdk8-jre-base", "openjdk8-jre-lib", "nss", "nspr"), names)
    }

    /** A minimal 64-bit LE ELF with one PT_INTERP program header pointing at a 26-byte interpreter string. */
    private fun fakeElf(): ByteArray {
        val interp = "/lib/ld-musl-aarch64.so.1\u0000".toByteArray(Charsets.US_ASCII)
        val bb = java.nio.ByteBuffer.allocate(64 + 56 + interp.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1))
        bb.putLong(0x20, 64L); bb.putShort(0x36, 56); bb.putShort(0x38, 1)
        bb.putInt(64, 3); bb.putLong(64 + 8, 120L); bb.putLong(64 + 32, interp.size.toLong())
        bb.position(120); bb.put(interp)
        return bb.array()
    }

    @Test fun elfInterp_rewritesToLocalLoader() {
        val elf = fakeElf()
        assertEquals("/lib/ld-musl-aarch64.so.1", com.lapisserve.app.runtime.ElfInterp.read(elf))
        val patched = com.lapisserve.app.runtime.ElfInterp.withInterp(elf, "lapis-ld")!!
        assertEquals("lapis-ld", com.lapisserve.app.runtime.ElfInterp.read(patched))
        assertEquals(elf.size, patched.size)
        assertNull(com.lapisserve.app.runtime.ElfInterp.withInterp(elf, "x".repeat(40)))   // no room: refuse
        assertNull(com.lapisserve.app.runtime.ElfInterp.read("not an elf".toByteArray()))
    }

    @Test fun javaSecurity_dropsNssProviderAndRenumbers() {
        val conf = listOf(
            "# comment", "security.provider.1=sun.security.provider.Sun",
            "security.provider.2=sun.security.pkcs11.SunPKCS11 \${java.home}/lib/security/nss.cfg",
            "security.provider.3=com.sun.crypto.provider.SunJCE", "other=1",
        ).joinToString("\n")
        val out = com.lapisserve.app.runtime.TrustStore.withoutNssProvider(conf)!!.lines()
        assertEquals(listOf("# comment", "security.provider.1=sun.security.provider.Sun", "security.provider.2=com.sun.crypto.provider.SunJCE", "other=1"), out)
        assertNull(com.lapisserve.app.runtime.TrustStore.withoutNssProvider("security.provider.1=sun.security.provider.Sun"))
    }
}
