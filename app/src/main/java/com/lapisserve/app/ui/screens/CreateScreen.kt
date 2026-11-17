@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lapisserve.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lapisserve.app.core.Arch
import com.lapisserve.app.core.humanMb
import com.lapisserve.app.engine.ServerManager
import com.lapisserve.app.model.ServerType
import com.lapisserve.app.model.VersionEntry
import com.lapisserve.app.providers.Providers
import com.lapisserve.app.system.SystemInfo
import com.lapisserve.app.ui.components.*

@Composable
fun CreateScreen(manager: ServerManager, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val ctx = LocalContext.current
    val mem by remember { SystemInfo.memoryFlow(ctx) }.collectAsState(initial = SystemInfo.memory(ctx))
    var type by remember { mutableStateOf(ServerType.PAPER) }
    var version by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var ram by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf(false) }
    val supported = type != ServerType.PUMPKIN || Arch.current == Arch.ARM64

    // Sensible default: ~1/4 of device RAM, 1-4 GB, snapped to 128 MB.
    LaunchedEffect(mem.totalMb) { if (ram == 0) ram = ((mem.totalMb / 4).coerceIn(1024, 4096) / 128) * 128 }
    var versions by remember(type) { mutableStateOf<List<VersionEntry>?>(null) }
    var error by remember(type) { mutableStateOf<String?>(null) }
    LaunchedEffect(type) {
        version = null; versions = null; error = null
        if (!supported) return@LaunchedEffect
        runCatching { Providers.versions(type) }
            .onSuccess { v -> versions = v; version = (v.firstOrNull { it.channel == "release" } ?: v.firstOrNull())?.id }
            .onFailure { error = it.message ?: "Could not reach the server" }
    }
    if (type == ServerType.PUMPKIN && ram > 2048) LaunchedEffect(type) { ram = 1024 }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text("New server") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Button(
                    onClick = { onCreated(manager.create(type, version!!, name, ram.coerceAtMost(mem.maxAllocMb)).id) },
                    enabled = version != null && supported,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp),
                ) { Icon(Icons.Rounded.RocketLaunch, null); Spacer(Modifier.width(10.dp)); Text("Create server") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("1 · Choose software", style = MaterialTheme.typography.titleMedium)
            ServerType.entries.forEach { t ->
                val sel = t == type
                ElevatedCard(
                    onClick = { type = t }, shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.elevatedCardColors(containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TypeAvatar(t, 52.dp)
                        Column(Modifier.weight(1f)) {
                            Text(t.label, style = MaterialTheme.typography.titleMedium)
                            Text(t.tagline, style = MaterialTheme.typography.bodySmall)
                            if (t == ServerType.PUMPKIN && Arch.current != Arch.ARM64)
                                Text("Needs an ARM64 phone", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                        RadioButton(sel, onClick = { type = t })
                    }
                }
            }

            Text("2 · Version", style = MaterialTheme.typography.titleMedium)
            when {
                !supported -> InfoBanner("Pumpkin only publishes an Android build for ARM64. This device reports ${Arch.current?.name ?: "an unsupported CPU"}.", Icons.Rounded.Block, BannerKind.ERROR)
                error != null -> InfoBanner(error!!, Icons.Rounded.CloudOff, BannerKind.ERROR, title = "Couldn't load versions")
                versions == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { LoadingIndicator(Modifier.size(40.dp)); Text("Fetching versions…") }
                else -> OutlinedCard(onClick = { picker = true }, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${type.label} version", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(version ?: "Choose…", style = MaterialTheme.typography.titleLarge)
                        }
                        Icon(Icons.Rounded.UnfoldMore, null)
                    }
                }
            }

            Text("3 · Name", style = MaterialTheme.typography.titleMedium)
            TextRow("Server name", name, { name = it }, desc = "Optional. Defaults to “${type.label} server”.")

            Text("4 · Memory", style = MaterialTheme.typography.titleMedium)
            RamSlider(ram, { ram = it }, mem)
            Text("You can change this and everything else later in the server's settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
    }

    if (picker) versions?.let { VersionPickerSheet(it, version, type, onPick = { version = it; picker = false }, onDismiss = { picker = false }) }
}

@Composable
fun VersionPickerSheet(all: List<VersionEntry>, current: String?, type: ServerType, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var snaps by remember { mutableStateOf(false) }
    val hasSnaps = all.any { it.channel != "release" }
    val shown = all.filter { (snaps || it.channel == "release") && it.id.contains(query, true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).fillMaxHeight(0.9f)) {
            Text("${type.label} versions", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search, e.g. 1.21") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = MaterialTheme.shapes.extraLarge)
            if (hasSnaps) Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!snaps, { snaps = false }, { Text("Releases") }); FilterChip(snaps, { snaps = true }, { Text("Include pre-releases") })
            }
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.id }) { v ->
                    ListItem(
                        headlineContent = { Text(v.id) },
                        supportingContent = if (v.channel != "release") ({ Text("Pre-release") }) else null,
                        trailingContent = if (v.id == current) ({ Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clickable { onPick(v.id) },
                    )
                }
            }
        }
    }
}
