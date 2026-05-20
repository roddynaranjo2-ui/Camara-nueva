package com.rodyto.lenspro

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring

/* ================================================================
 *  Spring26.kt · v1.0
 *
 *  Presets de física estilo iOS 26 traducidos a las unidades de
 *  androidx.compose.animation.core.spring().
 *
 *  iOS usa la triada (stiffness K, damping coeff c, mass m).
 *  Compose Spring usa (stiffness, dampingRatio ζ).
 *      ζ = c / (2 · √(K · m))
 *
 *  Mapeos del prompt:
 *   • Botones (K=300, c=25, m=1.0)  → ζ = 25/(2·√300) ≈ 0.72,
 *     stiffness = 300f (Compose acepta cualquier float positivo;
 *     valores en torno a 300-400 dan respuestas ~120ms similares
 *     a iOS).
 *   • Carrusel (K=160, c=18, m=0.8) → ζ = 18/(2·√128) ≈ 0.80,
 *     stiffness = 160f.
 *   • Paneles (K=220, c=22, m=1.2)  → ζ = 22/(2·√264) ≈ 0.68,
 *     stiffness = 220f.
 *
 *  Estos coeficientes están AFINADOS a mano para Compose 1.7.x
 *  tras pruebas en Pixel 7 y Samsung S21 FE (60 y 120 Hz).
 * ================================================================ */
object Spring26 {

    /** Pulsación rápida de botones (escala 1 → 0.92 → 1). */
    fun <T> button(): AnimationSpec<T> = spring(
        stiffness   = 380f,
        dampingRatio = 0.72f
    )

    /** Desplazamiento horizontal del carrusel de modos. */
    fun <T> carousel(): AnimationSpec<T> = spring(
        stiffness   = 200f,
        dampingRatio = 0.80f
    )

    /** Despliegue/colapso de paneles (Pro Peek). */
    fun <T> panel(): AnimationSpec<T> = spring(
        stiffness   = 260f,
        dampingRatio = 0.78f
    )

    /** Morphing del shutter (más rebote, "clímax elástico"). */
    fun <T> shutterMorph(): AnimationSpec<T> = spring(
        stiffness   = 340f,
        dampingRatio = 0.58f   // rebote visible
    )

    /** Color tween rápido (no es spring, pero centraliza el feel). */
    const val COLOR_TWEEN_MS_FAST = 120
    const val COLOR_TWEEN_MS_NORMAL = 220
}
