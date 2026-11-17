@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lapisserve.app.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lapisserve.app.engine.ServerManager
import com.lapisserve.app.model.ServerEvent
import com.lapisserve.app.ui.components.ScreenHeader
import com.lapisserve.app.ui.glass.GlassCard
import com.lapisserve.app.ui.glass.staggerIn
import com.lapisserve.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private fun dayOf(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

private fun dayLabel(ms: Long, now: Long): String = when (dayOf(now) - dayOf(ms)) {
    0 -> "Today"; 1 -> "Yesterday"; else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
}

fun relativeTime(ms: Long, now: Long): String {
    val s = ((now - ms) / 1000).coerceAtLeast(0)
    return when {
        s < 10 -> "just now"; s < 60 -> "${s}s ago"; s < 3600 -> "${s / 60}m ago"; s < 86_400 -> "${s / 3600}h ago"; else -> "${s / 86_400}d ago"
    }
}

@Composable
fun ActivityScreen(manager: ServerManager, onOpen: (String) -> Unit) {
    val events by manager.events.collectAsState()
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(20_000); value = System.currentTimeMillis() } }
    val cs = MaterialTheme.colorScheme

    val dayAgo = now - 86_400_000L
    val recent = events.filter { it.time >= dayAgo }
    val joins = recent.count { it.kind == ServerEvent.Kind.JOIN }
    val crashes = recent.count { it.kind == ServerEvent.Kind.CRASHED }
    val starts = recent.count { it.kind == ServerEvent.Kind.STARTED }

    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenHeader("Activity", "Everything happening across your servers", Modifier.statusBarsPadding().staggerIn(0)) }
        if (events.isNotEmpty()) item {
            GlassCard(Modifier.staggerIn(1), shape = MaterialTheme.shapes.large, padding = PaddingValues(16.dp)) {
                Text("Last 24 hours", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Summary("$joins", "joins", Icons.Rounded.Login, cs.tertiary)
                    Summary("$starts", "starts", Icons.Rounded.PlayCircle, LocalStatusColors.current.good)
                    Summary("$crashes", "crashes", Icons.Rounded.ReportProblem, if (crashes > 0) cs.error else cs.outline)
                }
            }
        }
        if (events.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(110.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(cs.primary.copy(alpha = .16f)), Alignment.Center) {
                    Icon(Icons.Rounded.NotificationsNone, null, Modifier.size(52.dp), tint = cs.primary)
                }
                Text("Nothing yet", style = MaterialTheme.typography.headlineSmall)
                Text("Starts, stops, crashes and players joining will show up here.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
        var last = ""
        events.forEachIndexed { i, e ->
            val label = dayLabel(e.time, now)
            if (label != last) { last = label; item(key = "day-$label") { Text(label, Modifier.padding(top = 10.dp, start = 4.dp), style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant) } }
            item(key = e.id) { EventRow(e, now, Modifier.staggerIn(minOf(i, 6) + 2), onClick = { onOpen(e.serverId) }) }
        }
    }
}

@Composable
private fun Summary(value: String, label: String, icon: ImageVector, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, null, tint = color)
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EventRow(e: ServerEvent, now: Long, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme; val st = LocalStatusColors.current
    val (icon, color) = when (e.kind) {
        ServerEvent.Kind.STARTED -> Icons.Rounded.PlayCircle to st.good
        ServerEvent.Kind.STOPPED -> Icons.Rounded.StopCircle to cs.onSurfaceVariant
        ServerEvent.Kind.CRASHED -> Icons.Rounded.ReportProblem to cs.error
        ServerEvent.Kind.JOIN -> Icons.Rounded.Login to cs.tertiary
        ServerEvent.Kind.LEAVE -> Icons.Rounded.Logout to cs.outline
        ServerEvent.Kind.WARNING -> Icons.Rounded.Warning to st.warn
        ServerEvent.Kind.INFO -> Icons.Rounded.Info to cs.primary
    }
    GlassCard(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, onClick = onClick, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = .18f)), Alignment.Center) { Icon(icon, null, tint = color, modifier = Modifier.size(22.dp)) }
            Column(Modifier.weight(1f)) {
                Text(e.text, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                Text("${e.serverName} · ${relativeTime(e.time, now)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}
