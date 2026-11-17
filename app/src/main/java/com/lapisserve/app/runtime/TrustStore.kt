package com.lapisserve.app.runtime

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * Java needs a list of trusted certificate authorities (its "cacerts" file) for HTTPS: Mojang login, skins, Paper
 * downloads, plugin update checks. Alpine's Java packages expect the system to generate it at install time, which
 * can't happen here, so LapisServe writes one from Android's own trusted CAs (including any the user added).
 *
 * The file is in JKS format, which every Java version from 8 onwards reads without a password.
 */
object TrustStore {
    private const val MAGIC = 0xFEEDFEED.toInt()
    private const val VERSION = 2
    private const val TRUSTED_CERT = 2

    /** Android's trusted CA certificates. */
    fun androidCertificates(): List<X509Certificate> {
        val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        return ks.aliases().toList().mapNotNull { ks.getCertificate(it) as? X509Certificate }
    }

    /**
     * Alpine's Java registers Mozilla NSS as a crypto provider, loaded from /usr/lib. That isn't there on Android,
     * and the first HTTPS connection then fails. Removes that provider (Java's built-in ones cover everything, as in
     * Temurin) and renumbers the rest, since Java stops reading providers at the first gap. Returns the new text,
     * or null when nothing needed changing.
     */
    fun withoutNssProvider(javaSecurity: String): String? {
        val provider = Regex("""^security\.provider\.(\d+)=(.*)$""")
        val lines = javaSecurity.lines()
        if (lines.none { provider.matchEntire(it)?.groupValues?.get(2)?.contains("nss.cfg") == true }) return null
        var n = 0
        val out = lines.mapNotNull { line ->
            val m = provider.matchEntire(line) ?: return@mapNotNull line
            if (m.groupValues[2].contains("nss.cfg")) null else "security.provider.${++n}=${m.groupValues[2]}"
        }
        return out.joinToString("\n")
    }

    /** Applies [withoutNssProvider] to a JVM home (Java 8 keeps the file in lib/security, 9+ in conf/security). */
    fun fixSecurityConfig(home: File) {
        for (f in listOf(File(home, "lib/security/java.security"), File(home, "conf/security/java.security"))) {
            if (!f.isFile) continue
            withoutNssProvider(f.readText())?.let { f.writeText(it) }
        }
    }

    /** Writes [certs] as a JKS truststore with the standard "changeit" password. */
    fun writeJks(certs: List<X509Certificate>, dest: File, password: String = "changeit") {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(certs.size)
            val now = System.currentTimeMillis()
            certs.forEachIndexed { i, c ->
                out.writeInt(TRUSTED_CERT)
                out.writeUTF("lapis-ca-$i")
                out.writeLong(now)
                out.writeUTF("X.509")
                val enc = c.encoded
                out.writeInt(enc.size); out.write(enc)
            }
        }
        val data = body.toByteArray()
        // JKS integrity check: SHA-1 over the password (UTF-16BE), the fixed salt string, then the store contents.
        val md = MessageDigest.getInstance("SHA-1")
        for (ch in password) { md.update((ch.code shr 8).toByte()); md.update(ch.code.toByte()) }
        md.update("Mighty Aphrodite".toByteArray(Charsets.UTF_8))
        md.update(data)
        dest.parentFile?.mkdirs()
        dest.delete()   // may be a dangling symlink to /etc/ssl/... from the package
        dest.outputStream().use { it.write(data); it.write(md.digest()) }
    }
}
