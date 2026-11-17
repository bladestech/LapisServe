package com.lapisserve.app

import com.lapisserve.app.engine.ConsoleParsers
import com.lapisserve.app.engine.Slp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class MetricsTest {
    @Test fun tps_paperThreeValues() {
        val t = ConsoleParsers.parseTps("[08:02:11 INFO]: TPS from last 1m, 5m, 15m: 19.98, 20.0, *20.0")!!
        assertEquals(19.98f, t.first, 0.001f); assertEquals(20f, t.second!!, 0.001f); assertEquals(20f, t.third!!, 0.001f)
    }

    @Test fun tps_purpurFourValuesSkipsFiveSeconds() {
        val t = ConsoleParsers.parseTps("§6TPS from last 5s, 1m, 5m, 15m: §a*20.0§6, §a19.5§6, §a18.0§6, §c12.5")!!
        assertEquals(19.5f, t.first, 0.001f); assertEquals(18f, t.second!!, 0.001f); assertEquals(12.5f, t.third!!, 0.001f)
    }

    @Test fun tps_clampedAndIgnoresOtherLines() {
        assertEquals(20f, ConsoleParsers.parseTps("TPS from last 1m, 5m, 15m: 20.4, 20.0, 20.0")!!.first, 0.001f)
        assertNull(ConsoleParsers.parseTps("Steve joined the game"))
    }

    @Test fun vanillaTickQuery_andTpsDerivation() {
        assertEquals(3.5f, ConsoleParsers.parseTickQuery("Average time per tick: 3.5ms (Target: 50.0ms)")!!, 0.001f)
        assertEquals(20f, ConsoleParsers.tpsFromMspt(3.5f), 0.001f)
        assertEquals(10f, ConsoleParsers.tpsFromMspt(100f), 0.001f)
    }

    @Test fun mspt_paperLine() {
        assertEquals(3.1f, ConsoleParsers.parseMspt("◴ 3.1/2.0/8.5, 3.0/1.9/9.1, 2.9/1.8/12.0")!!, 0.001f)
        assertNull(ConsoleParsers.parseMspt("Server is running"))
    }

    @Test fun playerEvents() {
        val j = ConsoleParsers.parsePlayerEvent("[08:00:01 INFO]: Steve joined the game")!!
        assertEquals("Steve", j.name); assertEquals(ConsoleParsers.Presence.JOINED, j.presence)
        val l = ConsoleParsers.parsePlayerEvent("[08:05:01 INFO]: Alex_99 left the game")!!
        assertEquals(ConsoleParsers.Presence.LEFT, l.presence)
        assertNull(ConsoleParsers.parsePlayerEvent("[08:05:01 INFO]: Done (12.5s)! For help, type \"help\""))
    }

    @Test fun pollingOutputIsRecognised() {
        assertTrue(ConsoleParsers.isPollingOutput("TPS from last 1m, 5m, 15m: 20.0, 20.0, 20.0"))
        assertTrue(ConsoleParsers.isPollingOutput("Unknown or incomplete command, see below for error"))
        assertTrue(ConsoleParsers.isPollingOutput("◴ 3.1/2.0/8.5, 3.0/1.9/9.1"))
        assertTrue(ConsoleParsers.isPollingOutput("[07:05:57 INFO]: ◴ 4.5/2.4/11.4, 5.8/0.9/57.2, 5.8/0.9/57.2"))
        assertFalse(ConsoleParsers.isPollingOutput("[08:00:01 INFO]: Steve joined the game"))
    }

    @Test fun formattingStripped() {
        assertEquals("Hello", ConsoleParsers.stripFormatting("\u001B[32mHello\u001B[0m"))
        assertEquals("A server", ConsoleParsers.stripFormatting("§aA §lserver"))
    }

    @Test fun slpParse_readsPlayersAndMotd() {
        val r = Slp.parse(
            """{"version":{"name":"Paper 1.21.8","protocol":772},"players":{"max":10,"online":2,"sample":[{"name":"Steve","id":"x"},{"name":"Alex","id":"y"}]},""" +
                """"description":{"text":"Hello ","extra":[{"text":"world"}]}}""", 12,
        )
        assertEquals(2, r.online); assertEquals(10, r.max); assertEquals(listOf("Steve", "Alex"), r.sample)
        assertEquals("Paper 1.21.8", r.versionName); assertEquals("Hello world", r.motd); assertEquals(12, r.latencyMs)
    }

    /** Talks to a tiny fake server that speaks just enough of the Minecraft status protocol. */
    @Test fun slpQuery_endToEndAgainstFakeServer() {
        val json = """{"version":{"name":"Fake","protocol":1},"players":{"max":5,"online":1,"sample":[{"name":"Bob","id":"1"}]},"description":"Hi"}"""
        val server = ServerSocket(0)
        val t = thread(isDaemon = true) {
            server.accept().use { s ->
                val i = DataInputStream(s.getInputStream()); val o = DataOutputStream(s.getOutputStream())
                fun readVar(): Int { var r = 0; var sh = 0; while (true) { val b = i.readUnsignedByte(); r = r or ((b and 0x7F) shl sh); if (b and 0x80 == 0) return r; sh += 7 } }
                fun writeVar(out: DataOutputStream, v0: Int) { var v = v0; while (true) { if (v and 0x7F.inv() == 0) { out.writeByte(v); return }; out.writeByte(v and 0x7F or 0x80); v = v ushr 7 } }
                val hsLen = readVar(); i.readFully(ByteArray(hsLen))          // handshake
                readVar(); readVar()                                           // status request
                val body = ByteArrayOutputStream().also { b -> val d = DataOutputStream(b); writeVar(d, 0); val js = json.toByteArray(); writeVar(d, js.size); d.write(js) }.toByteArray()
                writeVar(o, body.size); o.write(body); o.flush()
                readVar(); readVar(); val payload = i.readLong()               // ping
                val pong = ByteArrayOutputStream().also { b -> val d = DataOutputStream(b); writeVar(d, 1); d.writeLong(payload) }.toByteArray()
                writeVar(o, pong.size); o.write(pong); o.flush()
            }
        }
        val r = Slp.query("127.0.0.1", server.localPort, 3000)
        t.join(2000); server.close()
        assertNotNull(r)
        assertEquals(1, r!!.online); assertEquals(5, r.max); assertEquals(listOf("Bob"), r.sample); assertEquals("Hi", r.motd)
        assertTrue(r.latencyMs >= 0)
    }

    @Test fun slpQuery_returnsNullWhenNothingListening() {
        val free = ServerSocket(0).use { it.localPort }
        assertNull(Slp.query("127.0.0.1", free, 300))
    }
}
