package com.lapisserve.app.ui.glass

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

data class TabItem(val key: String, val label: String, val icon: ImageVector)

/** A floating, blurred pill of tabs. The selected tab grows to reveal its label with a spring. */
@Composable
fun GlassTabBar(
    items: List<TabItem>, selectedKey: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier,
    hazeState: dev.chrisbanes.haze.HazeState? = LocalHazeState.current,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier.glassBar(CircleShape, hazeState).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { t ->
            val on = t.key == selectedKey
            val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.primary.copy(alpha = .22f) else Color.Transparent, label = "tabBg")
            val fg by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, label = "tabFg")
            Row(
                Modifier.height(50.dp).clip(CircleShape).background(bg)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        if (!on) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(t.key) }
                    }
                    .padding(horizontal = 13.dp).animateContentSize(spring(0.8f, 500f)),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(t.icon, t.label, tint = fg, modifier = Modifier.size(23.dp))
                AnimatedVisibility(on, enter = fadeIn(tween(160)) + expandHorizontally(), exit = fadeOut(tween(90)) + shrinkHorizontally()) {
                    Text(t.label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
                }
            }
        }
    }
}
