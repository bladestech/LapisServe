@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lapisserve.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.lapisserve.app.core.humanMb
import com.lapisserve.app.engine.ServerManager
import com.lapisserve.app.model.*
import com.lapisserve.app.system.SystemInfo
import com.lapisserve.app.ui.components.*
import com.lapisserve.app.ui.glass.*
import com.lapisserve.app.ui.nav.sharedAvatar
import kotlinx.coroutines.launch
import java.util.Calendar

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..22 -> "Good evening"; else -> "Late night"
}

@Composable
fun ServersScreen(manager: ServerManager, onOpen: (String) -> Unit, onCreate: () -> Unit) {
    val servers by manager.servers.collectAsState()
    val states by manager.states.collectAsState()
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val mem by remember { SystemInfo.memoryFlow(ctx) }.collectAsState(initial = SystemInfo.memory(ctx))
    val dev by rememberDeviceHealth()
    val ip = remember(states) { SystemInfo.localIps().firstOrNull() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val running = servers.count { states[it.id]?.state == RunState.RUNNING }
    val players = servers.sumOf { states[it.id]?.playersOnline ?: 0 }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 188.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ScreenHeader(
                    greeting(),
                    if (servers.isEmpty()) "Let's set up your first server" else buildString {
                        append("$running of ${servers.size} running")
                        if (players > 0) append(" · $players player${if (players == 1) "" else "s"} online")
                    },
                    Modifier.statusBarsPadding().staggerIn(0),
                )
            }
            item { DeviceStrip(mem, dev, Modifier.staggerIn(1)) }
            if (servers.isEmpty()) item { EmptyState(onCreate, Modifier.staggerIn(2)) }
            items(servers, key = { it.id }) { s ->
                val idx = servers.indexOf(s)
                ServerCard(
                    s, states[s.id] ?: ServerRuntime(), address = ip?.let { "$it:${s.port}" }, modifier = Modifier.staggerIn(2 + idx),
                    onOpen = { onOpen(s.id) },
                    onCopy = { a -> clip.setText(AnnotatedString(a)); scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar("Copied $a") } },
                    onToggle = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (manager.isActive(s.id)) manager.stop(s.id)
                        else if (s.type.isJava && !s.acceptEula) onOpen(s.id)   // the EULA prompt lives on the server screen
                        else manager.start(s.id)
                    },
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = onCreate, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New server") },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 92.dp),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 92.dp))
    }
}

@Composable
private fun DeviceStrip(mem: com.lapisserve.app.system.MemorySnapshot, dev: com.lapisserve.app.engine.DeviceHealth, modifier: Modifier = Modifier) {
    val frac = mem.usedMb.toFloat() / mem.totalMb
    val cs = MaterialTheme.colorScheme
    GlassCard(modifier, shape = MaterialTheme.shapes.large, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RingGauge(frac, if (frac > .88f) cs.error else cs.primary, Modifier.size(64.dp), stroke = 7.dp) {
                Text("${(frac * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("This phone", style = MaterialTheme.typography.titleMedium)
                Text("${mem.freeNowMb.humanMb()} free of ${mem.totalMb.humanMb()}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                Text("Up to ${mem.maxAllocMb.humanMb()} for servers", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (dev.batteryPct >= 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(if (dev.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd, null, Modifier.size(16.dp), tint = cs.onSurfaceVariant)
                    Text("${dev.batteryPct}%", style = MaterialTheme.typography.labelLarge)
                }
                Text(dev.thermal, style = MaterialTheme.typography.labelMedium, color = if (dev.thermal in setOf("Throttling", "Hot", "Critical")) cs.error else cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyState(onCreate: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(140.dp).clip(MaterialShapes.Cookie12Sided.toShape()).background(cs.primary.copy(alpha = .18f)), Alignment.Center) {
            Icon(Icons.Rounded.Dns, null, Modifier.size(64.dp), tint = cs.primary)
        }
        Spacer(Modifier.height(8.dp))
        Text("No servers yet", style = MaterialTheme.typography.headlineSmall)
        Text("Pick Vanilla, Paper, Purpur or Pumpkin. LapisServe downloads everything it needs from the official sources.",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(8.dp))
        Button(onClick = onCreate, modifier = Modifier.height(52.dp)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Create a server") }
    }
}

@Composable
private fun ServerCard(
    s: ServerConfig, r: ServerRuntime, address: String?, modifier: Modifier,
    onOpen: () -> Unit, onCopy: (String) -> Unit, onToggle: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val active = r.state != RunState.STOPPED && r.state != RunState.CRASHED
    val running = r.state == RunState.RUNNING
    val tint = when (s.type) {
        ServerType.VANILLA -> cs.tertiary; ServerType.PAPER -> cs.primary; ServerType.PURPUR -> cs.secondary
        ServerType.PUMPKIN -> com.lapisserve.app.ui.theme.LocalStatusColors.current.warn
    }
    GlassCard(modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.extraLarge, tint = if (active) tint else null, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TypeAvatar(s.type, 60.dp, Modifier.sharedAvatar(s.id))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("${s.type.label} ${s.displayVersion} · ${s.ramMb.humanMb()}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            FilledIconButton(
                onClick = onToggle, modifier = Modifier.size(54.dp), shape = if (active) MaterialTheme.shapes.medium else CircleShape,
                colors = if (active) IconButtonDefaults.filledIconButtonColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer) else IconButtonDefaults.filledIconButtonColors(),
            ) { Icon(if (active) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (active) "Stop ${s.name}" else "Start ${s.name}", Modifier.size(28.dp)) }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            StatePill(r.state)
            if (running) {
                MiniChip(Icons.Rounded.Group, "${r.playersOnline}/${r.playersMax.takeIf { it > 0 } ?: s.maxPlayers}")
                r.tps?.let { MiniChip(Icons.Rounded.Speed, "%.1f TPS".format(it), tpsColor(it)) }
            }
        }
        if (running && address != null) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.offset(x = (-8).dp).clip(MaterialTheme.shapes.small).clickable { onCopy(address) }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.ContentCopy, "Copy address", Modifier.size(15.dp), tint = cs.primary)
                Text(address, style = com.lapisserve.app.ui.theme.MonoStyle.copy(fontSize = 13.sp), color = cs.primary)
            }
        }
        if (r.state == RunState.STARTING && r.progress == null) {
            Spacer(Modifier.height(12.dp))
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (running) {
            val cores = r.cpuCoresAllowed.coerceAtLeast(1)
            val cpu = (r.cpuCoresUsed / cores).coerceIn(0f, 1f)
            val ram = if (s.ramMb > 0) r.rssMb.toFloat() / s.ramMb else 0f
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MiniMeter("CPU", "${(cpu * 100).toInt()}%", r.cpuHistory, cs.tertiary, Modifier.weight(1f))
                MiniMeter("Memory", "${(ram * 100).toInt()}%", r.ramHistory, if (ram > .9f) cs.error else cs.primary, Modifier.weight(1f))
            }
        }
        r.progress?.let { p ->
            Spacer(Modifier.height(12.dp))
            Text(p.label + if (p.detail.isNotEmpty()) " · ${p.detail}" else "", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            if (p.fraction != null) LinearWavyProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth()) else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (r.state == RunState.STOPPED && s.installedVersion.isEmpty() && r.message == null && r.progress == null) {
            Spacer(Modifier.height(10.dp))
            Text("Tap ▶ to download and launch. The first start takes a few minutes.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        r.message?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error) }
    }
}

@Composable
private fun MiniChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(Modifier.clip(CircleShape).background(color.copy(alpha = .14f)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = color); Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun MiniMeter(label: String, value: String, history: List<Float>, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelLarge, color = color)
        }
        Sparkline(history, color, Modifier.fillMaxWidth().height(34.dp), minValue = 0f, maxValue = 1f)
    }
}
