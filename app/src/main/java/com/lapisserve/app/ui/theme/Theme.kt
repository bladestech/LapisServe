package com.lapisserve.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LapisLight = lightColorScheme(
    primary = Color(0xFF2557C7), onPrimary = Color.White,
    primaryContainer = Color(0xFFDAE2FF), onPrimaryContainer = Color(0xFF001848),
    secondary = Color(0xFF585E71), secondaryContainer = Color(0xFFDCE2F9), onSecondaryContainer = Color(0xFF151B2C),
    tertiary = Color(0xFF00696E), tertiaryContainer = Color(0xFF9CF0F5), onTertiaryContainer = Color(0xFF002022),
    background = Color(0xFFFAF8FF), surface = Color(0xFFFAF8FF),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F3FA),
    surfaceContainer = Color(0xFFEEEDF4), surfaceContainerHigh = Color(0xFFE8E7EF), surfaceContainerHighest = Color(0xFFE3E2E9),
)

private val LapisDark = darkColorScheme(
    primary = Color(0xFFB2C5FF), onPrimary = Color(0xFF002B77),
    primaryContainer = Color(0xFF0B3EA4), onPrimaryContainer = Color(0xFFDAE2FF),
    secondary = Color(0xFFC0C6DC), secondaryContainer = Color(0xFF404659), onSecondaryContainer = Color(0xFFDCE2F9),
    tertiary = Color(0xFF80D4DA), tertiaryContainer = Color(0xFF004F53), onTertiaryContainer = Color(0xFF9CF0F5),
    background = Color(0xFF0E1320), surface = Color(0xFF0E1320),
    surfaceContainerLowest = Color(0xFF090D18), surfaceContainerLow = Color(0xFF161B28),
    surfaceContainer = Color(0xFF1A1F2D), surfaceContainerHigh = Color(0xFF252A38), surfaceContainerHighest = Color(0xFF303543),
)

/** Semantic colours that must stay readable on both themes (not part of the M3 scheme). */
@Immutable
data class StatusColors(val good: Color, val onGood: Color, val goodContainer: Color, val warn: Color, val warnContainer: Color, val onWarnContainer: Color)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(Color(0xFF1B7F4B), Color.White, Color(0xFFBDF0D0), Color(0xFFB25E00), Color(0xFFFFDDB8), Color(0xFF2D1600))
}

private val Display = FontFamily.SansSerif

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LapisTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> LapisDark
        else -> LapisLight
    }
    val status = if (dark) StatusColors(Color(0xFF7DDBA3), Color(0xFF00391D), Color(0xFF00522D), Color(0xFFFFB86B), Color(0xFF5A3300), Color(0xFFFFDDB8))
    else StatusColors(Color(0xFF1B7F4B), Color.White, Color(0xFFBDF0D0), Color(0xFFB25E00), Color(0xFFFFDDB8), Color(0xFF2D1600))

    val base = Typography()
    val type = base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = Display, fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
    val shapes = Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(40.dp),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialExpressiveTheme(colorScheme = scheme, typography = type, shapes = shapes, motionScheme = MotionScheme.expressive(), content = content)
    }
}

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
