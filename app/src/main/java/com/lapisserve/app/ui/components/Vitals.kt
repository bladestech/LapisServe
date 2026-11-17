package com.lapisserve.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lapisserve.app.engine.Insight
import com.lapisserve.app.engine.InsightAction
import com.lapisserve.app.engine.Severity
import com.lapisserve.app.ui.glass.*
import com.lapisserve.app.ui.theme.LocalStatusColors

/** Large screen title with an optional subtitle and trailing actions. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** A circular glass icon button for floating chrome (back, menu). */
@Composable
fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier.size(48.dp), shape = CircleShape, onClick = onClick, padding = PaddingValues(0.dp)) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Icon(icon, description, Modifier.size(24.dp)) }
    }
}

/** A tile with a big live number and an optional sparkline of its recent history. */
@Composable
fun VitalTile(
    title: String, icon: ImageVector, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface,
    sub: String? = null, spark: List<Float>? = null, sparkColor: Color = MaterialTheme.colorScheme.primary,
    sparkMin: Float? = null, sparkMax: Float? = null, tint: Color? = null, onClick: (() -> Unit)? = null,
) {
    GlassCard(modifier, shape = MaterialTheme.shapes.large, tint = tint, onClick = onClick, padding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(value, style = MaterialTheme.typography.headlineMedium, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (spark != null) Sparkline(spark, sparkColor, Modifier.fillMaxWidth().height(44.dp), minValue = sparkMin, maxValue = sparkMax)
        }
    }
}

/** A tile built around a ring gauge, for values that have a natural 0-100% scale. */
@Composable
fun GaugeTile(
    title: String, icon: ImageVector, fraction: Float, center: String, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary, sub: String? = null, footer: String? = null, tint: Color? = null,
) {
    GlassCard(modifier, shape = MaterialTheme.shapes.large, tint = tint, padding = PaddingValues(16.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            RingGauge(fraction, color, Modifier.size(112.dp), stroke = 11.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(center, style = MaterialTheme.typography.headlineSmall, color = color, maxLines = 1)
                    if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (footer != null) Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
fun InsightCard(insight: Insight, modifier: Modifier = Modifier, onAction: (InsightAction) -> Unit) {
    val cs = MaterialTheme.colorScheme; val st = LocalStatusColors.current
    val (color, icon) = when (insight.severity) {
        Severity.GOOD -> st.good to Icons.Rounded.CheckCircle
        Severity.INFO -> cs.primary to Icons.Rounded.Info
        Severity.WARN -> st.warn to Icons.Rounded.Warning
        Severity.CRITICAL -> cs.error to Icons.Rounded.Error
    }
    GlassCard(modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.large, tint = color, padding = PaddingValues(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = .18f)), Alignment.Center) { Icon(icon, null, tint = color) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(insight.title, style = MaterialTheme.typography.titleMedium)
                Text(insight.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                insight.action?.let { a -> TextButton({ onAction(a) }, contentPadding = PaddingValues(0.dp)) { Text(a.label) } }
            }
        }
    }
}
