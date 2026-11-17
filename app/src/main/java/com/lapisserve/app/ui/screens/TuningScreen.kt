@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lapisserve.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.lapisserve.app.ui.theme.LocalStatusColors

@Composable
fun TuningScreen(manager: ServerManager, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // Re-check whenever the user returns from the system settings screens.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val advice = remember(tick) { DevOptionsAdvisor.evaluate(ctx) }
    val dev = remember(tick) { SystemInfo.device(ctx) }
    val mem by remember { SystemInfo.memoryFlow(ctx) }.collectAsState(initial = SystemInfo.memory(ctx))
    val todo = advice.count { it.verdict == Verdict.RECOMMENDED }
    var refresh by remember { mutableIntStateOf(0) }
    val runtimes = remember(refresh) { manager.java.installed() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { LargeTopAppBar(title = { Text("Device tuning") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val st = LocalStatusColors.current
            InfoBanner(
                if (todo == 0) "Everything we can check looks good. Your server should stay alive in the background." else "$todo ${if (todo == 1) "setting" else "settings"} could keep your server from running smoothly. Tap a card for details.",
                if (todo == 0) Icons.Rounded.CheckCircle else Icons.Rounded.Lightbulb, if (todo == 0) BannerKind.GOOD else BannerKind.WARN,
                title = if (todo == 0) "You're all set" else "Get the most out of this phone",
            )

            SettingsCard("This device", Icons.Rounded.PhoneAndroid, subtitle = dev.model) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(dev.processor, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOf("${dev.cores} cores", dev.abi).joinToString(" · ") + if (dev.coreLayout.isNotEmpty()) "\n${dev.coreLayout}" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(Icons.Rounded.Android, "Android ${dev.androidVersion} (API ${dev.sdk})")
                    Chip(if (dev.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd, if (dev.batteryPct >= 0) "${dev.batteryPct}%" + if (dev.charging) " charging" else "" else "Battery n/a")
                    Chip(Icons.Rounded.Thermostat, dev.thermal)
                }
                Text("Memory", style = MaterialTheme.typography.titleSmall)
                val usedFrac = mem.usedMb.toFloat() / mem.totalMb
                LinearProgressIndicator({ usedFrac }, Modifier.fillMaxWidth().height(12.dp).clip(CircleShape),
                    color = if (usedFrac > .85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, drawStopIndicator = {})
                Text("${mem.usedMb.humanMb()} in use · ${mem.freeNowMb.humanMb()} free · ${mem.totalMb.humanMb()} total", style = MaterialTheme.typography.bodyMedium)
                Text("Servers can use up to ${mem.maxAllocMb.humanMb()} (90%). Above ${mem.riskyMb.humanMb()} is not recommended; ${mem.comfortMb.humanMb()} and below is comfortable.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (mem.usedMb > mem.maxAllocMb) InfoBanner("Android is already using more than the ${mem.maxAllocMb.humanMb()} that can be allocated, so no server can safely start. Close some apps.", Icons.Rounded.Error, BannerKind.ERROR)
                if (dev.ipAddresses.isNotEmpty()) Text("Network address: " + dev.ipAddresses.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text("Developer options & system", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
            advice.forEach { AdviceCard(it) { a -> DevOptionsAdvisor.act(ctx, a) } }

            SettingsCard("Downloaded runtimes", Icons.Rounded.Storage, subtitle = "Fetched from Adoptium and Alpine on demand · never bundled") {
                if (runtimes.isEmpty()) Text("Nothing downloaded yet. Java is installed automatically the first time a Vanilla or Paper server starts.", style = MaterialTheme.typography.bodyMedium)
                runtimes.forEach { (major, size) ->
                    ListItem(
                        headlineContent = { Text("Temurin Java $major") }, supportingContent = { Text(size.humanBytes()) },
                        leadingContent = { Icon(Icons.Rounded.Coffee, null) },
                        trailingContent = { IconButton({ manager.java.remove(major); refresh++ }) { Icon(Icons.Rounded.Delete, "Remove") } },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    )
                }
                if (runtimes.isNotEmpty()) Text("C runtime (musl): ${manager.java.muslSize().humanBytes()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsCard("About & trademarks", Icons.Rounded.Info) {
                Text(
                    "LapisServe is an independent, unofficial app. It is not affiliated with or endorsed by Mojang, Microsoft, PaperMC, PurpurMC or the Pumpkin project. " +
                        "Minecraft is a trademark of Mojang Synergies AB. Server software, Java and logos belong to their respective owners; LapisServe downloads them " +
                        "at run time from each project's official source and does not redistribute them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Chip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary); Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun AdviceCard(a: Advice, onAction: (AdviceAction) -> Unit) {
    val st = LocalStatusColors.current; val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(a.verdict == Verdict.RECOMMENDED && a.important) }
    val (bg, fg, icon) = when (a.verdict) {
        Verdict.GOOD -> Triple(st.goodContainer, st.good, Icons.Rounded.CheckCircle)
        Verdict.RECOMMENDED -> Triple(st.warnContainer, st.onWarnContainer, Icons.Rounded.Lightbulb)
        Verdict.UNKNOWN -> Triple(cs.surfaceContainerHighest, cs.onSurfaceVariant, Icons.Rounded.HelpOutline)
    }
    Card(onClick = { open = !open }, shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow), modifier = Modifier.animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) { Icon(icon, null, tint = fg) }
                Column(Modifier.weight(1f)) {
                    Text(a.title, style = MaterialTheme.typography.titleMedium)
                    Text(a.current, style = MaterialTheme.typography.bodySmall, color = fg.takeIf { a.verdict != Verdict.UNKNOWN } ?: cs.onSurfaceVariant)
                }
                if (a.important && a.verdict == Verdict.RECOMMENDED) AssistChip({}, { Text("Important") }, colors = AssistChipDefaults.assistChipColors(labelColor = cs.error))
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (open) {
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
