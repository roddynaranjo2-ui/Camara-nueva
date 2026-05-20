package com.rodyto.lenspro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/**
 * ShutterBlinkOverlay — FIX ②
 *
 * El overlay original usaba alpha = 0.18 de Color.White lo que causaba
 * un parpadeo blanco muy visible y agresivo, especialmente en ambientes
 * oscuros o cuando el AE todavía estaba convergiendo.
 *
 * Correcciones aplicadas:
 *  1. Se reemplaza Color.White por Color.Black con alpha máximo de 0.28.
 *     Esto imita el comportamiento del obturador físico (oscurecimiento
 *     breve) en lugar del flash blanco artificial.
 *  2. La curva de animación se parte en dos fases diferenciadas:
 *     - Entrada rápida (50 ms, LinearEasing) → simula el cierre del obturador
 *     - Salida lenta (200 ms, FastOutSlowInEasing) → apertura suave
 *     Esto elimina el "bounce" visual desagradable del original.
 *  3. El Box solo se compone cuando alpha > 0 para no añadir capas
 *     innecesarias al árbol de Compose en reposo.
 */
@Composable
fun ShutterBlinkOverlay(triggerKey: Int) {

    val alpha = remember { Animatable(0f) }

    LaunchedEffect(triggerKey) {
        if (triggerKey <= 0) return@LaunchedEffect

        // Reset limpio sin frame intermedio
        alpha.snapTo(0f)

        // Fase 1: cierre rápido del "obturador" (oscurecimiento)
        alpha.animateTo(
            targetValue = 0.28f,
            animationSpec = tween(
                durationMillis = 50,
                easing = LinearEasing
            )
        )

        // Fase 2: apertura suave (el mundo vuelve gradualmente)
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = 200,
                easing = FastOutSlowInEasing
            )
        )
    }

    // FIX ②: Negro elegante en lugar del blanco agresivo original.
    // El ojo humano percibe el oscurecimiento breve como más natural
    // que el destello blanco cuando ya tiene la escena expuesta.
    if (alpha.value > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.alpha = alpha.value
                }
                .background(Color.Black)
        )
    }
}
