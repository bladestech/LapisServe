@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package com.lapisserve.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lapisserve.app.core.humanBytes
import com.lapisserve.app.engine.Backup
import com.lapisserve.app.engine.ServerManager
import com.lapisserve.app.model.ServerConfig
import com.lapisserve.app.ui.components.BannerKind
import com.lapisserve.app.ui.components.InfoBanner
import com.lapisserve.app.ui.components.SettingsCard
import kotlinx.coroutines.launch

/** Create, restore and delete world backups for one server. */
@Composable
fun BackupsCard(manager: ServerManager, cfg: ServerConfig, active: Boolean, notify: (String) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var backups by remember(cfg.id) { mutableStateOf(manager.listBackups(cfg.id)) }
    var working by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<Backup?>(null) }
    val now = System.currentTimeMillis()
    val cs = MaterialTheme.colorScheme

    SettingsCard("Backups", Icons.Rounded.Backup, modifier, subtitle = "Worlds, whitelist, ops and bans") {
        Button(
            onClick = {
                scope.launch {
                    working = true
                    runCatching { manager.createBackup(cfg.id) }
                        .onSuccess { notify("Backup saved (${it.sizeBytes.humanBytes()})") }
                        .onFailure { notify("Backup failed: ${it.message}") }
                    backups = manager.listBackups(cfg.id); working = false
                }
            },
            enabled = !working, modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = cs.onPrimary) else Icon(Icons.Rounded.Save, null)
            Spacer(Modifier.width(10.dp)); Text(if (working) "Backing up…" else if (active) "Back up now (live)" else "Back up now")
        }
        if (active) Text("The server is running, so it is asked to save to disk first. For the safest backup, stop it.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)

        if (backups.isEmpty()) Text("No backups yet. Back up before changing versions or trying risky plugins.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        backups.forEach { b ->
            ListItem(
                headlineContent = { Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(b.time)), maxLines = 1) },
                supportingContent = { Text("${b.sizeBytes.humanBytes()} · ${relativeTime(b.time, now)}") },
                leadingContent = { Icon(Icons.Rounded.Archive, null, tint = cs.primary) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ restoreTarget = b }, enabled = !active) { Icon(Icons.Rounded.Restore, "Restore ${b.name}") }
                        IconButton({ manager.deleteBackup(b); backups = manager.listBackups(cfg.id) }) { Icon(Icons.Rounded.Delete, "Delete ${b.name}") }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
        }
        if (backups.isNotEmpty() && active) Text("Stop the server to restore a backup.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }

    restoreTarget?.let { b ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null }, icon = { Icon(Icons.Rounded.Restore, null) }, title = { Text("Restore this backup?") },
            text = { Text("The current worlds, whitelist, ops and bans are replaced with the ones from ${b.name}. This cannot be undone, so back up first if you might need them.") },
            confirmButton = {
                Button({
                    restoreTarget = null
                    scope.launch {
                        runCatching { manager.restoreBackup(cfg.id, b) }.onSuccess { notify("Backup restored") }.onFailure { notify("Restore failed: ${it.message}") }
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton({ restoreTarget = null }) { Text("Cancel") } },
        )
    }
}
