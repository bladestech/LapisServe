@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lapisserve.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.lapisserve.app.core.humanBytes
import com.lapisserve.app.core.humanMb
import com.lapisserve.app.engine.InsightAction
import com.lapisserve.app.engine.Insights
import com.lapisserve.app.engine.LaunchBuilder
import com.lapisserve.app.engine.ServerManager
import com.lapisserve.app.model.*
import com.lapisserve.app.system.SystemInfo
import com.lapisserve.app.ui.components.*
import com.lapisserve.app.ui.glass.*
import com.lapisserve.app.ui.nav.sharedAvatar
import com.lapisserve.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.haze

private val BottomBarSpace = 118.dp

@Composable
fun ServerScreen(manager: ServerManager, id: String, startTab: String, onBack: () -> Unit, onTuning: () -> Unit) {
    val configs by manager.servers.collectAsState()
    val cfg = configs.firstOrNull { it.id == id }
    if (cfg == null) { LaunchedEffect(Unit) { onBack() }; return }
    val states by manager.states.collectAsState()
    val rt = states[id] ?: ServerRuntime()
    val active = rt.state != RunState.STOPPED && rt.state != RunState.CRASHED
    var tab by rememberSaveable { mutableStateOf(startTab) }
    var eula by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmKill by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val notify: (String) -> Unit = { m -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(m) } }
    fun start() { if (cfg.type.isJava && !cfg.acceptEula) eula = true else manager.start(id) }
    val toggle = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); if (active) manager.stop(id) else start() }

    val tabs = remember(cfg.type) {
        buildList {
            add(TabItem("Overview", "Overview", Icons.Rounded.Dashboard))
            add(TabItem("Console", "Console", Icons.Rounded.Terminal))
            add(TabItem("Players", "Players", Icons.Rounded.Group))
            if (cfg.type.supportsPlugins) add(TabItem("Plugins", "Plugins", Icons.Rounded.Extension))
            add(TabItem("Settings", "Settings", Icons.Rounded.Settings))
        }
    }
    val current = if (tabs.any { it.key == tab }) tab else "Overview"
    val imeVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0

    val screenHaze = remember { dev.chrisbanes.haze.HazeState() }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
                Text(cfg.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Box {
                    GlassIconButton(Icons.Rounded.MoreVert, "More", { menu = true })
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Restart") }, { menu = false; manager.restart(id) }, leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) }, enabled = active)
                        DropdownMenuItem({ Text("Force stop") }, { menu = false; manager.forceStop(id) }, leadingIcon = { Icon(Icons.Rounded.DoNotDisturbOn, null) }, enabled = active)
                        DropdownMenuItem({ Text("Delete server…") }, { menu = false; confirmDelete = true }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
                    }
                }
            }
            AnimatedContent(
                targetState = current, modifier = Modifier.weight(1f), label = "serverTab",
                transitionSpec = {
                    val dir = if (tabs.indexOfFirst { it.key == targetState } > tabs.indexOfFirst { it.key == initialState }) 1 else -1
                    (fadeIn(tween(230, 40)) + slideInHorizontally(spring(0.9f, 520f)) { dir * it / 10 }) togetherWith
                        (fadeOut(tween(120)) + slideOutHorizontally(spring(0.9f, 520f)) { -dir * it / 10 })
                },
            ) { t ->
                when (t) {
                    "Overview" -> OverviewTab(manager, cfg, rt, active, toggle, notify, onSettings = { tab = "Settings" }, onTuning = onTuning, onPlayers = { tab = "Players" },
                        onRestart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); manager.restart(id); notify("Restarting ${cfg.name}…") },
                        onKill = { confirmKill = true })
                    "Console" -> Box(Modifier.padding(bottom = if (imeVisible) 0.dp else BottomBarSpace - 14.dp)) { ConsoleTab(manager, cfg, rt, notify) }
                    "Players" -> PlayersTab(manager, cfg, rt, notify)
                    "Plugins" -> PluginsTab(manager, cfg, active, notify)
                    else -> SettingsTab(manager, cfg, active, notify)
                }
            }
        }

        AnimatedVisibility(
            visible = !imeVisible, modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(0.8f, 420f)) { it } + fadeIn(), exit = slideOutVertically(tween(160)) { it } + fadeOut(tween(100)),
        ) { GlassTabBar(tabs, current, { tab = it }, Modifier.navigationBarsPadding().padding(bottom = 12.dp), screenHaze) }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp).navigationBarsPadding())
    }

    if (eula) AlertDialog(
        onDismissRequest = { eula = false }, icon = { Icon(Icons.Rounded.Gavel, null) }, title = { Text("Minecraft EULA") },
        text = { Text("Running a Minecraft server means agreeing to Mojang's End User License Agreement (aka.ms/MinecraftEULA). By continuing you confirm that you accept it.") },
        confirmButton = { Button({ eula = false; manager.update(cfg.copy(acceptEula = true)); manager.start(id) }) { Text("I agree · Start") } },
        dismissButton = { TextButton({ eula = false }) { Text("Cancel") } },
    )
    if (confirmKill) AlertDialog(
        onDismissRequest = { confirmKill = false }, icon = { Icon(Icons.Rounded.Dangerous, null) }, title = { Text("Force stop ${cfg.name}?") },
        text = { Text("This kills the server immediately without saving. Anything since the last autosave can be lost, and a world that is mid-save could be damaged. Use it only if the server is stuck. A normal Stop is much safer.") },
        confirmButton = { Button({ confirmKill = false; haptic.performHapticFeedback(HapticFeedbackType.LongPress); manager.forceStop(id); notify("Force stopped ${cfg.name}") }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Force stop") } },
        dismissButton = { TextButton({ confirmKill = false }) { Text("Keep running") } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, icon = { Icon(Icons.Rounded.DeleteForever, null) }, title = { Text("Delete ${cfg.name}?") },
        text = { Text("This permanently deletes the server, its worlds and all files. This cannot be undone.") },
        confirmButton = { Button({ confirmDelete = false; manager.delete(id) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep") } },
    )
}

@Composable
private fun typeTint(type: ServerType): androidx.compose.ui.graphics.Color = when (type) {
    ServerType.VANILLA -> MaterialTheme.colorScheme.tertiary
    ServerType.PAPER -> MaterialTheme.colorScheme.primary
    ServerType.PURPUR -> MaterialTheme.colorScheme.secondary
    ServerType.PUMPKIN -> LocalStatusColors.current.warn
}

// ------------------------------------------------------------------ overview

@Composable
private fun OverviewTab(
    manager: ServerManager, cfg: ServerConfig, rt: ServerRuntime, active: Boolean, onToggle: () -> Unit,
    notify: (String) -> Unit, onSettings: () -> Unit, onTuning: () -> Unit, onPlayers: () -> Unit, onRestart: () -> Unit, onKill: () -> Unit,
) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val running = rt.state == RunState.RUNNING
    val live = running || rt.state == RunState.STARTING
    val dev by rememberDeviceHealth()
    val ips = remember(rt.state) { SystemInfo.localIps() }
    val cores = rt.cpuCoresAllowed.coerceAtLeast(1)
    val insights = remember(cfg, rt.tps, rt.mspt, rt.rssMb, rt.cpuCoresUsed, rt.state, dev) { Insights.forServer(cfg, rt, dev, cores) }
    var chart by rememberSaveable { mutableIntStateOf(if (cfg.type == ServerType.PUMPKIN) 1 else 0) }
    val st = LocalStatusColors.current
    val cs = MaterialTheme.colorScheme
    val advancedMode = com.lapisserve.app.ui.theme.LocalAppearance.current.advanced
    var n = 0

    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = BottomBarSpace + 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---- hero
        item {
            GlassCard(Modifier.staggerIn(n++), shape = MaterialTheme.shapes.extraLarge, tint = typeTint(cfg.type)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StateAura(rt.state, Modifier.size(76.dp)) { TypeAvatar(cfg.type, 76.dp, Modifier.sharedAvatar(cfg.id)) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${cfg.type.label} ${cfg.displayVersion}", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        StatePill(rt.state)
                        if (running) Text("Up ${formatUptime(rt.uptimeSec)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(16.dp))
                val stopping = rt.state == RunState.STOPPING
                val container by animateColorAsState(if (active) cs.errorContainer else cs.primary, tween(350), label = "btnBg")
                val onContainer by animateColorAsState(if (active) cs.onErrorContainer else cs.onPrimary, tween(350), label = "btnFg")
                val canRestart = rt.state == RunState.RUNNING || rt.state == RunState.STARTING
                val canKill = canRestart || stopping
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onToggle, enabled = !stopping, modifier = Modifier.weight(1f).height(58.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = onContainer, disabledContainerColor = container.copy(alpha = .6f), disabledContentColor = onContainer),
                    ) {
                        AnimatedContent(
                            (rt.state == RunState.INSTALLING) to (if (stopping) 2 else if (active) 1 else 0), label = "btnLabel",
                            transitionSpec = { (slideInVertically(spring(0.8f, 500f)) { it / 2 } + fadeIn(tween(180))) togetherWith (slideOutVertically(tween(160)) { -it / 2 } + fadeOut(tween(120))) },
                        ) { (setup, mode) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (mode == 2) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp, color = onContainer)
                                else Icon(if (mode == 1) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null)
                                Spacer(Modifier.width(10.dp))
                                Text(if (mode == 2) "Stopping…" else if (setup) "Cancel setup" else if (mode == 1) "Stop" else "Start server", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            }
                        }
                    }
                    AnimatedVisibility(
                        canKill, enter = expandHorizontally(spring(0.7f, 420f)) + fadeIn(tween(200)) + scaleIn(spring(0.6f, 420f), initialScale = .6f),
                        exit = shrinkHorizontally(tween(180)) + fadeOut(tween(120)) + scaleOut(targetScale = .6f),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalIconButton(onRestart, Modifier.size(58.dp), enabled = canRestart) { Icon(Icons.Rounded.RestartAlt, "Restart server", Modifier.size(26.dp)) }
                            FilledIconButton(
                                onKill, Modifier.size(58.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = cs.error.copy(alpha = .85f), contentColor = cs.onError),
                            ) { Icon(Icons.Rounded.Dangerous, "Force stop (kill)", Modifier.size(26.dp)) }
                        }
                    }
                }
                AnimatedVisibility(stopping) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        Text("Saving the world and shutting down. If it takes too long, use the red button to force stop.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                AnimatedVisibility(running && ips.isNotEmpty()) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ips.forEach { ip ->
                                val addr = "$ip:${cfg.port}"
                                AssistChip({ clip.setText(AnnotatedString(addr)); notify("Copied $addr") }, { Text(addr, maxLines = 1) },
                                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)) })
                            }
                            FilledTonalIconButton({
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(android.content.Intent.EXTRA_TEXT, "Join my Minecraft server: ${ips.first()}:${cfg.port}")
                                ctx.startActivity(android.content.Intent.createChooser(send, "Share address").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }, Modifier.size(38.dp)) { Icon(Icons.Rounded.Share, "Share address", Modifier.size(18.dp)) }
                        }
                    }
                }
            }
        }

        rt.progress?.let { p ->
            item {
                GlassCard(Modifier.staggerIn(n++), shape = MaterialTheme.shapes.large, tint = cs.primary) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LapisLoader(size = 56.dp)
                        Column { Text(p.label, style = MaterialTheme.typography.titleMedium); if (p.detail.isNotEmpty()) Text(p.detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (p.fraction != null) LinearWavyProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth()) else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        rt.message?.let { item { InfoBanner(it, Icons.Rounded.ErrorOutline, BannerKind.ERROR, Modifier.staggerIn(n++), title = if (rt.state == RunState.CRASHED) "Server stopped" else "Heads up") } }

        if (live) {
            // ---- insights first: what needs attention
            if (running) items(insights, key = { it.title }) { i -> InsightCard(i, Modifier.staggerIn(n++)) { a -> if (a == InsightAction.OPEN_SETTINGS) onSettings() else onTuning() } }

            // ---- vitals: compact pills, two per row
            item {
                val tps = rt.tps
                val ramFrac = if (cfg.ramMb > 0) rt.rssMb.toFloat() / cfg.ramMb else 0f
                val cpuFrac = (rt.cpuCoresUsed / cores).coerceIn(0f, 1f)
                val maxSlots = rt.playersMax.takeIf { it > 0 } ?: cfg.maxPlayers
                val pills = buildList {
                    if (cfg.type == ServerType.PUMPKIN || (running && tps == null && rt.uptimeSec > 25))
                        add(Pill(Icons.Rounded.Speed, "—", if (cfg.type == ServerType.PUMPKIN) "TPS · not reported" else "TPS · waiting", cs.outline))
                    else add(Pill(Icons.Rounded.Speed, tps?.let { "%.1f".format(it) } ?: "…", "TPS", tpsColor(tps), ring = (tps ?: 0f) / 20f))
                    add(Pill(Icons.Rounded.Group, "${rt.playersOnline} / $maxSlots", "Players", cs.tertiary, onClick = onPlayers))
                    add(Pill(Icons.Rounded.Memory, "${(ramFrac * 100).toInt()}%", "RAM ${rt.rssMb.humanMb()}", if (ramFrac > .9f) cs.error else cs.primary, ring = ramFrac))
                    add(Pill(Icons.Rounded.DeveloperBoard, "${(cpuFrac * 100).toInt()}%", "CPU · %.1f of $cores".format(rt.cpuCoresUsed), if (cpuFrac > .9f) cs.error else cs.tertiary, ring = cpuFrac))
                    add(Pill(Icons.Rounded.Timer, rt.mspt?.let { "%.1f ms".format(it) } ?: "—", "Tick time",
                        rt.mspt?.let { if (it >= 45f) cs.error else if (it >= 35f) st.warn else st.good } ?: cs.outline))
                    add(Pill(Icons.Rounded.NetworkPing, if (rt.pingMs >= 0) "${rt.pingMs} ms" else "—", "Latency", cs.primary))
                    add(Pill(Icons.Rounded.Schedule, formatUptime(rt.uptimeSec), "Uptime", cs.secondary))
                    add(Pill(Icons.Rounded.Public, rt.worldBytes.humanBytes(), "World size", cs.secondary))
                }
                Column(Modifier.staggerIn(n++), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    pills.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { PillStat(it, Modifier.weight(1f)) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // ---- history chart
            item {
                GlassCard(Modifier.staggerIn(n++), shape = MaterialTheme.shapes.large) {
                    val options = buildList { if (cfg.type != ServerType.PUMPKIN) add("TPS"); add("CPU"); add("RAM"); add("Players") }
                    val sel = options[chart.coerceIn(0, options.lastIndex)]
                    Text("Last 6 minutes", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        options.forEachIndexed { i, o ->
                            SegmentedButton(i == chart.coerceIn(0, options.lastIndex), { chart = i }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(o, maxLines = 1) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val (series, color, lo, hi) = when (sel) {
                        "TPS" -> Quad(rt.tpsHistory, tpsColor(rt.tps), 0f, 20f)
                        "CPU" -> Quad(rt.cpuHistory, cs.tertiary, 0f, 1f)
                        "RAM" -> Quad(rt.ramHistory, cs.primary, 0f, 1f)
                        else -> Quad(rt.playerHistory, cs.secondary, 0f, null)
                    }
                    AnimatedContent(sel, label = "chart") { _ -> Sparkline(series, color, Modifier.fillMaxWidth().height(130.dp), minValue = lo, maxValue = hi) }
                }
            }
        } else {
            if (cfg.installedVersion.isEmpty() && rt.state == RunState.STOPPED) item {
                GlassCard(Modifier.staggerIn(n++), shape = MaterialTheme.shapes.large, tint = cs.tertiary) {
                    Text("Ready when you are", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    listOf(
                        "Tap Start. LapisServe downloads " + (if (cfg.type.isJava) "Java and " else "") + "${cfg.type.label} ${cfg.displayVersion} for you." to Icons.Rounded.CloudDownload,
                        "The first launch takes a few minutes. Keep the app open." to Icons.Rounded.HourglassTop,
                        "When it says Running, share the address with friends." to Icons.Rounded.Share,
                    ).forEach { (t, ic) ->
                        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(ic, null, tint = cs.tertiary); Text(t, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onSettings) { Text("Adjust settings first") }
                }
            } else item {
                GlassCard(Modifier.staggerIn(n++), shape = MaterialTheme.shapes.large) {
                    Text("Start the server to see live vitals", style = MaterialTheme.typography.titleMedium)
                    Text("TPS, players, memory, CPU and history charts appear here while it runs.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
        }

        // ---- always: resources summary
        item {
            SettingsCard("Resources", Icons.Rounded.Tune, Modifier.staggerIn(n++)) {
                val total = Runtime.getRuntime().availableProcessors()
                val eff = LaunchBuilder.effectiveCores(cfg, total, remember { com.lapisserve.app.system.SystemInfo.coreScores() })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat(Icons.Rounded.Memory, "${cfg.ramMb.humanMb()} RAM"); Stat(Icons.Rounded.DeveloperBoard, "$eff of $total cores")
                    val advanced = advancedMode
                    if (advanced && cfg.type.isJava) Stat(Icons.Rounded.CleaningServices, LaunchBuilder.resolveGc(cfg, eff).label)
                    Stat(Icons.Rounded.Public, if (advanced) "View ${cfg.viewDistance} · Sim ${cfg.simulationDistance}" else "View ${cfg.viewDistance} chunks")
                    Stat(Icons.Rounded.Group, "Up to ${cfg.maxPlayers} players")
                }
                TextButton(onSettings, contentPadding = PaddingValues(0.dp)) { Text("Edit resources") }
            }
        }
        if (advancedMode) item {
            Text(manager.dir(cfg.id).path, Modifier.padding(horizontal = 8.dp), style = com.lapisserve.app.ui.theme.MonoStyle, color = cs.outline)
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
