@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.lapisserve.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lapisserve.app.core.humanMb
import com.lapisserve.app.model.RunState
import com.lapisserve.app.model.ServerType
import com.lapisserve.app.system.MemorySnapshot
import com.lapisserve.app.ui.theme.LocalStatusColors

// ------------------------------------------------------------------ identity

fun ServerType.icon(): ImageVector = when (this) {
    ServerType.VANILLA -> Icons.Rounded.Grass
    ServerType.PAPER -> Icons.Rounded.Description
    ServerType.PURPUR -> Icons.Rounded.Bolt
    ServerType.PUMPKIN -> Icons.Rounded.Eco
}

@Composable
fun TypeAvatar(type: ServerType, size: Dp = 56.dp, modifier: Modifier = Modifier) {
    val (bg, fg) = when (type) {
        ServerType.VANILLA -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ServerType.PAPER -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ServerType.PURPUR -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ServerType.PUMPKIN -> LocalStatusColors.current.warnContainer to LocalStatusColors.current.onWarnContainer
    }
    val shape = when (type) {
        ServerType.VANILLA -> MaterialShapes.Cookie9Sided
        ServerType.PAPER -> MaterialShapes.Square
        ServerType.PURPUR -> MaterialShapes.Cookie4Sided
        ServerType.PUMPKIN -> MaterialShapes.Flower
    }.toShape()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val logo by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, type) { value = Logos.get(ctx, type) }
    Box(modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
        val l = logo
        if (l != null) androidx.compose.foundation.Image(l, type.label, Modifier.fillMaxSize().padding(size * 0.14f))
        else Icon(type.icon(), null, tint = fg, modifier = Modifier.size(size * 0.5f))
    }
}

private typealias Dp = androidx.compose.ui.unit.Dp

@Composable
fun StatePill(state: RunState, modifier: Modifier = Modifier) {
    val s = LocalStatusColors.current
    val cs = MaterialTheme.colorScheme
    val (bg, fg, label) = when (state) {
        RunState.RUNNING -> Triple(s.goodContainer, s.good, "Running")
        RunState.STARTING -> Triple(cs.tertiaryContainer, cs.onTertiaryContainer, "Starting")
        RunState.INSTALLING -> Triple(cs.primaryContainer, cs.onPrimaryContainer, "Setting up")
        RunState.STOPPING -> Triple(s.warnContainer, s.onWarnContainer, "Stopping")
        RunState.CRASHED -> Triple(cs.errorContainer, cs.onErrorContainer, "Crashed")
        RunState.STOPPED -> Triple(cs.surfaceContainerHighest, cs.onSurfaceVariant, "Offline")
    }
    Row(
        modifier.clip(CircleShape).background(bg).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(fg))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

// ------------------------------------------------------------------ settings building blocks

@Composable
fun SettingsCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(40.dp).clip(MaterialShapes.Cookie6Sided.toShape()).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
fun SwitchRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled, thumbContent = if (checked) ({ Icon(Icons.Rounded.Check, null, Modifier.size(SwitchDefaults.IconSize)) }) else null)
    }
}

@Composable
fun SliderRow(
    title: String, value: Int, range: IntRange, onChange: (Int) -> Unit,
    valueText: (Int) -> String = { it.toString() }, desc: String? = null, step: Int = 1,
) {
    val steps = ((range.last - range.first) / step - 1).coerceAtLeast(0)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(valueText(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value.toFloat(), onValueChange = { onChange((Math.round(it / step) * step).coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = steps,
        )
    }
}

@Composable
fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, desc: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                FilterChip(
                    selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) },
                    leadingIcon = if (o == selected) ({ Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                )
            }
        }
    }
}

@Composable
fun TextRow(title: String, value: String, onChange: (String) -> Unit, desc: String? = null, numeric: Boolean = false, singleLine: Boolean = true) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text, onValueChange = { text = it; onChange(it) }, label = { Text(title) },
        supportingText = desc?.let { { Text(it) } }, singleLine = singleLine, modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (numeric) androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Text),
    )
}

@Composable
fun InfoBanner(text: String, icon: ImageVector, kind: BannerKind, modifier: Modifier = Modifier, title: String? = null) {
    val s = LocalStatusColors.current; val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (kind) {
        BannerKind.INFO -> cs.secondaryContainer to cs.onSecondaryContainer
        BannerKind.WARN -> s.warnContainer to s.onWarnContainer
        BannerKind.ERROR -> cs.errorContainer to cs.onErrorContainer
        BannerKind.GOOD -> s.goodContainer to s.good
    }
    Row(modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(bg).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
        Column {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = fg)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = fg)
        }
    }
}

enum class BannerKind { INFO, WARN, ERROR, GOOD }

/** A labelled usage meter: name on the left, value on the right, bar below. Turns red above 90%. */
@Composable
fun MeterRow(label: String, value: String, fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val c = if (fraction > .9f) MaterialTheme.colorScheme.error else color
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "meter")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelLarge, color = c)
        }
        LinearProgressIndicator(
            progress = { animated }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape), color = c,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, gapSize = 0.dp, drawStopIndicator = {},
        )
    }
}

// ------------------------------------------------------------------ RAM slider

enum class RamZone { COMFORTABLE, HEAVY, RISKY }

fun ramZone(mb: Int, m: MemorySnapshot) = when {
    mb > m.riskyMb -> RamZone.RISKY
    mb > m.comfortMb -> RamZone.HEAVY
    else -> RamZone.COMFORTABLE
}

/**
 * RAM allocation slider. Caps at 90% of device memory, turns amber then red as the value
 * approaches the unsafe range, shows how much RAM Android is already using, and warns when
 * the allocation can't be satisfied from what is free right now.
 */
@Composable
fun RamSlider(valueMb: Int, onChange: (Int) -> Unit, mem: MemorySnapshot, modifier: Modifier = Modifier, minMb: Int = 256) {
    val cs = MaterialTheme.colorScheme; val st = LocalStatusColors.current
    val maxMb = mem.maxAllocMb.coerceAtLeast(minMb + 256)
    val value = valueMb.coerceIn(minMb, maxMb)
    val zone = ramZone(value, mem)
    val zoneColor by animateColorAsStateCompat(when (zone) { RamZone.COMFORTABLE -> cs.primary; RamZone.HEAVY -> st.warn; RamZone.RISKY -> cs.error })
    val trackGood = cs.primary; val trackWarn = st.warn; val trackBad = cs.error
    val frac = { mb: Int -> ((mb - minMb).toFloat() / (maxMb - minMb)).coerceIn(0f, 1f) }
    val usedFrac = frac(mem.usedMb); val freeLimitFrac = frac(mem.freeNowMb)
    val exceedsFree = value > mem.freeNowMb
    val noRoom = mem.freeNowMb < minMb

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value.humanMb(), style = MaterialTheme.typography.displaySmall, color = zoneColor, modifier = Modifier.weight(1f))
            AnimatedContent(zone, label = "zone") { z ->
                val (t, ic) = when (z) {
                    RamZone.COMFORTABLE -> "Comfortable" to Icons.Rounded.CheckCircle
                    RamZone.HEAVY -> "Heavy" to Icons.Rounded.Warning
                    RamZone.RISKY -> "Not recommended" to Icons.Rounded.Error
                }
                Row(Modifier.clip(CircleShape).background(zoneColor.copy(alpha = .14f)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(ic, null, tint = zoneColor, modifier = Modifier.size(18.dp)); Text(t, color = zoneColor, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Text("${(value * 100f / mem.totalMb).toInt()}% of this device's ${mem.totalMb.humanMb()} · capped at 90%", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)

        Slider(
            value = value.toFloat(), onValueChange = { onChange(((Math.round(it / 128f)) * 128).coerceIn(minMb, maxMb)) },
            valueRange = minMb.toFloat()..maxMb.toFloat(),
            colors = SliderDefaults.colors(thumbColor = zoneColor),
            thumb = { Box(Modifier.size(width = 6.dp, height = 40.dp).clip(CircleShape).background(zoneColor)) },
            track = { s ->
                val progress = ((s.value - s.valueRange.start) / (s.valueRange.endInclusive - s.valueRange.start)).coerceIn(0f, 1f)
                val comfort = frac(mem.comfortMb); val risky = frac(mem.riskyMb)
                Canvas(Modifier.fillMaxWidth().height(40.dp)) {
                    val h = 22.dp.toPx(); val top = (size.height - h) / 2; val r = CornerRadius(h / 2)
                    val w = size.width
                    fun seg(a: Float, b: Float, c: Color, alpha: Float) {
                        if (b <= a) return
                        drawRoundRect(c.copy(alpha = alpha), Offset(w * a, top), Size(w * (b - a), h), r)
                    }
                    // tinted zones behind
                    seg(0f, comfort, trackGood, .16f); seg(comfort, risky, trackWarn, .20f); seg(risky, 1f, trackBad, .24f)
                    // filled part, coloured by whichever zone each portion falls in
                    seg(0f, minOf(progress, comfort), trackGood, 1f)
                    if (progress > comfort) seg(comfort, minOf(progress, risky), trackWarn, 1f)
                    if (progress > risky) seg(risky, progress, trackBad, 1f)
                    // gaps between zones so they read as a segmented bar
                    for (g in listOf(comfort, risky)) drawRect(cs.surfaceContainerLow, Offset(w * g - 1.5.dp.toPx(), top), Size(3.dp.toPx(), h))
                    // marker: RAM already used by the system and other apps
                    val ux = w * usedFrac
                    drawRect(cs.onSurface.copy(alpha = .75f), Offset(ux - 1.dp.toPx(), top - 5.dp.toPx()), Size(2.dp.toPx(), h + 10.dp.toPx()))
                    // marker: memory still free right now
                    if (freeLimitFrac < 1f) drawRect(cs.error, Offset(w * freeLimitFrac - 1.dp.toPx(), top - 5.dp.toPx()), Size(2.dp.toPx(), h + 10.dp.toPx()))
                }
            },
        )

        val rec = (minOf(mem.comfortMb, maxOf(mem.freeNowMb - 384, 768)) / 128 * 128).coerceIn(minMb, maxMb)
        val picks = listOf("Light" to 1024.coerceIn(minMb, maxMb), "Recommended" to rec, "Comfortable max" to (mem.comfortMb / 128 * 128).coerceIn(minMb, maxMb))
            .distinctBy { it.second }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            picks.forEach { (label, mb) ->
                FilterChip(selected = value == mb, onClick = { onChange(mb) }, label = { Text("$label · ${mb.humanMb()}") })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Legend(cs.onSurface.copy(alpha = .75f), "In use now ${mem.usedMb.humanMb()}")
            Legend(cs.error, "Free now ${mem.freeNowMb.humanMb()}")
        }

        AnimatedVisibility(noRoom, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            InfoBanner("Android is already using ${mem.usedMb.humanMb()} of ${mem.totalMb.humanMb()}. There is not enough free memory to start a server – close some apps first.",
                Icons.Rounded.Memory, BannerKind.ERROR, title = "Memory is already full")
        }
        AnimatedVisibility(exceedsFree && !noRoom, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            InfoBanner("Only ${mem.freeNowMb.humanMb()} is free right now but you've asked for ${value.humanMb()}. Android will close other apps to make room, and may stop the server if it runs short. Close apps or lower this.",
                Icons.Rounded.Warning, if (zone == RamZone.RISKY) BannerKind.ERROR else BannerKind.WARN, title = "Current usage + this allocation exceeds your RAM")
        }
        AnimatedVisibility(zone == RamZone.RISKY, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            val leave = (mem.totalMb - value).humanMb()
            InfoBanner("This leaves only $leave for Android itself, other apps and the JVM's own non-heap memory. Expect freezes or the server being killed.",
                Icons.Rounded.Error, BannerKind.ERROR, title = "Not recommended")
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(width = 3.dp, height = 14.dp).background(color))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun animateColorAsStateCompat(target: Color): State<Color> =
    androidx.compose.animation.animateColorAsState(target, label = "color")

/** Core selector: tap cores to pin the server to them. Labels big/little clusters when frequency data is available. */
@Composable
fun CoreSelector(totalCores: Int, mask: Int, freqs: List<Long?>, onChange: (Int) -> Unit) {
    val max = freqs.filterNotNull().maxOrNull()
    val min = freqs.filterNotNull().minOrNull()
    val hetero = max != null && min != null && max != min
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until totalCores) {
            val on = mask == 0 || (mask shr i) and 1 == 1
            val f = freqs.getOrNull(i)
            val big = hetero && f != null && f >= max!! * 0.9
            FilterChip(
                selected = on,
                onClick = {
                    val eff = if (mask == 0) (1 shl totalCores) - 1 else mask
                    var n = eff xor (1 shl i)
                    if (n == 0) n = eff                       // never allow zero cores
                    if (n == (1 shl totalCores) - 1) n = 0    // all selected == no pinning
                    onChange(n)
                },
                label = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Core $i")
                    if (hetero && f != null) Text(if (big) "Performance" else "Efficiency", style = MaterialTheme.typography.labelSmall)
                } },
            )
        }
    }
}
