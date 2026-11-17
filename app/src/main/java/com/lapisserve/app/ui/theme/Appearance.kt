package com.lapisserve.app.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark"), AMOLED("AMOLED") }

enum class Accent(val label: String, val seed: Color) {
    LAPIS("Lapis", Color(0xFF3D6BFF)),
    EMERALD("Emerald", Color(0xFF16B67A)),
    AMBER("Amber", Color(0xFFF2A100)),
    ROSE("Rose", Color(0xFFE8527A)),
    VIOLET("Violet", Color(0xFF8B5CF6)),
    CYAN("Cyan", Color(0xFF12B5CB)),
}

data class Appearance(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val accent: Accent = Accent.LAPIS,
    /** Use the wallpaper-based Material You palette instead of the accent (Android 12+). */
    val dynamicColor: Boolean = false,
    val reduceMotion: Boolean = false,
    val animatedBackdrop: Boolean = true,
)

/** Small persisted app preferences, exposed as a flow so the UI recomposes on change. */
class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val _appearance = MutableStateFlow(load())
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    private fun load() = Appearance(
        mode = runCatching { ThemeMode.valueOf(sp.getString("mode", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        accent = runCatching { Accent.valueOf(sp.getString("accent", "LAPIS")!!) }.getOrDefault(Accent.LAPIS),
        dynamicColor = sp.getBoolean("dynamic", false),
        reduceMotion = sp.getBoolean("reduceMotion", false),
        animatedBackdrop = sp.getBoolean("backdrop", true),
    )

    fun update(f: (Appearance) -> Appearance) {
        val a = f(_appearance.value)
        _appearance.value = a
        sp.edit().putString("mode", a.mode.name).putString("accent", a.accent.name).putBoolean("dynamic", a.dynamicColor)
            .putBoolean("reduceMotion", a.reduceMotion).putBoolean("backdrop", a.animatedBackdrop).apply()
    }
}

private fun hsl(h: Float, s: Float, l: Float) =
    Color(ColorUtils.HSLToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))

/** A full Material scheme derived from one accent colour, tuned for glass surfaces. */
fun schemeFromSeed(seed: Color, dark: Boolean, amoled: Boolean): ColorScheme {
    val v = FloatArray(3).also { ColorUtils.colorToHSL(seed.toArgb(), it) }
    val h = v[0]; val s = v[1].coerceIn(0.5f, 0.95f)
    return if (dark) darkColorScheme(
        primary = hsl(h, s, .72f), onPrimary = hsl(h, s, .14f),
        primaryContainer = hsl(h, s * .8f, .28f), onPrimaryContainer = hsl(h, s, .90f),
        secondary = hsl(h + 24, .38f, .74f), onSecondary = hsl(h + 24, .38f, .16f),
        secondaryContainer = hsl(h + 24, .30f, .26f), onSecondaryContainer = hsl(h + 24, .45f, .90f),
        tertiary = hsl(h + 150, .55f, .70f), onTertiary = hsl(h + 150, .55f, .14f),
        tertiaryContainer = hsl(h + 150, .40f, .26f), onTertiaryContainer = hsl(h + 150, .55f, .90f),
        background = if (amoled) Color.Black else hsl(h, .30f, .065f), onBackground = hsl(h, .15f, .93f),
        surface = if (amoled) Color.Black else hsl(h, .30f, .075f), onSurface = hsl(h, .15f, .93f),
        surfaceVariant = hsl(h, .18f, .20f), onSurfaceVariant = hsl(h, .14f, .76f),
        surfaceContainerLowest = hsl(h, .28f, .04f), surfaceContainerLow = hsl(h, .25f, .09f),
        surfaceContainer = hsl(h, .24f, .115f), surfaceContainerHigh = hsl(h, .22f, .145f), surfaceContainerHighest = hsl(h, .20f, .18f),
        outline = hsl(h, .12f, .56f), outlineVariant = hsl(h, .14f, .30f),
        error = Color(0xFFFF8A80), onError = Color(0xFF4A0A06), errorContainer = Color(0xFF7A1D14), onErrorContainer = Color(0xFFFFDAD5),
    ) else lightColorScheme(
        primary = hsl(h, s, .42f), onPrimary = Color.White,
        primaryContainer = hsl(h, s, .91f), onPrimaryContainer = hsl(h, s, .14f),
        secondary = hsl(h + 24, .30f, .38f), onSecondary = Color.White,
        secondaryContainer = hsl(h + 24, .40f, .90f), onSecondaryContainer = hsl(h + 24, .40f, .14f),
        tertiary = hsl(h + 150, .55f, .33f), onTertiary = Color.White,
        tertiaryContainer = hsl(h + 150, .50f, .88f), onTertiaryContainer = hsl(h + 150, .55f, .12f),
        background = hsl(h, .45f, .965f), onBackground = hsl(h, .25f, .10f),
        surface = hsl(h, .45f, .975f), onSurface = hsl(h, .25f, .10f),
        surfaceVariant = hsl(h, .30f, .90f), onSurfaceVariant = hsl(h, .15f, .30f),
        surfaceContainerLowest = Color.White, surfaceContainerLow = hsl(h, .45f, .955f),
        surfaceContainer = hsl(h, .40f, .94f), surfaceContainerHigh = hsl(h, .35f, .92f), surfaceContainerHighest = hsl(h, .30f, .90f),
        outline = hsl(h, .10f, .45f), outlineVariant = hsl(h, .20f, .80f),
    )
}
