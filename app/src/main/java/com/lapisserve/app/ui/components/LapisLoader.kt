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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lapisserve.app.ui.theme.LocalAppearance
import com.lapisserve.app.ui.theme.LocalIsDark

/**
 * The LapisServe logo drawing itself: the green S is laid down bar by bar (bottom, right, middle, left, top) with a glowing
 * pen tip. Drawn natively, so it is transparent, sharp at any size and animates at the display's refresh rate.
 *
 * - [progress] known (0..1): the S fills to match, e.g. a download percentage.
 * - [progress] null: it runs as a snake, drawing in and then erasing from the start.
 */
@Composable
fun LapisLoader(modifier: Modifier = Modifier, size: Dp = 96.dp, progress: Float? = null, colors: LogoColors = defaultLogoColors()) {
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
            progress != null -> drawLapisLogo(head = shown, tail = 0f, tip = shown < 0.999f, colors = colors)
            reduce -> drawLapisLogo(head = 1f, tail = 0f, tip = false, colors = colors)
            else -> drawLapisLogo(head = up(t, 0f, 1400f), tail = up(t, 1250f, 2600f), tip = true, colors = colors)
        }
    }
}

/** The logo at rest. */
@Composable
fun LapisLogo(modifier: Modifier = Modifier, size: Dp = 96.dp, colors: LogoColors = defaultLogoColors()) {
    Canvas(modifier.size(size).semantics { contentDescription = "LapisServe" }) { drawLapisLogo(1f, 0f, false, colors = colors) }
}

/** Draws the logo once (about [durationMs]) and calls [onDone]; for the launch splash. */
@Composable
fun LapisIntro(modifier: Modifier = Modifier, size: Dp = 140.dp, durationMs: Int = 900, colors: LogoColors = defaultLogoColors(), onDone: () -> Unit = {}) {
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
        drawLapisLogo(head = p.value, tail = 0f, tip = p.value < 0.999f, flash = flash.value, colors = colors)
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

/**
 * Logo colours. [Original] is the artwork; [duo] is a two-tone version built from the app's colour scheme (so it follows the
 * chosen accent and wallpaper colours): accent bars on accent-container blocks.
 */
@Immutable
class LogoColors(
    val barEdge: Color, val barMid: Color,
    val upperTop: Color, val upperBottom: Color, val lowerTop: Color, val lowerBottom: Color,
    val glow: Color,
) {
    companion object {
        val Original = LogoColors(
            barEdge = Color(0xFF60D959), barMid = Color(0xFFAFEA46),
            upperTop = Color(0xFF064584), upperBottom = Color(0xFF01133E), lowerTop = Color(0xFF011236), lowerBottom = Color(0xFF064383),
            glow = Color(0xFFEBFFC8),
        )

        fun duo(cs: ColorScheme, dark: Boolean): LogoColors = if (dark) {
            val top = lerp(cs.primaryContainer, cs.primary, 0.12f); val bottom = lerp(cs.primaryContainer, Color.Black, 0.22f)
            LogoColors(cs.primary, lerp(cs.primary, Color.White, 0.45f), top, bottom, bottom, top, Color.White)
        } else {
            val top = cs.primaryContainer; val bottom = lerp(cs.primaryContainer, cs.primary, 0.14f)
            LogoColors(cs.primary, lerp(cs.primary, Color.White, 0.25f), top, bottom, bottom, top, Color.White)
        }
    }
}

/** Original artwork on dark themes; duo-chrome on light, where the navy blocks would feel heavy. */
@Composable
fun defaultLogoColors(): LogoColors {
    val dark = LocalIsDark.current
    val cs = MaterialTheme.colorScheme
    return remember(dark, cs) { if (dark) LogoColors.Original else LogoColors.duo(cs, dark = false) }
}

/** Duo-chrome in the current theme; for empty states and other places the logo should match the accent. */
@Composable
fun duoLogoColors(): LogoColors {
    val dark = LocalIsDark.current
    val cs = MaterialTheme.colorScheme
    return remember(dark, cs) { LogoColors.duo(cs, dark) }
}

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
fun DrawScope.drawLapisLogo(head: Float, tail: Float, tip: Boolean, flash: Float = 0f, colors: LogoColors = LogoColors.Original) {
    val s = size.minDimension / UNIT
    withTransform({
        translate((size.width - UNIT * s) / 2f, (size.height - UNIT * s) / 2f)
        scale(s, s, Offset.Zero)
    }) { drawLogoUnits(head, tail, tip, flash, colors) }
}

/** The logo in artwork units (0..2560); the caller sets up the transform. */
private fun DrawScope.drawLogoUnits(head: Float, tail: Float, tip: Boolean, flash: Float, colors: LogoColors) {
    run {
        block(UPPER, colors.upperTop, colors.upperBottom)
        block(LOWER, colors.lowerTop, colors.lowerBottom)
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
            bar(part, seg.vertical, flash, colors)
        }
        val p = pen
        if (tip && p != null && head < 1f) {
            drawCircle(Brush.radialGradient(listOf(colors.glow.copy(alpha = 0.9f), colors.glow.copy(alpha = 0f)), p, 190f), 190f, p)
        }
    }
}

private fun DrawScope.block(r: Rect, top: Color, bottom: Color) =
    drawRoundRect(Brush.verticalGradient(listOf(top, bottom), r.top, r.bottom), r.topLeft, r.size, CornerRadius(64f, 64f))

private fun DrawScope.bar(r: Rect, vertical: Boolean, flash: Float, c: LogoColors) {
    if (r.width < 1f || r.height < 1f) return
    val stops = listOf(c.barEdge, c.barMid, c.barEdge)
    val brush = if (vertical) Brush.verticalGradient(stops, r.top, r.bottom) else Brush.horizontalGradient(stops, r.left, r.right)
    drawRoundRect(brush, r.topLeft, Size(r.width, r.height), CornerRadius(10f, 10f))
    if (flash > 0f) drawRoundRect(Color.White, r.topLeft, Size(r.width, r.height), CornerRadius(10f, 10f), alpha = 0.45f * flash)
}

// ------------------------------------------------------------------ launch splash

private const val SPLASH_DRAW_END = 800f       // the S has drawn itself
private const val SPLASH_EXT_START = 820f      // its top bar starts running to the edge
private const val SPLASH_HIT = 1060f           // ...and hits the right side of the screen
private const val SPLASH_WIPE_END = 1680f      // the bar has swept back across the screen
private const val SPLASH_END = 1760f
private val Accel: Easing = CubicBezierEasing(0.6f, 0f, 0.9f, 0.5f)
private val WipeEase: Easing = CubicBezierEasing(0.5f, 0f, 0.2f, 1f)

/**
 * Full-screen launch splash over the app: the S draws itself, its top bar keeps going and hits the right edge of the screen,
 * then turns into a bright bar that sweeps back across and wipes the splash away, revealing the app underneath.
 * [onDone] is called once the screen is clear. Under reduce motion it simply shows the logo briefly.
 */
@Composable
fun LapisSplash(background: Color, colors: LogoColors, modifier: Modifier = Modifier, logoSize: Dp = 148.dp, onDone: () -> Unit) {
    val reduce = LocalAppearance.current.reduceMotion
    val time = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduce) { time.snapTo(SPLASH_DRAW_END); kotlinx.coroutines.delay(350) }
        else time.animateTo(SPLASH_END, tween(SPLASH_END.toInt(), easing = LinearEasing))
        onDone()
    }
    Canvas(modifier.fillMaxSize().semantics { contentDescription = "LapisServe" }) {
        val t = time.value
        val w = size.width; val h = size.height
        val logoPx = logoSize.toPx()
        val ox = (w - logoPx) / 2f; val oy = h * 0.45f - logoPx / 2f; val s = logoPx / UNIT
        val impactY = oy + TOP.center.y * s
        val wipe = up(t, SPLASH_HIT, SPLASH_WIPE_END, WipeEase)
        val wipeX = w * (1f - wipe)
        // The splash layer, cut away from the right as the bar sweeps back.
        if (wipeX > 0f) clipRect(right = wipeX) {
            drawRect(background)
            withTransform({ translate(ox, oy); scale(s, s, Offset.Zero) }) {
                val flash = if (t >= SPLASH_HIT) 1f - up(t, SPLASH_HIT, SPLASH_HIT + 300f, LinearEasing) else 0f
                drawLogoUnits(up(t, 0f, SPLASH_DRAW_END), 0f, tip = t < SPLASH_EXT_START, flash = flash, colors = colors)
                val ext = up(t, SPLASH_EXT_START, SPLASH_HIT, Accel)
                if (ext > 0f && t < SPLASH_HIT + 120f) {
                    val edge = (w - ox) / s + 40f
                    val x1 = TOP.right + (edge - TOP.right) * ext
                    bar(Rect(TOP.right - 10f, TOP.top + 22f, x1, TOP.bottom - 22f), vertical = false, flash = 0f, c = colors)
                    val tipAt = Offset(x1, TOP.center.y)
                    drawCircle(Brush.radialGradient(listOf(colors.glow.copy(alpha = 0.9f), colors.glow.copy(alpha = 0f)), tipAt, 260f), 260f, tipAt)
                }
            }
        }
        if (t >= SPLASH_HIT) {
            // The sweeping bar.
            val half = 26.dp.toPx()
            val fade = 1f - up(t, SPLASH_HIT + 450f, SPLASH_WIPE_END + 60f, LinearEasing)
            if (fade > 0f) drawRect(
                Brush.horizontalGradient(listOf(colors.barEdge.copy(alpha = 0f), colors.barMid, colors.barEdge.copy(alpha = 0f)), wipeX - half, wipeX + half),
                Offset(wipeX - half, 0f), Size(half * 2f, h), alpha = fade,
            )
            // Spark where the line hit the edge.
            val sp = 1f - up(t, SPLASH_HIT, SPLASH_HIT + 350f, LinearEasing)
            if (sp > 0f) {
                val r = 70.dp.toPx() * (1.4f - sp)
                val at = Offset(w, impactY)
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f * sp), colors.barMid.copy(alpha = 0f)), at, r), r, at)
            }
        }
    }
}
