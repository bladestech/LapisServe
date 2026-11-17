package com.lapisserve.app.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Smooth area sparkline for a short history of live samples. */
@Composable
fun Sparkline(
    values: List<Float>, color: Color, modifier: Modifier = Modifier,
    minValue: Float? = null, maxValue: Float? = null, fill: Boolean = true,
) {
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)
    Canvas(modifier) {
        val w = size.width; val h = size.height; val pad = 4.dp.toPx()
        if (values.size < 2) {
            drawLine(track, Offset(0f, h - pad), Offset(w, h - pad), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            return@Canvas
        }
        val lo = minValue ?: values.min().let { if (it == values.max()) it - 1f else it }
        val hi = maxValue ?: values.max().let { if (it == lo) it + 1f else it }
        val range = (hi - lo).coerceAtLeast(0.0001f)
        fun x(i: Int) = i.toFloat() / (values.size - 1) * w
        fun y(v: Float) = h - pad - ((v.coerceIn(lo, hi) - lo) / range) * (h - pad * 2)
        val line = Path().apply {
            moveTo(x(0), y(values[0]))
            for (i in 1 until values.size) {
                val mx = (x(i - 1) + x(i)) / 2f
                cubicTo(mx, y(values[i - 1]), mx, y(values[i]), x(i), y(values[i]))
            }
        }
        if (fill) {
            val area = Path().apply { addPath(line); lineTo(w, h); lineTo(0f, h); close() }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = .34f), Color.Transparent)))
        }
        drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        val last = Offset(x(values.lastIndex), y(values.last()))
        drawCircle(color.copy(alpha = .25f), radius = 7.dp.toPx(), center = last)
        drawCircle(color, radius = 3.5.dp.toPx(), center = last)
    }
}

/** A 270 degree gauge that sweeps to [fraction]; [content] is centred inside the ring. */
@Composable
fun RingGauge(
    fraction: Float, color: Color, modifier: Modifier = Modifier, stroke: Dp = 10.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
    val sweep by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(dampingRatio = 0.9f, stiffness = 70f), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val sw = stroke.toPx()
            val d = minOf(size.width, size.height) - sw
            val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            val arc = Size(d, d)
            drawArc(track, 135f, 270f, false, topLeft, arc, style = Stroke(sw, cap = StrokeCap.Round))
            if (sweep > 0.002f) drawArc(color, 135f, 270f * sweep, false, topLeft, arc, style = Stroke(sw, cap = StrokeCap.Round))
        }
        content()
    }
}
