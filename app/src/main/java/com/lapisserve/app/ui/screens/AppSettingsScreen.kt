@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lapisserve.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.lapisserve.app.ui.components.*
import com.lapisserve.app.ui.glass.GlassCard
import com.lapisserve.app.ui.glass.staggerIn
import com.lapisserve.app.ui.theme.Accent
import com.lapisserve.app.ui.theme.AppPrefs
import com.lapisserve.app.ui.theme.ThemeMode

private const val REPO = "https://github.com/bladestech/LapisServe"

@Composable
fun AppSettingsScreen(prefs: AppPrefs) {
    val ctx = LocalContext.current
    val a by prefs.appearance.collectAsState()
    val haptic = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "dev" }
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("Settings", "Make it yours", Modifier.statusBarsPadding().staggerIn(0)) }

        item {
            SettingsCard("Theme", Icons.Rounded.Palette, Modifier.staggerIn(1), subtitle = "Light, dark or true black") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(a.mode == m, { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); prefs.update { it.copy(mode = m) } }, { Text(m.label) },
                            leadingIcon = { Icon(when (m) { ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto; ThemeMode.LIGHT -> Icons.Rounded.LightMode; ThemeMode.DARK -> Icons.Rounded.DarkMode; ThemeMode.AMOLED -> Icons.Rounded.Contrast }, null, Modifier.size(18.dp)) })
                    }
                }
            }
        }
        item {
            SettingsCard("Accent colour", Icons.Rounded.ColorLens, Modifier.staggerIn(2), subtitle = if (a.dynamicColor) "Using your wallpaper colours" else a.accent.label) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Accent.entries.forEach { ac -> Swatch(ac.seed, selected = !a.dynamicColor && a.accent == ac, enabled = !a.dynamicColor) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); prefs.update { it.copy(accent = ac) } } }
                }
                if (Build.VERSION.SDK_INT >= 31) SwitchRow("Use wallpaper colours", "Material You palette from your wallpaper instead of an accent.", a.dynamicColor, { v -> prefs.update { it.copy(dynamicColor = v) } })
            }
        }
        item {
            SettingsCard("Motion", Icons.Rounded.Animation, Modifier.staggerIn(3)) {
                SwitchRow("Animated background", "The slowly drifting colour glow behind everything.", a.animatedBackdrop, { v -> prefs.update { it.copy(animatedBackdrop = v) } })
                SwitchRow("Reduce motion", "Turns off entrance effects and pulsing for a calmer, lighter UI.", a.reduceMotion, { v -> prefs.update { it.copy(reduceMotion = v) } })
            }
        }
        item {
            SettingsCard("About", Icons.Rounded.Info, Modifier.staggerIn(4), subtitle = "LapisServe $version") {
                Text("Open source under the Apache License 2.0. Independent project, not affiliated with Mojang, Microsoft, PaperMC, PurpurMC, the Pumpkin project or Modrinth.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip({ open(REPO) }, { Text("GitHub") }, leadingIcon = { Icon(Icons.Rounded.Code, null, Modifier.size(18.dp)) })
                    AssistChip({ open("$REPO/issues/new/choose") }, { Text("Report a problem") }, leadingIcon = { Icon(Icons.Rounded.BugReport, null, Modifier.size(18.dp)) })
                    AssistChip({ open("$REPO/blob/main/docs/COMPATIBILITY.md") }, { Text("Compatibility") }, leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(18.dp)) })
                    AssistChip({ open("$REPO/blob/main/NOTICE.md") }, { Text("Licenses") }, leadingIcon = { Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)) })
                }
                Text("No analytics, no telemetry, no account. Server software, Java, plugins and logos are downloaded at run time from their official sources.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val size by animateDpAsState(if (selected) 52.dp else 44.dp, spring(0.55f, 500f), label = "swatch")
    val ring by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, label = "ring")
    Box(Modifier.size(56.dp), Alignment.Center) {
        Box(
            Modifier.size(size).clip(CircleShape).background(color.copy(alpha = if (enabled) 1f else .35f)).border(3.dp, ring, CircleShape)
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick),
            Alignment.Center,
        ) { if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White) }
    }
}
