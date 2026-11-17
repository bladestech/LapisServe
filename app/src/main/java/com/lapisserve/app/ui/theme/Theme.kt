package com.lapisserve.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Semantic colours that must stay readable on every theme (not part of the M3 scheme). */
@Immutable
data class StatusColors(
    val good: Color, val onGood: Color, val goodContainer: Color,
    val warn: Color, val warnContainer: Color, val onWarnContainer: Color,
)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(Color(0xFF1B7F4B), Color.White, Color(0xFFBDF0D0), Color(0xFFB25E00), Color(0xFFFFDDB8), Color(0xFF2D1600))
}
val LocalIsDark = staticCompositionLocalOf { true }
val LocalAppearance = staticCompositionLocalOf { Appearance() }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LapisTheme(appearance: Appearance = Appearance(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = when (appearance.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val amoled = appearance.mode == ThemeMode.AMOLED
    var scheme = if (appearance.dynamicColor && Build.VERSION.SDK_INT >= 31)
        (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx))
    else schemeFromSeed(appearance.accent.seed, dark, amoled)
    if (amoled && appearance.dynamicColor) scheme = scheme.copy(background = Color.Black, surface = Color.Black)

    val status = if (dark) StatusColors(Color(0xFF6EE7A8), Color(0xFF00391D), Color(0xFF0E4A2B), Color(0xFFFFB86B), Color(0xFF4F2F00), Color(0xFFFFDDB8))
    else StatusColors(Color(0xFF14753F), Color.White, Color(0xFFC4F2D6), Color(0xFFB25E00), Color(0xFFFFE2BE), Color(0xFF2D1600))

    val base = Typography()
    val tnum = "tnum"   // tabular numerals keep live numbers from jittering as they change
    val type = base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = tnum),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = tnum),
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = tnum),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = tnum),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = tnum),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = tnum),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = tnum),
        labelMedium = base.labelMedium.copy(fontFeatureSettings = tnum),
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(22.dp),
        large = RoundedCornerShape(30.dp), extraLarge = RoundedCornerShape(42.dp),
    )
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalStatusColors provides status, LocalIsDark provides dark, LocalAppearance provides appearance) {
        MaterialExpressiveTheme(colorScheme = scheme, typography = type, shapes = shapes, motionScheme = MotionScheme.expressive(), content = content)
    }
}

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
