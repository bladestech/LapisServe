package com.lapisserve.app.engine

import android.content.Context
import android.net.ConnectivityManager
import com.lapisserve.app.runtime.MuslPatch
import java.io.File
import java.net.Inet6Address

/**
 * Writes the DNS settings a server's JVM reads (see [MuslPatch]): the DNS servers of the phone's current network,
 * plus public resolvers as backup, in resolv.conf format. musl re-reads the file on every lookup, so rewriting it
 * when the network changes (Wi-Fi to mobile data, say) takes effect immediately without a restart.
 */
object DnsConfig {
    /** Asked alongside the network's own servers; musl queries all listed servers in parallel and keeps the first answer. */
    val FALLBACK = listOf("1.1.1.1", "8.8.8.8")

    /** DNS servers of the active network, as Android sees them. */
    fun networkServers(ctx: Context): List<String> = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val net = cm.activeNetwork ?: return emptyList()
        cm.getLinkProperties(net)?.dnsServers.orEmpty()
            // Link-local IPv6 needs an interface scope; skip it rather than risk a broken entry.
            .filterNot { it is Inet6Address && it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
    }.getOrDefault(emptyList())

    /** resolv.conf contents; musl uses at most three nameservers. */
    fun render(network: List<String>): String = buildString {
        appendLine("# Written by LapisServe from this phone's network settings. Rewritten automatically.")
        (network + FALLBACK).distinct().take(3).forEach { appendLine("nameserver $it") }
        appendLine("options timeout:2 attempts:2")
    }

    const val HOSTS = "127.0.0.1 localhost\n::1 localhost\n"

    /** Writes both files into [serverDir] if their contents changed. Returns the nameservers in use. */
    fun write(ctx: Context, serverDir: File): List<String> {
        val network = networkServers(ctx)
        val text = render(network)
        val resolv = File(serverDir, MuslPatch.RESOLV)
        if (!resolv.exists() || resolv.readText() != text) resolv.writeText(text)
        val hosts = File(serverDir, MuslPatch.HOSTS)
        if (!hosts.exists() || hosts.readText() != HOSTS) hosts.writeText(HOSTS)
        return text.lineSequence().filter { it.startsWith("nameserver ") }.map { it.removePrefix("nameserver ") }.toList()
    }
}
