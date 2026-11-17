package com.lapisserve.app.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads and rewrites the program interpreter (PT_INTERP) of a 64-bit little-endian ELF executable: the path of the
 * dynamic loader the kernel starts it with. Alpine's binaries ask for /lib/ld-musl-*.so.1, which doesn't exist on Android.
 *
 * Why it matters: Alpine's Java 8/11/17 launchers re-execute themselves via /proc/self/exe during start-up. Started
 * through "loader java …", /proc/self/exe is the loader, so the re-exec goes wrong. Pointing the interpreter at
 * [LOCAL] (a relative path the kernel resolves in the working directory, where LapisServe places a link to the patched
 * musl loader) lets `java` be executed directly, so re-executing works.
 */
object ElfInterp {
    const val LOCAL = "lapis-ld"
    private const val PT_INTERP = 3

    private fun header(b: ByteArray): ByteBuffer? {
        if (b.size < 64 || b[0] != 0x7f.toByte() || b[1] != 'E'.code.toByte() || b[2] != 'L'.code.toByte() || b[3] != 'F'.code.toByte()) return null
        if (b[4].toInt() != 2 || b[5].toInt() != 1) return null   // 64-bit, little-endian only
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    }

    /** (offset, size) of the interpreter string, or null if the file has none or isn't a 64-bit LE ELF. */
    private fun find(b: ByteArray): Pair<Int, Int>? {
        val bb = header(b) ?: return null
        val phoff = bb.getLong(0x20).toInt()
        val phentsize = bb.getShort(0x36).toInt() and 0xffff
        val phnum = bb.getShort(0x38).toInt() and 0xffff
        for (i in 0 until phnum) {
            val o = phoff + i * phentsize
            if (o + 56 > b.size) return null
            if (bb.getInt(o) == PT_INTERP) {
                val off = bb.getLong(o + 8).toInt(); val size = bb.getLong(o + 32).toInt()
                if (off < 0 || size <= 0 || off + size > b.size) return null
                return off to size
            }
        }
        return null
    }

    fun read(b: ByteArray): String? = find(b)?.let { (off, size) -> String(b, off, size, Charsets.US_ASCII).substringBefore('\u0000') }

    /** Returns a copy with the interpreter set to [interp], or null if there is no room or no interpreter. */
    fun withInterp(b: ByteArray, interp: String): ByteArray? {
        val (off, size) = find(b) ?: return null
        val bytes = interp.toByteArray(Charsets.US_ASCII)
        if (bytes.size + 1 > size) return null
        val out = b.copyOf()
        for (i in 0 until size) out[off + i] = if (i < bytes.size) bytes[i] else 0
        return out
    }
}
