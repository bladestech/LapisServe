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
import androidx.compose.ui.draw.shadow
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
    val pa = if (animated) a else .3f; val pb = if (animated) b else .6f
    // Deliberately faint: a hint of the accent at the top, nothing that moves under the content by default.
    val strength = if (dark) .55f else .35f

    Box(modifier.fillMaxSize().background(cs.background)
        .drawBehind {
            val w = size.width; val h = size.height; val r = maxOf(w, h)
            fun bloom(color: Color, cx: Float, cy: Float, radius: Float, alpha: Float) = drawRect(
                Brush.radialGradient(listOf(color.copy(alpha = alpha * strength), Color.Transparent), Offset(cx, cy), radius),
            )
            bloom(cs.primary, w * (0.2f + 0.5f * pa), h * (0.02f + 0.08f * pb), r * 0.6f, .22f)
        },
    ) {
        // Without a Surface above us, nothing else sets the default text colour for the whole app.
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides cs.onBackground) { content() }
    }
}

/**
 * The app's card: a solid Material 3 tonal surface (no translucency or shiny edges), a gentle press response
 * when clickable, and a clear accent outline when [tint] marks it as selected or highlighted.
 * (Named GlassCard for history; it is no longer glass.)
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = LocalIsDark.current
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, spring(dampingRatio = 0.7f, stiffness = 500f), label = "press")

    val fill = if (dark) cs.surfaceContainer else cs.surfaceContainerLow
    var m = modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape).background(fill)
    m = if (tint != null) m.background(tint.copy(alpha = if (dark) .12f else .08f)).border(BorderStroke(1.5.dp, tint.copy(alpha = .7f)), shape)
        else m.border(BorderStroke(1.dp, cs.outlineVariant.copy(alpha = if (dark) .45f else .6f)), shape)
    if (onClick != null) m = m.clickable(interaction, indication = null, onClick = onClick)
    Column(m.padding(padding), content = content)
}

/** Floating chrome (nav bar, bottom action bars): a solid raised surface, no blur. */
@Composable
fun Modifier.glassBar(shape: Shape, @Suppress("UNUSED_PARAMETER") hazeState: HazeState? = LocalHazeState.current): Modifier {
    val cs = MaterialTheme.colorScheme
    val dark = LocalIsDark.current
    return this.shadow(if (dark) 0.dp else 6.dp, shape, clip = false).clip(shape)
        .background(if (dark) cs.surfaceContainerHigh else cs.surfaceContainerLowest)
        .border(BorderStroke(1.dp, cs.outlineVariant.copy(alpha = if (dark) .5f else .4f)), shape)
}

/** Hairline divider that reads on glass. */
@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)))
}

val GlassCorner: Dp = 28.dp
