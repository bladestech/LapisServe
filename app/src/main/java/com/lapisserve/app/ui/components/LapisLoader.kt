package com.lapisserve.app.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lapisserve.app.ui.theme.LocalAppearance

/**
 * The LapisServe logo, drawn natively so it is transparent, sharp at any size and animates at the display's refresh rate.
 * Geometry is measured from the original artwork on a 2560-unit square.
 *
 * The loading loop (5 s): rest, the connectors swell, the S comes apart and fades, a single bar drops in and settles into the
 * middle, then the long bars slide back and the connectors spin in and snap the S together.
 */
@Composable
fun LapisLoader(modifier: Modifier = Modifier, size: Dp = 96.dp) {
    val reduce = LocalAppearance.current.reduceMotion
    val t by rememberInfiniteTransition(label = "lapis")
        .animateFloat(0f, CYCLE_MS, infiniteRepeatable(tween(CYCLE_MS.toInt(), easing = LinearEasing)), label = "t")
    // Start at the anticipation, not the rest pose, so even a short wait shows movement; the rest comes at the end of the loop.
    Canvas(modifier.size(size).semantics { contentDescription = "Loading" }) { drawLapisLogo(if (reduce) 0f else (t + START_MS) % CYCLE_MS) }
}

/** The logo at rest. */
@Composable
fun LapisLogo(modifier: Modifier = Modifier, size: Dp = 96.dp) {
    Canvas(modifier.size(size).semantics { contentDescription = "LapisServe" }) { drawLapisLogo(0f) }
}

private const val CYCLE_MS = 5040f
private const val START_MS = 650f
private const val UNIT = 2560f

// Rest geometry (left, top, right, bottom) in artwork units.
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
private val Decel: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val Snap: Easing = Easing { x -> val c1 = 1.70158f; val c3 = c1 + 1f; val y = x - 1f; 1f + c3 * y * y * y + c1 * y * y }

/** Progress of [t] through [start]..[end], eased. */
private fun up(t: Float, start: Float, end: Float, e: Easing = Smooth) = e.transform(((t - start) / (end - start)).coerceIn(0f, 1f))

fun DrawScope.drawLapisLogo(t: Float) {
    val s = size.minDimension / UNIT
    withTransform({
        translate((size.width - UNIT * s) / 2f, (size.height - UNIT * s) / 2f)
        scale(s, s, Offset.Zero)
    }) {
        // ---- timeline
        val swell = up(t, 700f, 1250f)                       // connectors swell (anticipation)
        val apart = up(t, 1250f, 1800f)                      // S separates
        val ghost = up(t, 1850f, 2450f)                      // long bars fade out
        val dropIn = up(t, 1750f, 2300f, Decel)              // single bar falls in
        val settle = up(t, 2450f, 3050f)                     // ...and settles into the middle
        val rebuild = up(t, 2950f, 3800f, Snap)              // long bars and connectors return
        val appear = up(t, 2950f, 3250f, LinearEasing)
        val lock = if (t in 3700f..4400f) 1f - up(t, 3750f, 4400f) else 0f
        val returning = t >= 2700f

        // ---- blocks: breathe in while the S is apart, release with the lock
        val squeeze = 0.03f * swell * (1f - up(t, 3700f, 4200f, Snap))
        block(UPPER, UPPER_TOP, UPPER_BOTTOM, 1f - squeeze)
        block(LOWER, LOWER_TOP, LOWER_BOTTOM, 1f - squeeze)

        // ---- long bars
        if (!returning) {
            bar(TOP, dx = -170f * apart, alpha = 1f - ghost)
            bar(BOTTOM, dx = 150f * apart, alpha = 1f - ghost)
        } else {
            bar(TOP, dx = 650f * (1f - rebuild), alpha = appear, flash = lock)
            bar(BOTTOM, dx = -650f * (1f - rebuild), alpha = appear, flash = lock)
        }

        // ---- middle bar: the original fades as the dropped bar takes its place
        if (t < 1750f) bar(MID, widthScale = 1f - 0.25f * apart)
        else {
            if (t < 2450f) bar(MID, widthScale = 0.75f, alpha = 1f - ghost)
            val y = if (t < 2450f) -500f + (TOP.top + 500f) * dropIn else TOP.top + (MID.top - TOP.top) * settle
            bar(MID, dy = y - MID.top, alpha = up(t, 1750f, 1950f, LinearEasing), flash = lock)
        }

        // ---- connectors: swell and vanish, then spin back in from opposite corners
        if (!returning) {
            val out = up(t, 1250f, 1700f)
            bar(LEFT_V, thickScale = 1f + 0.35f * swell + 0.4f * out, alpha = 1f - out, vertical = true)
            bar(RIGHT_V, thickScale = 1f + 0.35f * swell + 0.4f * out, alpha = 1f - out, vertical = true)
        } else {
            val k = 1f - rebuild
            bar(LEFT_V, dx = -420f * k, dy = -380f * k, rotation = -38f * k, alpha = appear, vertical = true, flash = lock)
            bar(RIGHT_V, dx = 420f * k, dy = 380f * k, rotation = 38f * k, alpha = appear, vertical = true, flash = lock)
        }
    }
}

private fun DrawScope.block(r: Rect, top: Color, bottom: Color, scale: Float) {
    val w = r.width * scale; val h = r.height * scale
    val tl = Offset(r.center.x - w / 2f, r.center.y - h / 2f)
    drawRoundRect(Brush.verticalGradient(listOf(top, bottom), tl.y, tl.y + h), tl, Size(w, h), CornerRadius(64f, 64f))
}

private fun DrawScope.bar(
    r: Rect, dx: Float = 0f, dy: Float = 0f, alpha: Float = 1f, widthScale: Float = 1f, thickScale: Float = 1f,
    rotation: Float = 0f, vertical: Boolean = false, flash: Float = 0f,
) {
    if (alpha <= 0.002f) return
    val w = if (vertical) r.width * thickScale else r.width * widthScale
    val h = if (vertical) r.height else r.height * thickScale
    val c = Offset(r.center.x + dx, r.center.y + dy)
    val tl = Offset(c.x - w / 2f, c.y - h / 2f)
    val brush = if (vertical) Brush.verticalGradient(listOf(GREEN, LIME, GREEN), tl.y, tl.y + h)
        else Brush.horizontalGradient(listOf(GREEN, LIME, GREEN), tl.x, tl.x + w)
    rotate(rotation, c) {
        drawRoundRect(brush, tl, Size(w, h), CornerRadius(10f, 10f), alpha = alpha.coerceIn(0f, 1f))
        if (flash > 0f) drawRoundRect(Color.White, tl, Size(w, h), CornerRadius(10f, 10f), alpha = 0.45f * flash * alpha)
    }
}
