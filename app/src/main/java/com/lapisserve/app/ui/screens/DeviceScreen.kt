@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lapisserve.app.ui.screens

import android.net.wifi.WifiManager
import android.os.StatFs
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.lapisserve.app.core.humanBytes
import com.lapisserve.app.core.humanMb
import com.lapisserve.app.engine.ServerManager
import com.lapisserve.app.system.*
import com.lapisserve.app.ui.components.*
import com.lapisserve.app.ui.glass.*
import com.lapisserve.app.ui.theme.LocalStatusColors

@Composable
fun DeviceScreen(manager: ServerManager) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }       // re-check after returning from system settings
    val advice = remember(tick) { DevOptionsAdvisor.evaluate(ctx) }
    val dev = remember(tick) { SystemInfo.device(ctx) }
    val mem by remember { SystemInfo.memoryFlow(ctx) }.collectAsState(initial = SystemInfo.memory(ctx))
    val todo = advice.count { it.verdict == Verdict.RECOMMENDED }
    val readiness = if (advice.isEmpty()) 1f else advice.count { it.verdict == Verdict.GOOD }.toFloat() / advice.size
    var refresh by remember { mutableIntStateOf(0) }
    val runtimes = remember(refresh) { manager.java.installed() }
    val storage = remember(tick) { runCatching { StatFs(ctx.filesDir.path).let { it.availableBytes to it.totalBytes } }.getOrNull() }
    val wifi = remember(tick) { runCatching {
        @Suppress("DEPRECATION") val i = (ctx.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager).connectionInfo
        if (i.linkSpeed > 0) "${i.linkSpeed} Mbps" to WifiManager.calculateSignalLevel(i.rssi, 5) else null
    }.getOrNull() }
    val cs = MaterialTheme.colorScheme; val st = LocalStatusColors.current
    var n = 0

    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("Device", dev.model, Modifier.statusBarsPadding().staggerIn(n++)) }

        // ---- hardware hero
        item {
            GlassCard(Modifier.staggerIn(n++), shape = MaterialTheme.shapes.extraLarge) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(52.dp).clip(MaterialShapes.Cookie6Sided.toShape()).background(cs.primary.copy(alpha = .2f)), Alignment.Center) { Icon(Icons.Rounded.Memory, null, tint = cs.primary) }
                    Column(Modifier.weight(1f)) {
                        Text(dev.processor, style = MaterialTheme.typography.titleLarge)
                        Text("${dev.cores} cores · ${dev.abi}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        if (dev.coreLayout.isNotEmpty()) Text(dev.coreLayout, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat(Icons.Rounded.Android, "Android ${dev.androidVersion}")
                    if (dev.batteryPct >= 0) Stat(if (dev.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd, "${dev.batteryPct}%" + if (dev.charging) " charging" else "")
                    Stat(Icons.Rounded.Thermostat, dev.thermal)
                    wifi?.let { Stat(Icons.Rounded.Wifi, it.first) }
                }
            }
        }

        // ---- memory + readiness gauges
        item {
            Row(Modifier.fillMaxWidth().staggerIn(n++), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val f = mem.usedMb.toFloat() / mem.totalMb
                GaugeTile("Memory", Icons.Rounded.Memory, f, "${(f * 100).toInt()}%", Modifier.weight(1f).fillMaxHeight(),
                    color = if (f > .88f) cs.error else cs.primary, sub = "in use", footer = "${mem.freeNowMb.humanMb()} free · ${mem.totalMb.humanMb()}")
                GaugeTile("Hosting ready", Icons.Rounded.Verified, readiness, "${(readiness * 100).toInt()}%", Modifier.weight(1f).fillMaxHeight(),
                    color = if (todo == 0) st.good else st.warn, sub = if (todo == 0) "all set" else "$todo to fix", footer = "Android settings checked")
            }
        }
        item {
            GlassCard(Modifier.staggerIn(n++), shape = MaterialTheme.shapes.large) {
                Text("Memory budget", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("Servers can use up to ${mem.maxAllocMb.humanMb()} (90%). Above ${mem.riskyMb.humanMb()} is not recommended; ${mem.comfortMb.humanMb()} and below is comfortable.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                if (mem.usedMb > mem.maxAllocMb) {
                    Spacer(Modifier.height(10.dp))
                    InfoBanner("Android is already using more than the ${mem.maxAllocMb.humanMb()} that can be allocated, so no server can safely start. Close some apps.", Icons.Rounded.Error, BannerKind.ERROR)
                }
            }
        }
        storage?.let { (free, total) ->
            item {
                val used = 1f - free.toFloat() / total
                GlassCard(Modifier.staggerIn(n++), shape = MaterialTheme.shapes.large) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Storage, null, tint = cs.primary); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text("Storage", style = MaterialTheme.typography.titleMedium); Text("${free.humanBytes()} free of ${total.humanBytes()}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                    }
                    Spacer(Modifier.height(10.dp))
                    MeterRow("Used", "${(used * 100).toInt()}%", used, if (used > .9f) cs.error else cs.tertiary)
                }
            }
        }

        // ---- readiness
        item {
            InfoBanner(
                if (todo == 0) "Everything we can check looks good. Your server should stay alive in the background." else "$todo ${if (todo == 1) "setting" else "settings"} could keep your server from running smoothly. Tap a card for details.",
                if (todo == 0) Icons.Rounded.CheckCircle else Icons.Rounded.Lightbulb, if (todo == 0) BannerKind.GOOD else BannerKind.WARN,
                Modifier.staggerIn(n++), title = if (todo == 0) "You're all set" else "Get the most out of this phone",
            )
        }
        item { Text("Android settings that matter", Modifier.padding(start = 4.dp, top = 4.dp), style = MaterialTheme.typography.titleMedium) }
        items(advice.size) { i -> AdviceCard(advice[i], Modifier.staggerIn(minOf(i, 6) + 3)) { a -> DevOptionsAdvisor.act(ctx, a) } }

        // ---- storage of runtimes
        item {
            SettingsCard("Downloaded runtimes", Icons.Rounded.Download, Modifier.staggerIn(n++), subtitle = "Fetched from Adoptium and Alpine on demand · never bundled") {
                if (runtimes.isEmpty()) Text("Nothing downloaded yet. Java is installed automatically the first time a Vanilla, Paper or Purpur server starts.", style = MaterialTheme.typography.bodyMedium)
                runtimes.forEach { (major, size) ->
                    ListItem(
                        headlineContent = { Text("Temurin Java $major") }, supportingContent = { Text(size.humanBytes()) },
                        leadingContent = { Icon(Icons.Rounded.Coffee, null) },
                        trailingContent = { IconButton({ manager.java.remove(major); refresh++ }) { Icon(Icons.Rounded.Delete, "Remove Java $major") } },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    )
                }
                if (runtimes.isNotEmpty()) Text("C runtime (musl): ${manager.java.muslSize().humanBytes()}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AdviceCard(a: Advice, modifier: Modifier, onAction: (AdviceAction) -> Unit) {
    val st = LocalStatusColors.current; val cs = MaterialTheme.colorScheme
    var open by rememberSaveable(a.id) { mutableStateOf(a.verdict == Verdict.RECOMMENDED && a.important) }
    val (color, icon) = when (a.verdict) {
        Verdict.GOOD -> st.good to Icons.Rounded.CheckCircle
        Verdict.RECOMMENDED -> st.warn to Icons.Rounded.Lightbulb
        Verdict.UNKNOWN -> cs.outline to Icons.Rounded.HelpOutline
    }
    GlassCard(modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.large, tint = if (a.verdict == Verdict.RECOMMENDED) color else null, onClick = { open = !open }, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(color.copy(alpha = .18f)), Alignment.Center) { Icon(icon, null, tint = color) }
            Column(Modifier.weight(1f)) {
                Text(a.title, style = MaterialTheme.typography.titleMedium)
                Text(a.current, style = MaterialTheme.typography.bodySmall, color = if (a.verdict == Verdict.UNKNOWN) cs.onSurfaceVariant else color)
            }
            if (a.important && a.verdict == Verdict.RECOMMENDED) AssistChip({}, { Text("Important") }, colors = AssistChipDefaults.assistChipColors(labelColor = cs.error))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(a.why, style = MaterialTheme.typography.bodyMedium)
                Text("How: ${a.how}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                a.action?.let { act ->
                    if (a.verdict != Verdict.GOOD) FilledTonalButton({ onAction(act) }) { Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(act.label) }
                    else TextButton({ onAction(act) }) { Text(act.label) }
                }
            }
        }
    }
}
