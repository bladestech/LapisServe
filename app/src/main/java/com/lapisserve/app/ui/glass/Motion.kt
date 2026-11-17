package com.lapisserve.app.ui.glass

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lapisserve.app.ui.theme.LocalAppearance
import kotlinx.coroutines.delay

/** Fades and lifts an element in on first composition, staggered by [index]. Skipped when reduce-motion is on. */
@Composable
fun Modifier.staggerIn(index: Int = 0, distance: Dp = 28.dp): Modifier {
    val reduce = LocalAppearance.current.reduceMotion
    val progress = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduce) {
            delay(minOf(index, 9) * 55L)
            progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 220f))
        }
    }
    return graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        translationY = (1f - progress.value) * distance.toPx()
    }
}

/** A number that eases to its new value instead of snapping. */
@Composable
fun AnimatedNumber(
    value: Float, format: (Float) -> String, style: TextStyle, color: Color, modifier: Modifier = Modifier,
) {
    val reduce = LocalAppearance.current.reduceMotion
    val shown by animateFloatAsState(value, if (reduce) snap() else spring(dampingRatio = 1f, stiffness = 90f), label = "num")
    Text(format(shown), modifier, style = style, color = color)
}

/** A breathing status dot with a soft halo; [active] false makes it static. */
@Composable
fun PulseDot(color: Color, active: Boolean, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "pulse")
    val reduce = LocalAppearance.current.reduceMotion
    val halo by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "halo")
    Box(modifier.size(size * 2.4f)) {
        Canvas(Modifier.size(size * 2.4f)) {
            val c = center; val r = size.toPx() / 2f
            if (active && !reduce) drawCircle(color.copy(alpha = (1f - halo) * .45f), radius = r + halo * r * 1.9f, center = c)
            drawCircle(color, radius = r, center = c)
        }
    }
}

/** Colour for a TPS reading: calm green near 20, amber when slipping, red when the server is struggling. */
@Composable
fun tpsColor(tps: Float?): Color {
    val good = com.lapisserve.app.ui.theme.LocalStatusColors.current
    val cs = MaterialTheme.colorScheme
    return when {
        tps == null -> cs.outline
        tps >= 19.0f -> good.good
        tps >= 15.0f -> good.warn
        else -> cs.error
    }
}
