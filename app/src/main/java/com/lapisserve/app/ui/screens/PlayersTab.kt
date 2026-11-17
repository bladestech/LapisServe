@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lapisserve.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lapisserve.app.engine.ServerManager
import com.lapisserve.app.model.RunState
import com.lapisserve.app.model.ServerConfig
import com.lapisserve.app.model.ServerRuntime
import com.lapisserve.app.ui.components.InfoBanner
import com.lapisserve.app.ui.components.BannerKind
import com.lapisserve.app.ui.glass.GlassCard
import com.lapisserve.app.ui.glass.staggerIn

/** A stable colour per player name, so the same player always gets the same avatar. */
private fun avatarColor(name: String): Color {
    val h = (name.lowercase().hashCode().toLong() and 0xFFFFFF).toInt() % 360
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), 0.55f, 0.85f)))
}

@Composable
fun PlayersTab(manager: ServerManager, cfg: ServerConfig, rt: ServerRuntime, notify: (String) -> Unit) {
    val live = rt.state == RunState.RUNNING
    var name by remember { mutableStateOf("") }
    var banTarget by remember { mutableStateOf<String?>(null) }
    val max = rt.playersMax.takeIf { it > 0 } ?: cfg.maxPlayers
    val cs = MaterialTheme.colorScheme

    fun run(cmd: String, done: String) { manager.sendCommand(cfg.id, cmd); notify(done) }

    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 132.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            GlassCard(Modifier.staggerIn(0), shape = MaterialTheme.shapes.extraLarge, tint = cs.tertiary) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${rt.playersOnline} online", style = MaterialTheme.typography.headlineLarge)
                        Text(if (live) "of $max slots" else "Start the server to see players", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                    Box(Modifier.size(56.dp).clip(CircleShape).background(cs.tertiary.copy(alpha = .2f)), Alignment.Center) { Icon(Icons.Rounded.Groups, null, tint = cs.tertiary, modifier = Modifier.size(30.dp)) }
                }
            }
        }

        if (live && rt.players.isEmpty()) item {
            InfoBanner(
                if (rt.playersOnline > 0) "${rt.playersOnline} player${if (rt.playersOnline == 1) " is" else "s are"} online, but this server doesn't share their names." else "No one is online yet. Share your address from the Overview tab.",
                Icons.Rounded.PersonSearch, BannerKind.INFO, Modifier.staggerIn(1),
            )
        }

        items(rt.players, key = { it }) { p ->
            var menu by remember { mutableStateOf(false) }
            GlassCard(Modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.large, padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(avatarColor(p).copy(alpha = .30f)), Alignment.Center) {
                        Text(p.first().uppercase(), style = MaterialTheme.typography.titleMedium, color = avatarColor(p), fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(p, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("Online now", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    FilledTonalIconButton({ run("kick $p", "Kicked $p") }) { Icon(Icons.Rounded.ExitToApp, "Kick $p") }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More actions for $p") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Make operator") }, { menu = false; run("op $p", "$p is now an operator") }, leadingIcon = { Icon(Icons.Rounded.Shield, null) })
                            DropdownMenuItem({ Text("Remove operator") }, { menu = false; run("deop $p", "Removed operator from $p") }, leadingIcon = { Icon(Icons.Rounded.RemoveModerator, null) })
                            DropdownMenuItem({ Text("Add to whitelist") }, { menu = false; run("whitelist add $p", "Whitelisted $p") }, leadingIcon = { Icon(Icons.Rounded.PlaylistAddCheck, null) })
                            DropdownMenuItem({ Text("Ban…") }, { menu = false; banTarget = p }, leadingIcon = { Icon(Icons.Rounded.Block, null) })
                        }
                    }
                }
            }
        }

        item {
            GlassCard(Modifier.staggerIn(2), shape = MaterialTheme.shapes.large) {
                Text("Manage by name", style = MaterialTheme.typography.titleMedium)
                Text("Works for players who aren't online too.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(name, { name = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Player name") }, shape = MaterialTheme.shapes.medium, enabled = live)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip({ run("whitelist add $name", "Whitelisted $name"); name = "" }, { Text("Whitelist") }, enabled = live && name.isNotBlank(), leadingIcon = { Icon(Icons.Rounded.PlaylistAddCheck, null, Modifier.size(18.dp)) })
                    AssistChip({ run("whitelist remove $name", "Removed $name from whitelist"); name = "" }, { Text("Un-whitelist") }, enabled = live && name.isNotBlank())
                    AssistChip({ run("op $name", "$name is now an operator"); name = "" }, { Text("Op") }, enabled = live && name.isNotBlank(), leadingIcon = { Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp)) })
                    AssistChip({ banTarget = name }, { Text("Ban") }, enabled = live && name.isNotBlank(), leadingIcon = { Icon(Icons.Rounded.Block, null, Modifier.size(18.dp)) })
                    AssistChip({ run("pardon $name", "Unbanned $name"); name = "" }, { Text("Unban") }, enabled = live && name.isNotBlank())
                }
                AnimatedVisibility(cfg.whitelist) {
                    Text("The whitelist is on: only listed players can join.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }

    banTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { banTarget = null }, icon = { Icon(Icons.Rounded.Block, null) }, title = { Text("Ban $t?") },
            text = { Text("They won't be able to join until you unban them.") },
            confirmButton = { Button({ run("ban $t", "Banned $t"); banTarget = null; name = "" }, colors = ButtonDefaults.buttonColors(containerColor = cs.error)) { Text("Ban") } },
            dismissButton = { TextButton({ banTarget = null }) { Text("Cancel") } },
        )
    }
}
