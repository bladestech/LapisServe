package com.lapisserve.app.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Small, targeted patches to our copy of the musl loader (which *is* libc on musl). Nothing in the binary moves.
 *
 * 1. DNS. musl reads its DNS settings from /etc/resolv.conf and host entries from /etc/hosts. Android has neither and
 *    apps can't create files in /etc, so a JVM on musl can't resolve any hostname. Those two paths are swapped for
 *    relative ones; the server runs with its working directory set to its folder, where LapisServe writes [RESOLV]
 *    (the phone's DNS servers) and [HOSTS] (localhost).
 *
 * 2. accept(). On ARM64, musl's accept() uses the `accept` syscall (#202). Android's own libc only ever uses
 *    `accept4` (#242), and the syscall filter every app runs under kills a process that calls #202. Java's built-in
 *    networking (used by Minecraft 1.8-1.16 and anything without Netty's native transport) goes through accept(), so
 *    the first player to connect killed the server. accept4 with flags 0 is exactly accept, and musl already passes 0
 *    in that argument, so the syscall number is changed from 202 to 242.
 */
object MuslPatch {
    const val RESOLV = "lapis-resolv"
    const val HOSTS = "lapis-host"

    private val pathSwaps = listOf("/etc/resolv.conf" to RESOLV, "/etc/hosts" to HOSTS)

    // AArch64: `movz x0, #202` -> `movz x0, #242` inside accept().
    private const val MOV_X0_202 = 0xd2801940.toInt()
    private const val MOV_X0_242 = 0xd2801e40.toInt()

    data class Result(val bytes: ByteArray, val dns: Boolean, val accept: Boolean)

    fun patch(original: ByteArray): Result {
        val b = original.copyOf()
        var paths = 0
        for ((from, to) in pathSwaps) {
            require(to.length <= from.length)
            // Match the whole C string (with its terminator) so we never touch a longer string that merely contains it.
            val at = indexOf(b, (from + "\u0000").toByteArray(Charsets.US_ASCII))
            if (at < 0) continue
            val repl = to.toByteArray(Charsets.US_ASCII)
            for (i in from.indices) b[at + i] = if (i < repl.size) repl[i] else 0
            paths++
        }
        val accept = patchAccept(b)
        return Result(b, dns = paths == pathSwaps.size, accept = accept)
    }

    /** Rewrites the syscall number in accept(); true if done (or already done). AArch64 ELF64 only. */
    private fun patchAccept(b: ByteArray): Boolean {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        if (b.size < 64 || b[4].toInt() != 2 || b[5].toInt() != 1) return false
        if ((bb.getShort(0x12).toInt() and 0xffff) != 183) return false   // EM_AARCH64
        val (addr, size) = symbol(b, bb, "accept") ?: return false
        val off = vaddrToOffset(bb, addr) ?: return false
        var i = 0
        while (i + 4 <= size && off + i + 4 <= b.size) {
            val ins = bb.getInt(off + i)
            if (ins == MOV_X0_242) return true
            if (ins == MOV_X0_202) { bb.putInt(off + i, MOV_X0_242); return true }
            i += 4
        }
        return false
    }

    /** (value, size) of a symbol from .dynsym. */
    private fun symbol(b: ByteArray, bb: ByteBuffer, name: String): Pair<Long, Int>? {
        val shoff = bb.getLong(0x28).toInt()
        val shentsize = bb.getShort(0x3A).toInt() and 0xffff
        val shnum = bb.getShort(0x3C).toInt() and 0xffff
        if (shoff <= 0 || shoff + shnum * shentsize > b.size) return null
        fun sh(i: Int) = shoff + i * shentsize
        val dynsym = (0 until shnum).firstOrNull { bb.getInt(sh(it) + 4) == 11 } ?: return null   // SHT_DYNSYM
        val strtab = bb.getInt(sh(dynsym) + 40)
        val symOff = bb.getLong(sh(dynsym) + 24).toInt(); val symSize = bb.getLong(sh(dynsym) + 32).toInt()
        val strOff = bb.getLong(sh(strtab) + 24).toInt()
        val want = name.toByteArray(Charsets.US_ASCII)
        for (s in 0 until symSize / 24) {
            val o = symOff + s * 24
            val n = strOff + bb.getInt(o)
            if (n + want.size >= b.size) continue
            if ((want.indices).all { b[n + it] == want[it] } && b[n + want.size].toInt() == 0) {
                return bb.getLong(o + 8) to bb.getLong(o + 16).toInt()
            }
        }
        return null
    }

    private fun vaddrToOffset(bb: ByteBuffer, vaddr: Long): Int? {
        val phoff = bb.getLong(0x20).toInt()
        val phentsize = bb.getShort(0x36).toInt() and 0xffff
        val phnum = bb.getShort(0x38).toInt() and 0xffff
        for (i in 0 until phnum) {
            val o = phoff + i * phentsize
            if (bb.getInt(o) != 1) continue   // PT_LOAD
            val off = bb.getLong(o + 8); val va = bb.getLong(o + 16); val filesz = bb.getLong(o + 32)
            if (vaddr >= va && vaddr < va + filesz) return (vaddr - va + off).toInt()
        }
        return null
    }

    private fun indexOf(h: ByteArray, n: ByteArray): Int {
        outer@ for (i in 0..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }
}
