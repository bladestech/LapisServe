@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lapisserve.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import com.lapisserve.app.core.Arch
import com.lapisserve.app.core.humanMb
import com.lapisserve.app.engine.CoreTopology
import com.lapisserve.app.engine.LaunchBuilder
import com.lapisserve.app.engine.ServerManager
import com.lapisserve.app.model.*
import com.lapisserve.app.providers.Providers
import com.lapisserve.app.system.*
import com.lapisserve.app.ui.components.*
import com.lapisserve.app.ui.theme.LocalStatusColors
import com.lapisserve.app.ui.theme.MonoStyle


fun uptime(s: Long) = "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)

@Composable
fun Stat(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary); Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

// ------------------------------------------------------------------ console

@Composable
fun ConsoleTab(manager: ServerManager, cfg: ServerConfig, rt: ServerRuntime, notify: (String) -> Unit) {
    val allLines by manager.logFlow(cfg.id).collectAsState()
    var filter by remember { mutableIntStateOf(0) }
    val lines = remember(allLines, filter) {
        when (filter) {
            1 -> allLines.filter { it.kind == LogLine.Kind.WARN || it.kind == LogLine.Kind.ERROR }
            2 -> allLines.filter { it.kind == LogLine.Kind.ERROR }
            else -> allLines
        }
    }
    val clip = LocalClipboardManager.current
    val history = remember { mutableStateListOf<String>() }
    var histIdx by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    fun send() {
        if (input.isNotBlank()) {
            val c = input.trim().removePrefix("/")
            manager.sendCommand(cfg.id, c); history.remove(c); history.add(c); histIdx = -1; input = ""
        }
    }
    var follow by remember { mutableStateOf(true) }
    val live = rt.state == RunState.RUNNING || rt.state == RunState.STARTING
    LaunchedEffect(lines.size, follow) { if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
    val atBottom by remember { derivedStateOf { val l = listState.layoutInfo; l.visibleItemsInfo.lastOrNull()?.index?.let { it >= l.totalItemsCount - 2 } ?: true } }
    LaunchedEffect(atBottom) { follow = atBottom }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
            if (lines.isEmpty()) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Terminal, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
                Text("Console output appears here", color = MaterialTheme.colorScheme.outline)
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(12.dp)) {
                items(lines, key = { it.id }) { l ->
                    Text(
                        l.text, style = MonoStyle, color = when (l.kind) {
                            LogLine.Kind.ERROR -> MaterialTheme.colorScheme.error
                            LogLine.Kind.WARN -> LocalStatusColors.current.warn
                            LogLine.Kind.SYSTEM -> MaterialTheme.colorScheme.tertiary
                            LogLine.Kind.INPUT -> MaterialTheme.colorScheme.primary
                            LogLine.Kind.INFO -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
            androidx.compose.animation.AnimatedVisibility(!follow, Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                SmallFloatingActionButton({ follow = true }) { Icon(Icons.Rounded.KeyboardDoubleArrowDown, "Jump to latest") }
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Warnings", "Errors").forEachIndexed { i, t -> FilterChip(filter == i, { filter = i }, { Text(t) }) }
        }
        FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("list", "save-all", "whitelist list", "difficulty peaceful", "time set day", "weather clear").forEach { c ->
                SuggestionChip({ if (live) input = c }, { Text(c) }, enabled = live)
            }
            AssistChip({ manager.clearLog(cfg.id) }, { Text("Clear") }, leadingIcon = { Icon(Icons.Rounded.ClearAll, null, Modifier.size(18.dp)) })
            val shareCtx = LocalContext.current
            AssistChip({
                val text = allLines.takeLast(400).joinToString("\n") { it.text }
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "${cfg.name} console").putExtra(android.content.Intent.EXTRA_TEXT, text)
                shareCtx.startActivity(android.content.Intent.createChooser(send, "Share console log").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }, { Text("Share log") }, leadingIcon = { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)) })
            AssistChip({ clip.setText(AnnotatedString(allLines.joinToString("\n") { it.text })); notify("Console copied (${allLines.size} lines)") }, { Text("Copy all") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)) })
        }
        Row(Modifier.padding(bottom = 12.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                input, { input = it }, Modifier.weight(1f), enabled = live, singleLine = true, shape = CircleShape,
                placeholder = { Text(if (live) "Type a command" else "Start the server to send commands") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                trailingIcon = if (history.isNotEmpty()) ({
                    IconButton({ histIdx = if (histIdx < 0) history.lastIndex else (histIdx - 1).coerceAtLeast(0); input = history[histIdx] }, enabled = live) { Icon(Icons.Rounded.History, "Previous command") }
                }) else null,
            )
            FilledIconButton({ send() }, Modifier.size(56.dp), enabled = live && input.isNotBlank()) { Icon(Icons.AutoMirrored.Rounded.Send, "Send") }
        }
    }
}

// ------------------------------------------------------------------ settings

@Composable
fun SettingsTab(manager: ServerManager, cfg: ServerConfig, active: Boolean, notify: (String) -> Unit) {
    val ctx = LocalContext.current
    val mem by remember { SystemInfo.memoryFlow(ctx) }.collectAsState(initial = SystemInfo.memory(ctx))
    val cores = remember { Runtime.getRuntime().availableProcessors() }
    val freqs = remember { SystemInfo.coreMaxFreqs() }
    val scores = remember { SystemInfo.coreScores() }
    val adv = com.lapisserve.app.ui.theme.LocalAppearance.current.advanced
    fun set(c: ServerConfig) = manager.update(c)
    var versionPicker by remember { mutableStateOf(false) }
    var versions by remember { mutableStateOf<List<VersionEntry>?>(null) }
    val scope = rememberCoroutineScope()
    val uiScope2 = scope

    val scroll = rememberScrollState()
    val offsets = remember { mutableStateMapOf<String, Int>() }
    fun Modifier.anchor(k: String) = this.onGloballyPositioned { offsets[k] = it.positionInParent().y.toInt() }
    val sections = buildList {
        add("Presets"); add("Memory"); add(if (adv) "Processor" else "Performance"); if (adv && cfg.type.isJava) add("Java")
        add("Gameplay"); add("Network"); add("Behaviour"); add("Backups"); add("Version")
    }
    Column(Modifier.fillMaxSize()) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sections.forEach { n -> AssistChip({ uiScope2.launch { offsets[n]?.let { scroll.animateScrollTo((it - 8).coerceAtLeast(0)) } } }, { Text(n) }) }
    }
    Column(Modifier.weight(1f).verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (active) InfoBanner("Changes are saved instantly and take effect the next time the server starts.", Icons.Rounded.Info, BannerKind.INFO)

        SettingsCard("Quick presets", Icons.Rounded.AutoAwesome, Modifier.anchor("Presets"), subtitle = "One tap to tune for your goal. You can still fine-tune below.") {
            data class Preset(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val desc: String, val apply: (ServerConfig) -> ServerConfig)
            val presets = listOf(
                Preset("Battery saver", Icons.Rounded.BatterySaver, "Small view distance, low CPU priority. Best for a few friends and long sessions.") {
                    it.copy(viewDistance = 4, simulationDistance = 3, cpuPriority = CpuPriority.BACKGROUND, gc = GcPreset.AUTO, preTouch = false, maxPlayers = minOf(it.maxPlayers, 5)) },
                Preset("Balanced", Icons.Rounded.Balance, "Sensible defaults for most phones.") {
                    it.copy(viewDistance = 6, simulationDistance = 4, cpuPriority = CpuPriority.NORMAL, gc = GcPreset.AUTO, preTouch = false) },
                Preset("Performance", Icons.Rounded.Speed, "Bigger world around players. Use while charging with good cooling.") {
                    it.copy(viewDistance = 10, simulationDistance = 8, cpuPriority = CpuPriority.NORMAL, gc = GcPreset.AUTO, preTouch = it.ramMb >= 2048) },
            )
            presets.forEach { p ->
                OutlinedCard(onClick = { set(p.apply(cfg)); notify("${p.label} applied") }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(p.icon, null, tint = MaterialTheme.colorScheme.primary)
                        Column { Text(p.label, style = MaterialTheme.typography.titleSmall); Text(p.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }

        SettingsCard("Memory", Icons.Rounded.Memory, Modifier.anchor("Memory"), subtitle = "Heap given to ${cfg.type.label}") {
            RamSlider(cfg.ramMb, { set(cfg.copy(ramMb = it)) }, mem)
            if (adv && cfg.type.isJava) SwitchRow("Pre-allocate memory", "Reserves the whole heap at launch. Smoother gameplay, but Android sees the memory as used immediately.", cfg.preTouch, { set(cfg.copy(preTouch = it)) })
        }

        if (!adv) SettingsCard("Performance", Icons.Rounded.Speed, Modifier.anchor("Performance"), subtitle = "Tuned automatically for ${remember { SystemInfo.processorName() }}") {
            val plan = CoreTopology.plan(cfg, scores)
            val lines = buildList {
                add(when {
                    plan.dedicatedTick && cfg.type.isJava -> "Game loop gets the fastest core to itself; everything else runs on ${CoreTopology.describe(plan.restMask, cores)}."
                    plan.mask != 0 -> "Runs on the ${plan.activeCores} fastest cores (${CoreTopology.describe(plan.mask, cores)})."
                    else -> "Uses all $cores cores."
                })
                if (cfg.autoTune) add(if (cfg.type.isJava) "Java and server configs tuned for phones." else "Chunk compression and worker threads tuned for phones.")
                if (cfg.autoTune && com.lapisserve.app.engine.TickBoost.supported()) add("Asks Android for CPU boosts when the server is busy, where the phone supports it.")
            }
            lines.forEach { l ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(l, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("Want to change cores, Java or garbage collection? Switch to Advanced in the app's Settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (adv) SettingsCard("Processor", Icons.Rounded.DeveloperBoard, Modifier.anchor("Processor"), subtitle = "${remember { SystemInfo.processorName() }} · $cores cores") {
            val strategy = if (cfg.coreStrategy == CoreStrategy.AUTO && cfg.cpuMask != 0) CoreStrategy.MANUAL else cfg.coreStrategy
            ChoiceRow("Core layout", CoreStrategy.entries, strategy, { it.label }, { s ->
                set(cfg.copy(coreStrategy = s, cpuMask = if (s == CoreStrategy.MANUAL) (cfg.cpuMask.takeIf { it != 0 } ?: CoreTopology.performanceMask(scores)) else 0))
            }, desc = strategy.blurb)
            val plan = CoreTopology.plan(cfg, scores)
            if (strategy == CoreStrategy.MANUAL) {
                Text("Tap cores to choose where the server may run.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CoreSelector(cores, cfg.cpuMask, freqs, { set(cfg.copy(cpuMask = it, coreStrategy = CoreStrategy.MANUAL)) }, scores)
            }
            val summary = when {
                plan.dedicatedTick -> "${plan.strategy.label}: tick thread on core ${plan.tickCore}, other threads on ${CoreTopology.describe(plan.restMask, cores)}."
                plan.mask != 0 -> "${plan.strategy.label}: ${plan.activeCores} of $cores cores (${CoreTopology.describe(plan.mask, cores)})."
                else -> "${plan.strategy.label}: using all $cores cores."
            }
            Text(summary, Modifier.animateContentSize(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (!cfg.type.isJava) SwitchRow("Performance tuning", "Faster chunk compression and worker threads sized to the cores Pumpkin may use.", cfg.autoTune, { set(cfg.copy(autoTune = it)) })
            if (plan.mask != 0) Text("Performance cores run hotter. If the phone throttles on long sessions, try All cores.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceRow("Scheduling priority", CpuPriority.entries, cfg.cpuPriority, { it.label }, { set(cfg.copy(cpuPriority = it)) },
                desc = "Gentle and Background let the phone stay responsive for other apps. Android doesn't allow raising priority above Normal without root.")
        }

        if (adv && cfg.type.isJava) SettingsCard("Java", Icons.Rounded.Coffee, Modifier.anchor("Java"), subtitle = "Runtime and garbage collector") {
            ChoiceRow("Garbage collector", GcPreset.entries, cfg.gc, { it.label }, { set(cfg.copy(gc = it)) }, desc = cfg.gc.blurb)
            ChoiceRow("Java version", listOf(0, 8, 17, 21, 25), cfg.javaMajorOverride, { if (it == 0) "Auto" else "Java $it" }, { set(cfg.copy(javaMajorOverride = it)) },
                desc = "Auto uses what Mojang recommends for ${cfg.version}. Downloaded from Adoptium on first use.")
            SwitchRow("Performance tuning", if (cfg.type.supportsPlugins)
                "Tunes the JVM and Paper/Spigot/Bukkit configs for phones: entity activation ranges, item merging, spawn limits, faster redstone (Alternate Current) and fewer JIT threads. Turn off to edit those files yourself."
                else "Tunes the JVM and server.properties for phones (fewer JIT threads, smaller entity broadcast range).", cfg.autoTune, { set(cfg.copy(autoTune = it)) })
            TextRow("Extra JVM arguments", cfg.extraJvmArgs, { set(cfg.copy(extraJvmArgs = it)) }, desc = "Advanced. Example: -XX:+UseStringDeduplication")
        }

        SettingsCard("World & gameplay", Icons.Rounded.Public, Modifier.anchor("Gameplay")) {
            TextRow("Message of the day", cfg.motd, { set(cfg.copy(motd = it)) })
            ChoiceRow("Game mode", listOf("survival", "creative", "adventure", "spectator"), cfg.gamemode, { it.replaceFirstChar { c -> c.uppercase() } }, { set(cfg.copy(gamemode = it)) })
            if (cfg.type.isJava) ChoiceRow("Difficulty", listOf("peaceful", "easy", "normal", "hard"), cfg.difficulty, { it.replaceFirstChar { c -> c.uppercase() } }, { set(cfg.copy(difficulty = it)) })
            SliderRow("Max players", cfg.maxPlayers, 1..100, { set(cfg.copy(maxPlayers = it)) })
            SliderRow("View distance", cfg.viewDistance, 2..24, { set(cfg.copy(viewDistance = it)) }, { "$it chunks" }, desc = "The biggest lever on CPU and RAM. 4-8 suits a phone.")
            SliderRow("Simulation distance", cfg.simulationDistance, 2..16, { set(cfg.copy(simulationDistance = it)) }, { "$it chunks" }, desc = "How far from players mobs and crops are processed.")
            SwitchRow("Hardcore", "Players are banned on death.", cfg.hardcore, { set(cfg.copy(hardcore = it)) })
            if (cfg.type.isJava) {
                SwitchRow("PvP", null, cfg.pvp, { set(cfg.copy(pvp = it)) })
                SwitchRow("Animals", null, cfg.spawnAnimals, { set(cfg.copy(spawnAnimals = it)) })
                SwitchRow("Monsters", null, cfg.spawnMonsters, { set(cfg.copy(spawnMonsters = it)) })
                SwitchRow("Villagers & NPCs", null, cfg.spawnNpcs, { set(cfg.copy(spawnNpcs = it)) })
                SwitchRow("Generate structures", "Villages, temples and so on. Applies to new chunks.", cfg.generateStructures, { set(cfg.copy(generateStructures = it)) })
                SwitchRow("Allow flight", "Stops survival players being kicked for flying with mods.", cfg.allowFlight, { set(cfg.copy(allowFlight = it)) })
                SwitchRow("Command blocks", null, cfg.commandBlocks, { set(cfg.copy(commandBlocks = it)) })
                if (adv) SliderRow("Spawn protection", cfg.spawnProtection, 0..64, { set(cfg.copy(spawnProtection = it)) }, { "$it blocks" })
                TextRow("World seed", cfg.levelSeed, { set(cfg.copy(levelSeed = it)) }, desc = "Only used when the world is first generated.")
            }
        }

        SettingsCard("Network & access", Icons.Rounded.Lan, Modifier.anchor("Network")) {
            TextRow("Port", cfg.port.toString(), { it.toIntOrNull()?.takeIf { p -> p in 1024..65535 }?.let { p -> set(cfg.copy(port = p)) } }, numeric = true, desc = "Default 25565. Must be unique per running server.")
            SwitchRow("Online mode", "Verify players with Mojang accounts. Requires working DNS inside the runtime; turn off for LAN play or cracked clients.", cfg.onlineMode, { set(cfg.copy(onlineMode = it)) })
            if (adv && cfg.type.isJava) TextRow("Extra hostnames", cfg.extraHosts, { set(cfg.copy(extraHosts = it)) }, singleLine = false,
                desc = "The Java runtime has no system DNS, so LapisServe pre-resolves Mojang, Paper and common plugin hosts. Add any other site your plugins call, separated by spaces.")
            SwitchRow("Whitelist", "Only listed players may join.", cfg.whitelist, { set(cfg.copy(whitelist = it)) })
            if (adv && cfg.type.isJava) SliderRow("Compression threshold", cfg.networkCompression, 0..1024, { set(cfg.copy(networkCompression = it)) }, { if (it == 0) "Always" else "$it bytes" }, step = 64,
                desc = "Higher uses less CPU, more bandwidth. Raise it on a LAN.")
        }

        SettingsCard("Behaviour", Icons.Rounded.Bolt, Modifier.anchor("Behaviour")) {
            SwitchRow("Restart after crash", "Up to 3 automatic restarts.", cfg.autoRestartOnCrash, { set(cfg.copy(autoRestartOnCrash = it)) })
            if (adv) SwitchRow("Keep CPU awake", "Holds a wake lock while running so the server keeps ticking with the screen off.", cfg.keepCpuAwake, { set(cfg.copy(keepCpuAwake = it)) })
            if (adv) SwitchRow("Keep Wi-Fi awake", "Prevents Wi-Fi power saving from adding lag.", cfg.keepWifiAwake, { set(cfg.copy(keepWifiAwake = it)) })
            if (adv) SliderRow("Graceful stop timeout", cfg.stopTimeoutSec, 5..120, { set(cfg.copy(stopTimeoutSec = it)) }, { "${it}s" }, step = 5, desc = "How long to wait for the world to save before force-killing.")
            if (cfg.type.isJava) SwitchRow("Minecraft EULA accepted", "Required to start a Java server.", cfg.acceptEula, { set(cfg.copy(acceptEula = it)) })
        }

        BackupsCard(manager, cfg, active, notify, Modifier.anchor("Backups"))

        SettingsCard("Version", Icons.Rounded.Update, Modifier.anchor("Version"), subtitle = "Installed: ${cfg.installedVersion.ifEmpty { "not downloaded yet" }}") {
            Text("Currently set to ${cfg.version}. Changing it downloads the new build on next start. Back up your world before downgrading.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = {
                versionPicker = true
                if (versions == null) scope.launch { versions = runCatching { Providers.versions(cfg.type) }.getOrNull() }
            }, enabled = !active) { Icon(Icons.Rounded.SwapVert, null); Spacer(Modifier.width(8.dp)); Text("Change version") }
            if (adv) TextRow("Extra server arguments", cfg.extraServerArgs, { set(cfg.copy(extraServerArgs = it)) }, desc = "Appended to the launch command.")
        }
        Spacer(Modifier.height(128.dp))
    }
    }
    if (versionPicker) {
        val v = versions
        if (v == null) AlertDialog({ versionPicker = false }, confirmButton = {}, title = { Text("Loading versions") }, text = { LoadingIndicator() })
        else VersionPickerSheet(v, cfg.version, cfg.type, { set(cfg.copy(version = it)); versionPicker = false }, { versionPicker = false })
    }
}

