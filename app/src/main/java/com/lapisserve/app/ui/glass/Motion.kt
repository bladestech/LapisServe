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
import androidx.compose.ui.draw.drawBehind
import com.lapisserve.app.model.RunState.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.BoxScope
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


/**
 * A living halo around a server's avatar that tells its state without reading a word:
 * ripples expand outward while starting, a steady breathing ring when running, ripples collapse inward while
 * stopping, a red ring after a crash, and a one-time burst the moment the server becomes ready.
 */
@Composable
fun StateAura(state: com.lapisserve.app.model.RunState, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val st = com.lapisserve.app.ui.theme.LocalStatusColors.current
    val cs = MaterialTheme.colorScheme
    val reduce = LocalAppearance.current.reduceMotion
    val target = when (state) {
        RUNNING -> st.good; STARTING, INSTALLING -> cs.primary; STOPPING -> st.warn; CRASHED -> cs.error; STOPPED -> Color.Transparent
    }
    val color by androidx.compose.animation.animateColorAsState(target, tween(500), label = "aura")
    val t = rememberInfiniteTransition(label = "auraLoop")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1900, easing = LinearEasing)), label = "phase")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val burst = remember { Animatable(1f) }
    LaunchedEffect(state) { if (state == RUNNING && !reduce) { burst.snapTo(0f); burst.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) } }

    Box(
        modifier.drawBehind {
            if (color == Color.Transparent || color.alpha < 0.02f) return@drawBehind
            val r = size.minDimension / 2f; val reach = 15.dp.toPx(); val c = center
            when (state) {
                STARTING, INSTALLING, STOPPING -> if (!reduce) for (k in 0..1) {
                    val p = (phase + k * 0.5f) % 1f
                    val e = if (state == STOPPING) 1f - p else p
                    drawCircle(color.copy(alpha = (1f - e) * .55f), radius = r + e * reach, center = c, style = Stroke(2.dp.toPx()))
                } else drawCircle(color.copy(alpha = .4f), radius = r + 6.dp.toPx(), center = c, style = Stroke(2.dp.toPx()))
                RUNNING -> {
                    val a = if (reduce) .4f else .25f + .25f * breathe
                    drawCircle(color.copy(alpha = a), radius = r + 6.dp.toPx(), center = c, style = Stroke(2.5.dp.toPx()))
                    drawCircle(color.copy(alpha = a * .35f), radius = r + 9.dp.toPx() + breathe * 3.dp.toPx(), center = c, style = Stroke(1.5.dp.toPx()))
                }
                CRASHED -> drawCircle(color.copy(alpha = .55f), radius = r + 6.dp.toPx(), center = c, style = Stroke(2.5.dp.toPx()))
                STOPPED -> {}
            }
            if (burst.value < 1f) drawCircle(color.copy(alpha = (1f - burst.value) * .8f), radius = r + burst.value * reach * 1.7f, center = c, style = Stroke(3.dp.toPx()))
        },
        contentAlignment = androidx.compose.ui.Alignment.Center, content = content,
    )
}
