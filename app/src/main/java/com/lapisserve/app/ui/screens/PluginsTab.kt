@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lapisserve.app.ui.screens

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lapisserve.app.core.Http
import com.lapisserve.app.engine.ServerManager
import com.lapisserve.app.model.ServerConfig
import com.lapisserve.app.modrinth.InstalledPlugin
import com.lapisserve.app.modrinth.Modrinth
import com.lapisserve.app.modrinth.MrProject
import com.lapisserve.app.modrinth.PluginStore
import com.lapisserve.app.ui.components.BannerKind
import com.lapisserve.app.ui.components.InfoBanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val imageCache = LruCache<String, ImageBitmap>(60)

/** Tiny remote image loader (plugin icons). Falls back to a placeholder while loading or on failure. */
@Composable
private fun RemoteIcon(url: String?, size: androidx.compose.ui.unit.Dp = 52.dp) {
    val bmp by produceState<ImageBitmap?>(url?.let { imageCache[it] }, url) {
        if (url == null || value != null) return@produceState
        value = runCatching {
            val b = Http.bytes(url)
            BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
        }.getOrNull()?.also { imageCache.put(url, it) }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.Extension, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

private fun compact(n: Long) = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

@Composable
fun PluginsTab(manager: ServerManager, cfg: ServerConfig, active: Boolean, notify: (String) -> Unit) {
    val store = remember(cfg.id) { PluginStore(File(manager.dir(cfg.id), "plugins")) }
    val loaders = remember(cfg.type) { Modrinth.loadersFor(cfg.type) }
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    val installed = remember(refresh) { store.installed() }
    val tracked = remember(refresh) { store.installedProjectIds() }
    val working = remember { mutableStateMapOf<String, String>() }   // projectId -> status text
    var changed by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            listOf("Installed (${installed.size})", "Browse Modrinth").forEachIndexed { i, t ->
                SegmentedButton(mode == i, { mode = i }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(t) }
            }
        }
        if (changed && active) InfoBanner("Plugin changes load when the server restarts.", Icons.Rounded.RestartAlt, BannerKind.INFO, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

        if (mode == 0) {
            if (installed.isEmpty()) Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.Extension, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                Text("No plugins yet", style = MaterialTheme.typography.titleMedium)
                Text("Browse Modrinth to add permissions, economy, world protection and more in one tap.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button({ mode = 1 }) { Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(8.dp)); Text("Browse Modrinth") }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(installed, key = { it.file.name }) { p -> InstalledRow(p,
                    onToggle = { store.setEnabled(p, it); changed = true; refresh++ },
                    onDelete = { store.remove(p); changed = true; refresh++; notify("Removed ${p.name}") }) }
            }
        } else {
            var query by rememberSaveable { mutableStateOf("") }
            var results by remember { mutableStateOf<List<MrProject>>(emptyList()) }
            var loading by remember { mutableStateOf(true) }
            var error by remember { mutableStateOf<String?>(null) }
            var attempt by remember { mutableIntStateOf(0) }
            LaunchedEffect(query, attempt) {
                loading = true; error = null
                delay(350)   // debounce typing
                runCatching { Modrinth.search(query, cfg.version, loaders) }
                    .onSuccess { results = it }.onFailure { error = it.message ?: "Couldn't reach Modrinth" }
                loading = false
            }
            Column(Modifier.fillMaxSize()) {
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, shape = RoundedCornerShape(50),
                    placeholder = { Text("Search plugins for ${cfg.type.label} ${Modrinth.gameVersion(cfg.version)}") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) ({ IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear") } }) else null,
                )
                Text(if (query.isBlank()) "Most downloaded" else "Results", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                when {
                    loading && results.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator() }
                    error != null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        InfoBanner(error!!, Icons.Rounded.CloudOff, BannerKind.ERROR, title = "Couldn't load plugins")
                        OutlinedButton({ attempt++ }) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Try again") }
                    }
                    results.isEmpty() -> Text("Nothing found for this Minecraft version. Try a different search.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(results, key = { it.id }) { p ->
                            ResultCard(p, installed = p.id in tracked, status = working[p.id]) {
                                scope.launch {
                                    working[p.id] = "Starting"
                                    runCatching { store.install(p.id, p.title, cfg.version, loaders, status = { working[p.id] = it }) }
                                        .onSuccess { names -> changed = true; refresh++; notify("Installed " + names.joinToString()) }
                                        .onFailure { notify("Install failed: ${it.message}") }
                                    working.remove(p.id)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledRow(p: InstalledPlugin, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Extension, null, tint = if (p.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (p.enabled) p.file.name else "Disabled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Switch(p.enabled, onToggle)
            IconButton({ confirm = true }) { Icon(Icons.Rounded.Delete, "Remove ${p.name}") }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, icon = { Icon(Icons.Rounded.Delete, null) },
        title = { Text("Remove ${p.name}?") }, text = { Text("The plugin jar is deleted. Its config folder and data are kept.") },
        confirmButton = { Button({ confirm = false; onDelete() }) { Text("Remove") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Keep") } },
    )
}

@Composable
private fun ResultCard(p: MrProject, installed: Boolean, status: String?, onInstall: () -> Unit) {
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            RemoteIcon(p.iconUrl)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("by ${p.author} · ${compact(p.downloads)} downloads", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(p.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                when {
                    status != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text(status, style = MaterialTheme.typography.labelMedium)
                    }
                    installed -> AssistChip({}, { Text("Installed") }, enabled = false, leadingIcon = { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) })
                    else -> FilledTonalButton(onInstall, contentPadding = PaddingValues(horizontal = 16.dp)) { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Install") }
                }
            }
        }
    }
}
