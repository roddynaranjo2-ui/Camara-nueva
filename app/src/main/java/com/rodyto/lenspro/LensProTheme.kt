package com.rodyto.lenspro

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/* ================================================================
 * LensPro — tokens visuales glass / liquid glass
 * ================================================================ */

enum class AccentStyle(
    val label: String,
    val accent: Color,
    val accentSoft: Color,
    val accentGlow: Color,
    val onAccent: Color
) {
    ICE_BLUE(
        label = "Azul hielo",
        accent = Color(0xFF67C6FF),
        accentSoft = Color(0xFFB9E6FF),
        accentGlow = Color(0xFF2E8BFF),
        onAccent = Color(0xFF04111B)
    ),
    AURORA(
        label = "Aurora",
        accent = Color(0xFF8D7CFF),
        accentSoft = Color(0xFFD3CBFF),
        accentGlow = Color(0xFF5A45FF),
        onAccent = Color(0xFF0B0820)
    ),
    JADE(
        label = "Jade",
        accent = Color(0xFF61D6B4),
        accentSoft = Color(0xFFC5F3E5),
        accentGlow = Color(0xFF23A67F),
        onAccent = Color(0xFF041711)
    )
}

val LensAccent = AccentStyle.ICE_BLUE.accent
val LensAccentSoft = AccentStyle.ICE_BLUE.accentSoft
val LensRecRed = Color(0xFFFF453A)
val LensRecRedSoft = Color(0xFFFF8A80)

private val UltraThinDarkBase = Color(0xB2141820)
private val UltraThinDarkSurface = Color(0x40FFFFFF)
private val UltraThinDarkStroke = Color(0x5EFFFFFF)
private val UltraThinDarkStrokeInner = Color(0x1FFFFFFF)
private val UltraThinDarkShadow = Color(0x66000000)

private val UltraThinLightBase = Color(0xD9FFFFFF)
private val UltraThinLightSurface = Color(0x8AFFFFFF)
private val UltraThinLightStroke = Color(0x220D1522)
private val UltraThinLightStrokeInner = Color(0x12FFFFFF)
private val UltraThinLightShadow = Color(0x14000000)

private val VibrancyDarkPrimary = Color(0xF5FFFFFF)
private val VibrancyDarkSecondary = Color(0xB8E2EEFF)
private val VibrancyLightPrimary = Color(0xFF0F1724)
private val VibrancyLightSecondary = Color(0xB3142133)

data class GlassPalette(
    val bg: Color,
    val bgStrong: Color,
    val border: Color,
    val borderSoft: Color,
    val onGlass: Color,
    val onGlassSecondary: Color,
    val ultraBase: Color,
    val ultraSurface: Color,
    val ultraStroke: Color,
    val ultraStrokeInner: Color,
    val shadow: Color,
    val vibrancyPrimary: Color,
    val vibrancySecondary: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentGlow: Color,
    val onAccent: Color,
    val letterboxTop: Color,
    val letterboxBottom: Color,
    val isDark: Boolean
)

private fun glassDark(style: AccentStyle) = GlassPalette(
    bg = Color(0x66131A25),
    bgStrong = Color(0xCC0A0E14),
    border = Color(0x52FFFFFF),
    borderSoft = Color(0x1FFFFFFF),
    onGlass = VibrancyDarkPrimary,
    onGlassSecondary = VibrancyDarkSecondary,
    ultraBase = UltraThinDarkBase,
    ultraSurface = UltraThinDarkSurface,
    ultraStroke = UltraThinDarkStroke,
    ultraStrokeInner = UltraThinDarkStrokeInner,
    shadow = UltraThinDarkShadow,
    vibrancyPrimary = VibrancyDarkPrimary,
    vibrancySecondary = VibrancyDarkSecondary,
    accent = style.accent,
    accentSoft = style.accentSoft,
    accentGlow = style.accentGlow,
    onAccent = style.onAccent,
    letterboxTop = Color(0xE0090D13),
    letterboxBottom = Color(0xE00A0E14),
    isDark = true
)

private fun glassLight(style: AccentStyle) = GlassPalette(
    bg = Color(0x73F7FAFF),
    bgStrong = Color(0xEAF7F9FC),
    border = Color(0x260D1522),
    borderSoft = Color(0x120D1522),
    onGlass = VibrancyLightPrimary,
    onGlassSecondary = VibrancyLightSecondary,
    ultraBase = UltraThinLightBase,
    ultraSurface = UltraThinLightSurface,
    ultraStroke = UltraThinLightStroke,
    ultraStrokeInner = UltraThinLightStrokeInner,
    shadow = UltraThinLightShadow,
    vibrancyPrimary = VibrancyLightPrimary,
    vibrancySecondary = VibrancyLightSecondary,
    accent = style.accent,
    accentSoft = style.accentSoft,
    accentGlow = style.accentGlow,
    onAccent = style.onAccent,
    letterboxTop = Color(0xE5F3F7FB),
    letterboxBottom = Color(0xEAF7FAFD),
    isDark = false
)

@Composable
fun LensProTheme(
    forceDark: Boolean? = null,
    accentStyle: AccentStyle = AccentStyle.ICE_BLUE,
    content: @Composable () -> Unit
) {
    val isDark = forceDark ?: isSystemInDarkTheme()
    val scheme = if (isDark) {
        darkColorScheme(
            primary = accentStyle.accent,
            onPrimary = accentStyle.onAccent,
            background = Color.Black,
            surface = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White,
            secondary = accentStyle.accentSoft
        )
    } else {
        lightColorScheme(
            primary = accentStyle.accent,
            onPrimary = accentStyle.onAccent,
            background = Color(0xFFF3F7FB),
            surface = Color.White,
            onBackground = Color(0xFF0F1724),
            onSurface = Color(0xFF0F1724),
            secondary = accentStyle.accentSoft
        )
    }

    MaterialTheme(
        colorScheme = scheme,
        content = content
    )
}

@Composable
fun glassPalette(
    forceDark: Boolean? = null,
    accentStyle: AccentStyle = AccentStyle.ICE_BLUE
): GlassPalette {
    val isDark = forceDark ?: isSystemInDarkTheme()
    return if (isDark) glassDark(accentStyle) else glassLight(accentStyle)
}
