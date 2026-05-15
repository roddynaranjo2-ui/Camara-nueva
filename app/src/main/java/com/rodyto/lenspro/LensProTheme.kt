package com.rodyto.lenspro

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ================== Tokens iOS-Glass ==================
val LensAccent       = Color(0xFFFFD60A)   // Amarillo característico
val LensAccentSoft   = Color(0xFFFFE066)
val LensRecRed       = Color(0xFFFF3B30)

// Glassmorphism — modo oscuro
val GlassDarkBg          = Color.Black.copy(alpha = 0.32f)
val GlassDarkBgStrong    = Color.Black.copy(alpha = 0.55f)
val GlassDarkBorder      = Color.White.copy(alpha = 0.22f)
val GlassDarkBorderSoft  = Color.White.copy(alpha = 0.10f)

// Glassmorphism — modo claro
val GlassLightBg         = Color.White.copy(alpha = 0.36f)
val GlassLightBgStrong   = Color.White.copy(alpha = 0.62f)
val GlassLightBorder     = Color.Black.copy(alpha = 0.18f)
val GlassLightBorderSoft = Color.Black.copy(alpha = 0.08f)

/** Conjunto de colores Glass conmutable según tema. */
data class GlassPalette(
    val bg: Color,
    val bgStrong: Color,
    val border: Color,
    val borderSoft: Color,
    val onGlass: Color,
    val onGlassSecondary: Color
)

val GlassDark = GlassPalette(
    bg = GlassDarkBg, bgStrong = GlassDarkBgStrong,
    border = GlassDarkBorder, borderSoft = GlassDarkBorderSoft,
    onGlass = Color.White, onGlassSecondary = Color.White.copy(alpha = 0.7f)
)
val GlassLight = GlassPalette(
    bg = GlassLightBg, bgStrong = GlassLightBgStrong,
    border = GlassLightBorder, borderSoft = GlassLightBorderSoft,
    onGlass = Color(0xFF101010), onGlassSecondary = Color(0xFF555555)
)

private val DarkScheme = darkColorScheme(
    primary = LensAccent,
    onPrimary = Color.Black,
    background = Color.Black,
    surface = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightScheme = lightColorScheme(
    primary = LensAccent,
    onPrimary = Color.Black,
    background = Color(0xFFF2F2F7),
    surface = Color.White,
    onBackground = Color(0xFF101010),
    onSurface = Color(0xFF101010)
)

/** Tema raíz de la app. Si `forceDark == null` se respeta el sistema. */
@Composable
fun LensProTheme(
    forceDark: Boolean? = null,
    content: @Composable () -> Unit
) {
    val isDark = forceDark ?: isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (isDark) DarkScheme else LightScheme,
        content = content
    )
}

/** Accede a la paleta Glass correcta. */
@Composable
fun glassPalette(forceDark: Boolean? = null): GlassPalette {
    val isDark = forceDark ?: isSystemInDarkTheme()
    return if (isDark) GlassDark else GlassLight
}
