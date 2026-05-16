package com.rodyto.lenspro

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/* ================================================================
 * LensPro — Tokens visuales (glass / liquid glass) — v2.0
 *
 * AMPLIACIÓN: paleta extendida con NEGRO PREMIUM + GRAPHITE + GOLD
 * para conseguir el look iOS 26 / iPhone 17 Pro Max sin romper la
 * estructura existente. Todas las paletas existentes se conservan.
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
    ),
    // ▼ NUEVAS PALETAS ▼
    OBSIDIAN(
        label = "Negro Obsidiana",
        accent = Color(0xFF0A0A0A),
        accentSoft = Color(0xFF1F1F1F),
        accentGlow = Color(0xFF000000),
        onAccent = Color(0xFFFFFFFF)
    ),
    GRAPHITE(
        label = "Grafito",
        accent = Color(0xFF3A3A3C),
        accentSoft = Color(0xFF8E8E93),
        accentGlow = Color(0xFF1C1C1E),
        onAccent = Color(0xFFFFFFFF)
    ),
    TITANIUM_GOLD(
        label = "Oro titanio",
        accent = Color(0xFFD4A574),
        accentSoft = Color(0xFFE8C9A0),
        accentGlow = Color(0xFFB8895C),
        onAccent = Color(0xFF1A1208)
    ),
    DESERT_TITANIUM(
        label = "Titanio desierto",
        accent = Color(0xFFBFA48F),
        accentSoft = Color(0xFFE5D5C5),
        accentGlow = Color(0xFF8A6E58),
        onAccent = Color(0xFF1B130C)
    ),
    CRIMSON(
        label = "Carmesí",
        accent = Color(0xFFFF2D55),
        accentSoft = Color(0xFFFF8AA0),
        accentGlow = Color(0xFFCC0033),
        onAccent = Color(0xFF1A0008)
    );

    /** Indica si el accent es un negro/grafito (afecta el chrome glass). */
    val isMonochrome: Boolean
        get() = this == OBSIDIAN || this == GRAPHITE
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

// ▼ Variantes especiales para paletas monocromas (negro/grafito): chrome más oscuro y stroke dorado-sutil
private val UltraThinObsidianBase = Color(0xC60A0A0A)
private val UltraThinObsidianSurface = Color(0x33FFFFFF)
private val UltraThinObsidianStroke = Color(0x44FFFFFF)
private val UltraThinObsidianStrokeInner = Color(0x18FFFFFF)
private val UltraThinObsidianShadow = Color(0x99000000)

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

private fun glassDark(style: AccentStyle): GlassPalette {
    val mono = style.isMonochrome
    return GlassPalette(
        bg = if (mono) Color(0x800A0A0A) else Color(0x66131A25),
        bgStrong = if (mono) Color(0xE6000000) else Color(0xCC0A0E14),
        border = Color(0x52FFFFFF),
        borderSoft = Color(0x1FFFFFFF),
        onGlass = VibrancyDarkPrimary,
        onGlassSecondary = VibrancyDarkSecondary,
        ultraBase = if (mono) UltraThinObsidianBase else UltraThinDarkBase,
        ultraSurface = if (mono) UltraThinObsidianSurface else UltraThinDarkSurface,
        ultraStroke = if (mono) UltraThinObsidianStroke else UltraThinDarkStroke,
        ultraStrokeInner = if (mono) UltraThinObsidianStrokeInner else UltraThinDarkStrokeInner,
        shadow = if (mono) UltraThinObsidianShadow else UltraThinDarkShadow,
        vibrancyPrimary = VibrancyDarkPrimary,
        vibrancySecondary = VibrancyDarkSecondary,
        accent = style.accent,
        accentSoft = style.accentSoft,
        accentGlow = style.accentGlow,
        onAccent = style.onAccent,
        letterboxTop = if (mono) Color(0xF0000000) else Color(0xE0090D13),
        letterboxBottom = if (mono) Color(0xF0050505) else Color(0xE00A0E14),
        isDark = true
    )
}

private fun glassLight(style: AccentStyle): GlassPalette {
    val mono = style.isMonochrome
    return GlassPalette(
        bg = if (mono) Color(0x802C2C2E) else Color(0x73F7FAFF),
        bgStrong = if (mono) Color(0xEA1C1C1E) else Color(0xEAF7F9FC),
        border = if (mono) Color(0x33FFFFFF) else Color(0x260D1522),
        borderSoft = if (mono) Color(0x1AFFFFFF) else Color(0x120D1522),
        onGlass = if (mono) VibrancyDarkPrimary else VibrancyLightPrimary,
        onGlassSecondary = if (mono) VibrancyDarkSecondary else VibrancyLightSecondary,
        ultraBase = if (mono) UltraThinObsidianBase else UltraThinLightBase,
        ultraSurface = if (mono) UltraThinObsidianSurface else UltraThinLightSurface,
        ultraStroke = if (mono) UltraThinObsidianStroke else UltraThinLightStroke,
        ultraStrokeInner = if (mono) UltraThinObsidianStrokeInner else UltraThinLightStrokeInner,
        shadow = if (mono) UltraThinObsidianShadow else UltraThinLightShadow,
        vibrancyPrimary = if (mono) VibrancyDarkPrimary else VibrancyLightPrimary,
        vibrancySecondary = if (mono) VibrancyDarkSecondary else VibrancyLightSecondary,
        accent = style.accent,
        accentSoft = style.accentSoft,
        accentGlow = style.accentGlow,
        onAccent = style.onAccent,
        letterboxTop = if (mono) Color(0xF0000000) else Color(0xE5F3F7FB),
        letterboxBottom = if (mono) Color(0xF0050505) else Color(0xEAF7FAFD),
        isDark = mono // monocroma => fuerza chrome oscuro aunque el sistema esté en claro
    )
}

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
    // Si la paleta es monocroma forzamos chrome oscuro para mantener la estética premium negra
    val effectiveDark = isDark || accentStyle.isMonochrome
    return if (effectiveDark) glassDark(accentStyle) else glassLight(accentStyle)
}
