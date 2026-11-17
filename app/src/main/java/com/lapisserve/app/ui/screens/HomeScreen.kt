@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lapisserve.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lapisserve.app.core.humanMb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch
import com.lapisserve.app.engine.ServerManager
import com.lapisserve.app.model.*
import com.lapisserve.app.system.SystemInfo
import com.lapisserve.app.ui.components.*

@Composable
fun HomeScreen(manager: ServerManager, onOpen: (String) -> Unit, onCreate: () -> Unit, onTuning: () -> Unit) {
    val servers by manager.servers.collectAsState()
    val states by manager.states.collectAsState()
    val ctx = LocalContext.current
    val mem by remember { SystemInfo.memoryFlow(ctx) }.collectAsState(initial = SystemInfo.memory(ctx))
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val ip = remember(states) { SystemInfo.localIps().firstOrNull() }
    val running = servers.count { states[it.id]?.state == RunState.RUNNING }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("LapisServe") },
                subtitle = { Text(if (servers.isEmpty()) "Minecraft servers, in your pocket" else "$running of ${servers.size} running") },
                actions = {
                    FilledTonalIconButton(onClick = onTuning) { Icon(Icons.Rounded.Tune, "Device tuning") }
                    Spacer(Modifier.width(8.dp))
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onCreate, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New server") })
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DeviceStrip(mem, onTuning) }
            if (servers.isEmpty()) item { EmptyState(onCreate) }
            items(servers, key = { it.id }) { s ->
                ServerCard(s, states[s.id] ?: ServerRuntime(), address = ip?.let { "$it:${s.port}" }, onOpen = { onOpen(s.id) },
                    onCopy = { a -> clip.setText(AnnotatedString(a)); scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar("Copied $a - share it with friends on your Wi-Fi") } },
                    onToggle = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (manager.isActive(s.id)) manager.stop(s.id) else {
                            manager.start(s.id)
                            if (manager.runtime(s.id).message != null) scope.launch { snackbar.showSnackbar(manager.runtime(s.id).message!!, actionLabel = "Open") .let { r -> if (r == SnackbarResult.ActionPerformed) onOpen(s.id) } }
                        }
                    })
            }
        }
    }
}

@Composable
private fun DeviceStrip(mem: com.lapisserve.app.system.MemorySnapshot, onTuning: () -> Unit) {
    val frac = mem.usedMb.toFloat() / mem.totalMb
    Card(
        onClick = onTuning, shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { frac }, modifier = Modifier.size(56.dp), strokeWidth = 6.dp,
                    color = if (frac > .85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                Text("${(frac * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }
            Column(Modifier.weight(1f)) {
                Text("This device", style = MaterialTheme.typography.titleMedium)
                Text("${mem.usedMb.humanMb()} used · ${mem.freeNowMb.humanMb()} free", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Servers may use up to ${mem.maxAllocMb.humanMb()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyState(onCreate: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(132.dp).clip(MaterialShapes.Cookie12Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Dns, null, Modifier.size(60.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(8.dp))
        Text("No servers yet", style = MaterialTheme.typography.headlineSmall)
        Text("Pick Vanilla, Paper or Pumpkin. LapisServe downloads everything it needs straight from the official sources.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(8.dp))
        Button(onClick = onCreate, contentPadding = ButtonDefaults.ContentPadding) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Create a server") }
    }
}

@Composable
private fun ServerCard(s: ServerConfig, r: ServerRuntime, address: String?, onOpen: () -> Unit, onCopy: (String) -> Unit, onToggle: () -> Unit) {
    val active = r.state != RunState.STOPPED && r.state != RunState.CRASHED
    Card(
        onClick = onOpen, shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.animateContentSize(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TypeAvatar(s.type)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(s.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text("${s.type.label} ${s.displayVersion} · ${s.ramMb.humanMb()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                // Morphs between a round play button and a squarer stop button.
                FilledIconButton(
                    onClick = onToggle, modifier = Modifier.size(52.dp),
                    shape = if (active) IconButtonDefaults.filledShape else CircleShape,
                    colors = if (active) IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                    else IconButtonDefaults.filledIconButtonColors(),
                ) { Icon(if (active) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (active) "Stop" else "Start", Modifier.size(28.dp)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatePill(r.state)
                if (r.state == RunState.RUNNING && address != null) AssistChip(onClick = { onCopy(address) }, label = { Text(address, maxLines = 1) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)) })
            }
            if (r.progress != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(r.progress.label + (if (r.progress.detail.isNotEmpty()) " · ${r.progress.detail}" else ""), style = MaterialTheme.typography.bodySmall)
                    if (r.progress.fraction != null) LinearWavyProgressIndicator(progress = { r.progress.fraction }, modifier = Modifier.fillMaxWidth())
                    else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if ((r.state == RunState.RUNNING || r.state == RunState.STARTING) && s.ramMb > 0) {
                val allowed = r.cpuCoresAllowed.coerceAtLeast(1)
                val cpu = (r.cpuCoresUsed / allowed).coerceIn(0f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MeterRow("Memory", "${r.rssMb.humanMb()} of ${s.ramMb.humanMb()}", r.rssMb.toFloat() / s.ramMb, MaterialTheme.colorScheme.primary)
                    MeterRow("CPU", "${(cpu * 100).toInt()}% of $allowed ${if (allowed == 1) "core" else "cores"}", cpu, MaterialTheme.colorScheme.tertiary)
                }
            }
            if (r.state == RunState.STOPPED && s.installedVersion.isEmpty() && r.message == null)
                Text("Tap ▶ to download and launch. First start takes a few minutes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            r.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
