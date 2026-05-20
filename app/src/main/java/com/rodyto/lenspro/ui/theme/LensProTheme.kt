package com.rodyto.lenspro

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/* ================================================================
 * LensPro — Tokens visuales (glass / liquid glass) — v2.3
 *
 * v2.3: Nuevas paletas PASTEL minimalistas inspiradas en iOS 26 /
 * iPhone 17 Pro Max / One UI 8. Se conservan TODAS las anteriores.
 * ================================================================ */

enum class AccentStyle(
    val label: String,
    val accent: Color,
    val accentSoft: Color,
    val accentGlow: Color,
    val onAccent: Color
) {
    // ── Originales ──────────────────────────────────────────────────
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
    ),
    // ── Nuevas paletas PASTEL (v2.3) ────────────────────────────────
    PEARL_ROSE(
        label = "Rosa perla",
        accent = Color(0xFFEFB8C8),
        accentSoft = Color(0xFFF9DCE5),
        accentGlow = Color(0xFFD48FA6),
        onAccent = Color(0xFF2C0A12)
    ),
    LAVENDER(
        label = "Lavanda",
        accent = Color(0xFFBFB0E8),
        accentSoft = Color(0xFFDFD8F7),
        accentGlow = Color(0xFF9080C8),
        onAccent = Color(0xFF160C2E)
    ),
    MINT(
        label = "Menta fresca",
        accent = Color(0xFF9EE8C8),
        accentSoft = Color(0xFFCDF5E5),
        accentGlow = Color(0xFF5DC8A0),
        onAccent = Color(0xFF041A11)
    ),
    PEACH(
        label = "Melocotón",
        accent = Color(0xFFF5C6A0),
        accentSoft = Color(0xFFFADFC8),
        accentGlow = Color(0xFFD89870),
        onAccent = Color(0xFF2A1206)
    ),
    SKY(
        label = "Cielo nublado",
        accent = Color(0xFFADD8F0),
        accentSoft = Color(0xFFD5EDF8),
        accentGlow = Color(0xFF70B4D8),
        onAccent = Color(0xFF051520)
    ),
    SAND(
        label = "Arena blanca",
        accent = Color(0xFFE8DCC8),
        accentSoft = Color(0xFFF5EEE2),
        accentGlow = Color(0xFFC0A880),
        onAccent = Color(0xFF201808)
    );

    val isMonochrome: Boolean
        get() = this == OBSIDIAN || this == GRAPHITE

    val isPastel: Boolean
        get() = this in listOf(PEARL_ROSE, LAVENDER, MINT, PEACH, SKY, SAND)
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
    // Para pasteles en tema claro: base ultra-thin levemente teñida con el acento
    val pastelBase = if (style.isPastel)
        style.accentSoft.copy(alpha = 0.72f)
    else
        UltraThinLightBase
    val pastelStroke = if (style.isPastel)
        style.accent.copy(alpha = 0.28f)
    else
        Color(0x260D1522)

    return GlassPalette(
        bg = if (mono) Color(0x802C2C2E) else if (style.isPastel) style.accentSoft.copy(alpha = 0.30f) else Color(0x73F7FAFF),
        bgStrong = if (mono) Color(0xEA1C1C1E) else if (style.isPastel) style.accentSoft.copy(alpha = 0.88f) else Color(0xEAF7F9FC),
        border = if (mono) Color(0x33FFFFFF) else Color(0x260D1522),
        borderSoft = if (mono) Color(0x1AFFFFFF) else if (style.isPastel) style.accent.copy(alpha = 0.18f) else Color(0x120D1522),
        onGlass = if (mono) VibrancyDarkPrimary else VibrancyLightPrimary,
        onGlassSecondary = if (mono) VibrancyDarkSecondary else VibrancyLightSecondary,
        ultraBase = if (mono) UltraThinObsidianBase else pastelBase,
        ultraSurface = if (mono) UltraThinObsidianSurface else if (style.isPastel) Color.White.copy(alpha = 0.65f) else UltraThinLightSurface,
        ultraStroke = if (mono) UltraThinObsidianStroke else pastelStroke,
        ultraStrokeInner = if (mono) UltraThinObsidianStrokeInner else UltraThinLightStrokeInner,
        shadow = if (mono) UltraThinObsidianShadow else UltraThinLightShadow,
        vibrancyPrimary = if (mono) VibrancyDarkPrimary else VibrancyLightPrimary,
        vibrancySecondary = if (mono) VibrancyDarkSecondary else VibrancyLightSecondary,
        accent = style.accent,
        accentSoft = style.accentSoft,
        accentGlow = style.accentGlow,
        onAccent = style.onAccent,
        letterboxTop = if (mono) Color(0xF0000000) else if (style.isPastel) style.accentSoft.copy(alpha = 0.95f) else Color(0xE5F3F7FB),
        letterboxBottom = if (mono) Color(0xF0050505) else if (style.isPastel) style.accentSoft.copy(alpha = 0.98f) else Color(0xEAF7FAFD),
        isDark = mono
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
            background = if (accentStyle.isPastel) accentStyle.accentSoft else Color(0xFFF3F7FB),
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
    val effectiveDark = isDark || accentStyle.isMonochrome
    return if (effectiveDark) glassDark(accentStyle) else glassLight(accentStyle)
}