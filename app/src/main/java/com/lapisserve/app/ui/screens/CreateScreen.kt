@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lapisserve.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lapisserve.app.core.Arch
import com.lapisserve.app.core.humanMb
import com.lapisserve.app.engine.ServerManager
import com.lapisserve.app.model.PlayerSize
import com.lapisserve.app.model.ServerType
import com.lapisserve.app.model.VersionEntry
import com.lapisserve.app.providers.Providers
import com.lapisserve.app.system.SystemInfo
import com.lapisserve.app.ui.components.*
import com.lapisserve.app.ui.glass.GlassCard
import com.lapisserve.app.ui.theme.LocalAppearance

/** A plain-language way to pick server software on the create screen. */
private data class Goal(val type: ServerType, val title: String, val blurb: String, val badges: List<String>)

private val goals = listOf(
    Goal(ServerType.PAPER, "Play with friends", "Fast and supports plugins. The best choice for most people.", listOf("Recommended", "Plugins")),
    Goal(ServerType.VANILLA, "Pure vanilla", "Exactly like singleplayer, no changes.", emptyList()),
    Goal(ServerType.PURPUR, "Extra gameplay options", "Everything Paper does, plus many more tweaks.", listOf("Plugins")),
    Goal(ServerType.PUMPKIN, "Lightweight & Bedrock", "Tiny and fast. Java and Bedrock players can join.", listOf("Experimental", "Bedrock")),
)

private enum class StartMode(val label: String) { SURVIVAL("Survival"), CREATIVE("Creative"), HARDCORE("Hardcore") }

@Composable
fun CreateScreen(manager: ServerManager, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val ctx = LocalContext.current
    val adv = LocalAppearance.current.advanced
    val mem by remember { SystemInfo.memoryFlow(ctx) }.collectAsState(initial = SystemInfo.memory(ctx))
    var type by remember { mutableStateOf(ServerType.PAPER) }
    var version by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(PlayerSize.FEW) }
    var mode by remember { mutableStateOf(StartMode.SURVIVAL) }
    var customRam by remember { mutableStateOf<Int?>(null) }
    var showRam by remember { mutableStateOf(adv) }
    var seed by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("25565") }
    var eula by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val supported = type != ServerType.PUMPKIN || Arch.current == Arch.ARM64
    val recommended = size.recommendedRamMb(type, mem.comfortMb)
    val ram = (customRam ?: recommended).coerceAtMost(mem.maxAllocMb)

    var versions by remember(type) { mutableStateOf<List<VersionEntry>?>(null) }
    var error by remember(type) { mutableStateOf<String?>(null) }
    LaunchedEffect(type) {
        version = null; versions = null; error = null; customRam = null
        if (!supported) return@LaunchedEffect
        runCatching { Providers.versions(type) }
            .onSuccess { v -> versions = v; version = (v.firstOrNull { it.channel == "release" } ?: v.firstOrNull())?.id }
            .onFailure { error = it.message ?: "Could not reach the server" }
    }
    val latest = versions?.firstOrNull { it.channel == "release" }?.id
    val needsEula = type.isJava
    val ready = version != null && supported && (!needsEula || eula)

    fun create(startNow: Boolean) {
        val made = manager.create(type, version!!, name, ram) { c ->
            c.copy(
                maxPlayers = size.maxPlayers, viewDistance = size.viewDistance, simulationDistance = size.simulationDistance,
                gamemode = if (mode == StartMode.CREATIVE) "creative" else "survival",
                hardcore = mode == StartMode.HARDCORE, difficulty = if (mode == StartMode.HARDCORE) "hard" else c.difficulty,
                acceptEula = needsEula && eula,
                levelSeed = seed.trim(), port = port.toIntOrNull()?.takeIf { it in 1024..65535 } ?: c.port,
            )
        }
        if (startNow) manager.start(made.id)
        onCreated(made.id)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("New server") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = {
            GlassCard(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.extraLarge, padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        listOf("${type.label} ${version ?: "…"}", ram.humanMb(), "up to ${size.maxPlayers} players", mode.label).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (needsEula) Row(Modifier.fillMaxWidth().clickable { eula = !eula }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(eula, { eula = it })
                        Text("I agree to the Minecraft EULA", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton({
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aka.ms/MinecraftEULA")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text("Read") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton({ create(false) }, enabled = ready, modifier = Modifier.height(56.dp)) { Text("Create") }
                        Button({ create(true) }, enabled = ready, modifier = Modifier.weight(1f).height(56.dp)) {
                            Icon(Icons.Rounded.RocketLaunch, null); Spacer(Modifier.width(10.dp)); Text("Create & start", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("What kind of server?", style = MaterialTheme.typography.titleMedium)
            goals.forEach { g ->
                val sel = g.type == type
                GlassCard(
                    Modifier.fillMaxWidth(), onClick = { type = g.type }, shape = MaterialTheme.shapes.large,
                    tint = if (sel) MaterialTheme.colorScheme.primary else null, padding = PaddingValues(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        TypeAvatar(g.type, 48.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(g.title, style = MaterialTheme.typography.titleMedium)
                            Text(g.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(
                                Modifier.padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Pill(g.type.label, muted = true)
                                g.badges.forEach { Pill(it, muted = false) }
                            }
                            if (g.type == ServerType.PUMPKIN && Arch.current != Arch.ARM64)
                                Text("Needs an ARM64 phone", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                        RadioButton(sel, onClick = { type = g.type })
                    }
                }
            }

            when {
                !supported -> InfoBanner("Pumpkin only publishes an Android build for ARM64. This device reports ${Arch.current?.name ?: "an unsupported CPU"}.", Icons.Rounded.Block, BannerKind.ERROR)
                error != null -> InfoBanner(error!!, Icons.Rounded.CloudOff, BannerKind.ERROR, title = "Couldn't load versions")
                versions == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingIndicator(Modifier.size(32.dp)); Text("Finding the latest version…")
                }
                else -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Update, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(if (version == latest) "Latest version: $version" else "Version: $version", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton({ picker = true }) { Text("Change") }
                }
            }

            Text("How many players?", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PlayerSize.entries.forEachIndexed { i, s ->
                    SegmentedButton(size == s, { size = s }, SegmentedButtonDefaults.itemShape(i, PlayerSize.entries.size), icon = {}) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(s.range, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(s.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
            }

            Text("Game mode", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                StartMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(mode == m, { mode = m }, SegmentedButtonDefaults.itemShape(i, StartMode.entries.size)) { Text(m.label) }
                }
            }
            AnimatedVisibility(mode == StartMode.HARDCORE) {
                Text("Hard difficulty and one life: players are banned when they die.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            TextRow("Server name", name, { name = it }, desc = "Optional. Defaults to “${type.label} server”.")

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Memory: ${ram.humanMb()}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (customRam == null) "Recommended for ${size.range} players on this phone" else "Custom",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (customRam != null) TextButton({ customRam = null }) { Text("Reset") }
                TextButton({ showRam = !showRam }) { Text(if (showRam) "Hide" else "Customise") }
            }
            AnimatedVisibility(showRam) { RamSlider(ram, { customRam = it }, mem) }

            if (adv) {
                TextRow("Port", port, { port = it.filter(Char::isDigit).take(5) }, numeric = true, desc = "Default 25565. Must be unique per running server.")
                if (type.isJava) TextRow("World seed", seed, { seed = it }, desc = "Optional. Leave empty for a random world.")
            }
            Text("Everything can be changed later in the server's settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
    }

    if (picker) versions?.let { VersionPickerSheet(it, version, type, onPick = { v -> version = v; picker = false }, onDismiss = { picker = false }) }
}

@Composable
private fun Pill(text: String, muted: Boolean) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.extraLarge, color = if (muted) cs.surfaceVariant.copy(alpha = .6f) else cs.primaryContainer) {
        Text(
            text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall,
            color = if (muted) cs.onSurfaceVariant else cs.onPrimaryContainer,
        )
    }
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
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onPick(v.id) },
                    )
                }
            }
        }
    }
}
