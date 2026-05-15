package com.rodyto.lenspro

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/* ================================================================
 *  LensPro — Tokens iOS 16 Liquid Glass / Ultra Thin Material
 * ================================================================ */

// ---------- Accent system ----------
val LensAccent       = Color(0xFFFFD60A)
val LensAccentSoft   = Color(0xFFFFE066)
val LensRecRed       = Color(0xFFFF3B30)
val LensRecRedSoft   = Color(0xFFFF6A60)

// ---------- Ultra Thin Material (Dark) ----------
// Replicamos UIVisualEffectView .systemUltraThinMaterialDark con tinte translúcido,
// stroke con vibrancia y sombra interior.
val UltraThinDarkBase     = Color(0xCC0E0E10)  // negro casi opaco para chrome
val UltraThinDarkSurface  = Color(0x33FFFFFF)  // capa de luz superior 20 % blanco
val UltraThinDarkStroke   = Color(0x40FFFFFF)  // contorno suave 25 %
val UltraThinDarkStrokeInner = Color(0x14FFFFFF)

// ---------- Ultra Thin Material (Light) ----------
val UltraThinLightBase    = Color(0xCCF6F6F8)
val UltraThinLightSurface = Color(0x33FFFFFF)
val UltraThinLightStroke  = Color(0x33000000)
val UltraThinLightStrokeInner = Color(0x14000000)

// ---------- Vibrancy ----------
// Capa que multiplica/saturará iconos sobre el cristal — extrae color del fondo.
val VibrancyDarkPrimary   = Color(0xF2FFFFFF)
val VibrancyDarkSecondary = Color(0xB3FFFFFF)
val VibrancyLightPrimary  = Color(0xF21A1A1A)
val VibrancyLightSecondary= Color(0xA61A1A1A)

/** Paleta Liquid Glass completa para todos los componentes. */
data class GlassPalette(
    val bg: Color,
    val bgStrong: Color,
    val border: Color,
    val borderSoft: Color,
    val onGlass: Color,
    val onGlassSecondary: Color,
    // -- Liquid Glass extensions --
    val ultraBase: Color,
    val ultraSurface: Color,
    val ultraStroke: Color,
    val ultraStrokeInner: Color,
    val vibrancyPrimary: Color,
    val vibrancySecondary: Color,
    val isDark: Boolean
)

val GlassDark = GlassPalette(
    bg               = Color.Black.copy(alpha = 0.32f),
    bgStrong         = Color.Black.copy(alpha = 0.55f),
    border           = Color.White.copy(alpha = 0.22f),
    borderSoft       = Color.White.copy(alpha = 0.10f),
    onGlass          = Color.White,
    onGlassSecondary = Color.White.copy(alpha = 0.70f),
    ultraBase        = UltraThinDarkBase,
    ultraSurface     = UltraThinDarkSurface,
    ultraStroke      = UltraThinDarkStroke,
    ultraStrokeInner = UltraThinDarkStrokeInner,
    vibrancyPrimary  = VibrancyDarkPrimary,
    vibrancySecondary= VibrancyDarkSecondary,
    isDark           = true
)

val GlassLight = GlassPalette(
    bg               = Color.White.copy(alpha = 0.36f),
    bgStrong         = Color.White.copy(alpha = 0.62f),
    border           = Color.Black.copy(alpha = 0.18f),
    borderSoft       = Color.Black.copy(alpha = 0.08f),
    onGlass          = Color(0xFF101010),
    onGlassSecondary = Color(0xFF555555),
    ultraBase        = UltraThinLightBase,
    ultraSurface     = UltraThinLightSurface,
    ultraStroke      = UltraThinLightStroke,
    ultraStrokeInner = UltraThinLightStrokeInner,
    vibrancyPrimary  = VibrancyLightPrimary,
    vibrancySecondary= VibrancyLightSecondary,
    isDark           = false
)

private val DarkScheme = darkColorScheme(
    primary = LensAccent, onPrimary = Color.Black,
    background = Color.Black, surface = Color.Black,
    onBackground = Color.White, onSurface = Color.White
)

private val LightScheme = lightColorScheme(
    primary = LensAccent, onPrimary = Color.Black,
    background = Color(0xFFF2F2F7), surface = Color.White,
    onBackground = Color(0xFF101010), onSurface = Color(0xFF101010)
)

@Composable
fun LensProTheme(forceDark: Boolean? = null, content: @Composable () -> Unit) {
    val isDark = forceDark ?: isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (isDark) DarkScheme else LightScheme,
        content = content
    )
}

@Composable
fun glassPalette(forceDark: Boolean? = null): GlassPalette {
    val isDark = forceDark ?: isSystemInDarkTheme()
    return if (isDark) GlassDark else GlassLight
}
