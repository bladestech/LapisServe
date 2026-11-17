package com.lapisserve.app.ui.glass

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lapisserve.app.ui.theme.LocalAppearance
import com.lapisserve.app.ui.theme.LocalIsDark
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * The app's backdrop: a deep base colour with three slowly drifting colour blooms. Everything the user scrolls
 * sits inside the haze source so floating glass bars can genuinely blur whatever passes beneath them.
 */
@Composable
fun LapisBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dark = LocalIsDark.current
    val animated = LocalAppearance.current.animatedBackdrop && !LocalAppearance.current.reduceMotion
    val t = rememberInfiniteTransition(label = "backdrop")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(42_000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(57_000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0f, 1f, infiniteRepeatable(tween(71_000, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    val pa = if (animated) a else .3f; val pb = if (animated) b else .6f; val pc = if (animated) c else .5f
    val strength = if (dark) 1f else .55f

    Box(modifier.fillMaxSize().background(cs.background)
        .drawBehind {
            val w = size.width; val h = size.height; val r = maxOf(w, h)
            fun bloom(color: Color, cx: Float, cy: Float, radius: Float, alpha: Float) = drawRect(
                Brush.radialGradient(listOf(color.copy(alpha = alpha * strength), Color.Transparent), Offset(cx, cy), radius),
            )
            bloom(cs.primary, w * (0.15f + 0.55f * pa), h * (0.08f + 0.18f * pb), r * 0.75f, .30f)
            bloom(cs.tertiary, w * (0.95f - 0.5f * pb), h * (0.45f + 0.15f * pc), r * 0.65f, .16f)
            bloom(cs.secondary, w * (0.1f + 0.35f * pc), h * (0.92f - 0.2f * pa), r * 0.70f, .18f)
        },
    ) {
        // Without a Surface above us, nothing else sets the default text colour for the whole app.
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides cs.onBackground) { content() }
    }
}

/** A frosted card: translucent fill, a light-catching edge, and a gentle press response when clickable. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = LocalIsDark.current
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, spring(dampingRatio = 0.62f, stiffness = 420f), label = "press")

    val fillTop = if (dark) Color.White.copy(alpha = .085f) else Color.White.copy(alpha = .72f)
    val fillBottom = if (dark) Color.White.copy(alpha = .045f) else Color.White.copy(alpha = .50f)
    val edgeHi = if (dark) Color.White.copy(alpha = .28f) else Color.White.copy(alpha = .95f)
    val edgeLo = if (dark) Color.White.copy(alpha = .05f) else cs.outlineVariant.copy(alpha = .35f)

    var m = modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
        .background(Brush.verticalGradient(listOf(fillTop, fillBottom)))
    if (tint != null) m = m.background(tint.copy(alpha = if (dark) .14f else .10f))
    m = m.border(BorderStroke(1.dp, Brush.linearGradient(listOf(edgeHi, edgeLo, edgeHi.copy(alpha = edgeHi.alpha * .35f)))), shape)
    if (onClick != null) m = m.clickable(interaction, indication = null, onClick = onClick)
    Column(m.padding(padding), content = content)
}

/** Real backdrop blur for floating chrome (nav bar, top bar, sheets). Falls back to a tint below Android 12. */
@Composable
fun Modifier.glassBar(shape: Shape, hazeState: HazeState? = LocalHazeState.current): Modifier {
    val cs = MaterialTheme.colorScheme
    val dark = LocalIsDark.current
    val base = this.clip(shape)
    val withBlur = if (hazeState != null) base.hazeChild(hazeState) {
        blurRadius = 22.dp
        noiseFactor = 0.03f
        backgroundColor = cs.surface
        tints = listOf(HazeTint(cs.surface.copy(alpha = if (dark) .30f else .50f)), HazeTint(Color.White.copy(alpha = if (dark) .05f else .22f)))
    } else base.background(cs.surface.copy(alpha = .9f))
    return withBlur.border(
        BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) .26f else .9f), Color.White.copy(alpha = if (dark) .06f else .3f)))),
        shape,
    )
}

/** Hairline divider that reads on glass. */
@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)))
}

val GlassCorner: Dp = 28.dp
