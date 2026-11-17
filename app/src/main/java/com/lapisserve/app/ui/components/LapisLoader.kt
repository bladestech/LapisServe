package com.lapisserve.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lapisserve.app.ui.theme.LocalAppearance

/**
 * The LapisServe logo drawing itself: the green S is laid down bar by bar (bottom, right, middle, left, top) with a glowing
 * pen tip. Drawn natively, so it is transparent, sharp at any size and animates at the display's refresh rate.
 *
 * - [progress] known (0..1): the S fills to match, e.g. a download percentage.
 * - [progress] null: it runs as a snake, drawing in and then erasing from the start.
 */
@Composable
fun LapisLoader(modifier: Modifier = Modifier, size: Dp = 96.dp, progress: Float? = null) {
    val reduce = LocalAppearance.current.reduceMotion
    val t by rememberInfiniteTransition(label = "lapis")
        .animateFloat(0f, SNAKE_MS, infiniteRepeatable(tween(SNAKE_MS.toInt(), easing = LinearEasing)), label = "t")
    // Smooth out jumps between progress updates (downloads report in steps).
    val shown by animateFloatAsState(progress?.coerceIn(0f, 1f) ?: 0f, spring(stiffness = 120f), label = "progress")
    val desc = Modifier.semantics {
        contentDescription = "Loading"
        if (progress != null) progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
    }
    Canvas(modifier.size(size).then(desc)) {
        when {
            progress != null -> drawLapisLogo(head = shown, tail = 0f, tip = shown < 0.999f)
            reduce -> drawLapisLogo(head = 1f, tail = 0f, tip = false)
            else -> drawLapisLogo(head = up(t, 0f, 1400f), tail = up(t, 1250f, 2600f), tip = true)
        }
    }
}

/** The logo at rest. */
@Composable
fun LapisLogo(modifier: Modifier = Modifier, size: Dp = 96.dp) {
    Canvas(modifier.size(size).semantics { contentDescription = "LapisServe" }) { drawLapisLogo(1f, 0f, false) }
}

/** Draws the logo once (about [durationMs]) and calls [onDone]; for the launch splash. */
@Composable
fun LapisIntro(modifier: Modifier = Modifier, size: Dp = 140.dp, durationMs: Int = 900, onDone: () -> Unit = {}) {
    val reduce = LocalAppearance.current.reduceMotion
    val p = remember { Animatable(if (reduce) 1f else 0f) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!reduce) {
            p.animateTo(1f, tween(durationMs, easing = Smooth))
            flash.snapTo(1f); flash.animateTo(0f, tween(380))
        }
        onDone()
    }
    Canvas(modifier.size(size).semantics { contentDescription = "LapisServe" }) {
        drawLapisLogo(head = p.value, tail = 0f, tip = p.value < 0.999f, flash = flash.value)
    }
}

private const val SNAKE_MS = 2800f
private const val UNIT = 2560f

// Rest geometry (left, top, right, bottom) in artwork units, measured from the original logo.
private val UPPER = Rect(798f, 344f, 2544f, 1449f)
private val LOWER = Rect(12f, 1110f, 1757f, 2215f)
private val TOP = Rect(863f, 822f, 2539f, 989f)
private val LEFT_V = Rect(862f, 822f, 1030f, 1363f)
private val MID = Rect(862f, 1196f, 1693f, 1363f)
private val RIGHT_V = Rect(1525f, 1196f, 1693f, 1731f)
private val BOTTOM = Rect(20f, 1565f, 1693f, 1731f)

private val GREEN = Color(0xFF60D959)
private val LIME = Color(0xFFAFEA46)
private val UPPER_TOP = Color(0xFF064584); private val UPPER_BOTTOM = Color(0xFF01133E)
private val LOWER_TOP = Color(0xFF011236); private val LOWER_BOTTOM = Color(0xFF064383)

private val Smooth: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
private fun up(t: Float, start: Float, end: Float, e: Easing = Smooth) = e.transform(((t - start) / (end - start)).coerceIn(0f, 1f))

/** One stretch of the S, drawn from [fromStart] (left / bottom) or the opposite end. */
private class Seg(val r: Rect, val vertical: Boolean, val fromStart: Boolean) { val len = if (vertical) r.height else r.width }

// The S as one path, bottom-left to top-right: bottom bar →, right connector ↑, middle bar ←, left connector ↑, top bar →.
private val SEGS = listOf(
    Seg(BOTTOM, vertical = false, fromStart = true), Seg(RIGHT_V, vertical = true, fromStart = true),
    Seg(MID, vertical = false, fromStart = false), Seg(LEFT_V, vertical = true, fromStart = true),
    Seg(TOP, vertical = false, fromStart = true),
)
private val TOTAL = SEGS.sumOf { it.len.toDouble() }.toFloat()

/** Draws the logo with the S visible between [tail] and [head] (fractions of the whole path). */
fun DrawScope.drawLapisLogo(head: Float, tail: Float, tip: Boolean, flash: Float = 0f) {
    val s = size.minDimension / UNIT
    withTransform({
        translate((size.width - UNIT * s) / 2f, (size.height - UNIT * s) / 2f)
        scale(s, s, Offset.Zero)
    }) {
        block(UPPER, UPPER_TOP, UPPER_BOTTOM)
        block(LOWER, LOWER_TOP, LOWER_BOTTOM)
        val a = tail.coerceIn(0f, 1f) * TOTAL
        val b = head.coerceIn(0f, 1f) * TOTAL
        var acc = 0f
        var pen: Offset? = null
        for (seg in SEGS) {
            val s0 = acc; val s1 = acc + seg.len; acc = s1
            val lo = maxOf(a, s0); val hi = minOf(b, s1)
            if (hi - lo <= 0.5f) continue
            val f0 = (lo - s0) / seg.len; val f1 = (hi - s0) / seg.len
            val r = seg.r
            val part = if (!seg.vertical) {
                // fromStart = left to right; otherwise right to left
                val x0 = if (seg.fromStart) r.left + r.width * f0 else r.right - r.width * f1
                val x1 = if (seg.fromStart) r.left + r.width * f1 else r.right - r.width * f0
                if (hi == b) pen = Offset(if (seg.fromStart) x1 else x0, r.center.y)
                Rect(x0, r.top, x1, r.bottom)
            } else {
                // connectors are drawn bottom to top
                val y0 = r.bottom - r.height * f1; val y1 = r.bottom - r.height * f0
                if (hi == b) pen = Offset(r.center.x, y0)
                Rect(r.left, y0, r.right, y1)
            }
            bar(part, seg.vertical, flash)
        }
        val p = pen
        if (tip && p != null && head < 1f) {
            drawCircle(Brush.radialGradient(listOf(Color(0xE6EBFFC8), Color(0x00AFEA46)), p, 190f), 190f, p)
        }
    }
}

private fun DrawScope.block(r: Rect, top: Color, bottom: Color) =
    drawRoundRect(Brush.verticalGradient(listOf(top, bottom), r.top, r.bottom), r.topLeft, r.size, CornerRadius(64f, 64f))

private fun DrawScope.bar(r: Rect, vertical: Boolean, flash: Float) {
    if (r.width < 1f || r.height < 1f) return
    val brush = if (vertical) Brush.verticalGradient(listOf(GREEN, LIME, GREEN), r.top, r.bottom)
        else Brush.horizontalGradient(listOf(GREEN, LIME, GREEN), r.left, r.right)
    drawRoundRect(brush, r.topLeft, Size(r.width, r.height), CornerRadius(10f, 10f))
    if (flash > 0f) drawRoundRect(Color.White, r.topLeft, Size(r.width, r.height), CornerRadius(10f, 10f), alpha = 0.45f * flash)
}
