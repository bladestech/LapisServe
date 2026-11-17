@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lapisserve.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lapisserve.app.core.humanBytes
import com.lapisserve.app.model.ServerConfig
import com.lapisserve.app.modrinth.Modrinth
import com.lapisserve.app.modrinth.MrProject
import com.lapisserve.app.modrinth.MrProjectInfo
import com.lapisserve.app.modrinth.MrVersion
import com.lapisserve.app.modrinth.PluginStore
import com.lapisserve.app.ui.components.BannerKind
import com.lapisserve.app.ui.components.InfoBanner
import com.lapisserve.app.ui.glass.GlassCard
import com.lapisserve.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

private fun compactCount(n: Long) = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

/**
 * Full plugin page: what it is, which version is installed, and every version that works with this server,
 * grouped into Release / Beta / Alpha so you can pick exactly what you want instead of "latest".
 */
@Composable
fun PluginDetailSheet(
    project: MrProject, cfg: ServerConfig, store: PluginStore, loaders: List<String>,
    onDismiss: () -> Unit, onChanged: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme; val st = LocalStatusColors.current
    var versions by remember { mutableStateOf<List<MrVersion>?>(null) }
    var info by remember { mutableStateOf<MrProjectInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var channel by remember { mutableStateOf(MrVersion.Channel.RELEASE) }
    var installing by remember { mutableStateOf<String?>(null) }       // version id being installed
    var status by remember { mutableStateOf("") }
    var installedTick by remember { mutableIntStateOf(0) }
    val installedId = remember(installedTick) { store.installedProjectIds()[project.id] }
    // Older installs have no stored number, so fall back to matching the installed version id against the loaded list.
    val installedNumber = remember(installedTick, versions) { store.installedVersionNumber(project.id) ?: versions?.firstOrNull { it.id == installedId }?.number }
    var confirmPre by remember { mutableStateOf<MrVersion?>(null) }

    LaunchedEffect(project.id, attempt) {
        error = null
        launch { info = Modrinth.projectInfo(project.id) }
        runCatching { Modrinth.versions(project.id, cfg.version, loaders) }
            .onSuccess { v ->
                versions = v
                // Open on a channel that actually has something, preferring stable.
                channel = MrVersion.Channel.entries.firstOrNull { c -> v.any { it.channel == c } } ?: MrVersion.Channel.RELEASE
            }
            .onFailure { error = it.message ?: "Couldn't reach Modrinth" }
    }

    fun install(v: MrVersion) {
        scope.launch {
            installing = v.id; status = "Starting"
            runCatching { store.install(project.id, project.title, cfg.version, loaders, status = { status = it }, version = v) }
                .onSuccess { names -> installedTick++; onChanged("Installed ${names.joinToString()} ${v.number}") }
                .onFailure { onChanged("Install failed: ${it.message}") }
            installing = null
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = cs.surface) {
        LazyColumn(Modifier.fillMaxHeight(0.94f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    PluginIcon(info?.iconUrl ?: project.iconUrl, 64.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(project.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                        if (project.author.isNotBlank()) Text("by ${project.author}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(info?.description ?: project.description, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoChip(Icons.Rounded.Download, "${compactCount(info?.downloads ?: project.downloads)} downloads")
                    info?.followers?.takeIf { it > 0 }?.let { InfoChip(Icons.Rounded.Favorite, "${compactCount(it)} followers") }
                    info?.license?.let { InfoChip(Icons.Rounded.Gavel, it) }
                    info?.categories?.take(3)?.forEach { InfoChip(Icons.Rounded.Label, it) }
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    fun open(u: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    info?.let { i ->
                        TextButton({ open(i.pageUrl) }) { Icon(Icons.Rounded.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Modrinth") }
                        i.sourceUrl?.let { u -> TextButton({ open(u) }) { Icon(Icons.Rounded.Code, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Source") } }
                        i.issuesUrl?.let { u -> TextButton({ open(u) }) { Icon(Icons.Rounded.BugReport, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Issues") } }
                    }
                }
            }

            item {
                if (installedNumber != null || installedId != null)
                    InfoBanner("Installed: ${installedNumber ?: "unknown version"}. Pick another version below to switch; the old jar is replaced.", Icons.Rounded.CheckCircle, BannerKind.GOOD)
            }

            val list = versions
            when {
                error != null -> item {
                    InfoBanner(error!!, Icons.Rounded.CloudOff, BannerKind.ERROR, title = "Couldn't load versions")
                    OutlinedButton({ attempt++ }, Modifier.padding(top = 8.dp)) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Try again") }
                }
                list == null -> item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { LoadingIndicator() } }
                list.isEmpty() -> item { InfoBanner("No version of this plugin supports ${cfg.type.label} ${Modrinth.gameVersion(cfg.version)}.", Icons.Rounded.Block, BannerKind.WARN, title = "Nothing compatible") }
                else -> {
                    item {
                        Text("Versions for ${cfg.type.label} ${Modrinth.gameVersion(cfg.version)}", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        val channels = MrVersion.Channel.entries
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            channels.forEachIndexed { i, c ->
                                val n = list.count { it.channel == c }
                                SegmentedButton(channel == c, { channel = c }, SegmentedButtonDefaults.itemShape(i, channels.size), enabled = n > 0) { Text("${c.label} $n", maxLines = 1) }
                            }
                        }
                        AnimatedVisibility(channel != MrVersion.Channel.RELEASE) {
                            Text(
                                if (channel == MrVersion.Channel.BETA) "Betas are feature-complete but may have bugs. Back up before trying one."
                                else "Alphas are experimental and can break your server or world. Back up first.",
                                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = st.warn,
                            )
                        }
                    }
                    val shown = list.filter { it.channel == channel }
                    items(shown, key = { it.id }) { v ->
                        VersionRow(
                            v, isInstalled = v.id == installedId, installing = installing == v.id, status = status, anyBusy = installing != null,
                            onInstall = { if (v.channel == MrVersion.Channel.RELEASE) install(v) else confirmPre = v },
                        )
                    }
                }
            }
        }
    }

    confirmPre?.let { v ->
        AlertDialog(
            onDismissRequest = { confirmPre = null }, icon = { Icon(Icons.Rounded.Warning, null) },
            title = { Text("Install a ${v.channel.label.lowercase()}?") },
            text = { Text("${project.title} ${v.number} is a ${v.channel.label.lowercase()} release and may be unstable. Consider backing up your world first.") },
            confirmButton = { Button({ confirmPre = null; install(v) }) { Text("Install anyway") } },
            dismissButton = { TextButton({ confirmPre = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InfoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun VersionRow(v: MrVersion, isInstalled: Boolean, installing: Boolean, status: String, anyBusy: Boolean, onInstall: () -> Unit) {
    val cs = MaterialTheme.colorScheme; val st = LocalStatusColors.current
    var open by remember { mutableStateOf(false) }
    val color = when (v.channel) { MrVersion.Channel.RELEASE -> st.good; MrVersion.Channel.BETA -> st.warn; MrVersion.Channel.ALPHA -> cs.error }
    GlassCard(Modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.large, tint = if (isInstalled) cs.primary else null, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(v.number, style = MaterialTheme.typography.titleMedium, maxLines = 3)
                    Text(v.channel.label, Modifier.clip(CircleShape).background(color.copy(alpha = .18f)).padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = color)
                }
                Text(
                    listOfNotNull(v.published.take(10).ifEmpty { null }, "${compactCount(v.downloads)} downloads", v.sizeBytes.takeIf { it > 0 }?.humanBytes()).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                if (v.name.isNotBlank() && v.name != v.number) Text(v.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                installing -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                isInstalled -> AssistChip({}, { Text("Installed") }, enabled = false, leadingIcon = { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) })
                else -> FilledTonalButton(onInstall, enabled = !anyBusy, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Install") }
            }
        }
        if (installing) Text(status, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        if (v.changelog.isNotBlank()) {
            TextButton({ open = !open }, contentPadding = PaddingValues(0.dp)) { Text(if (open) "Hide changelog" else "Changelog") }
            AnimatedVisibility(open) {
                Text(v.changelog.trim().take(1500), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}
