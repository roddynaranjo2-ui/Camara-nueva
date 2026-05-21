package com.rodyto.lenspro.ui.overlays

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
 * ShutterBlinkOverlay — v2.0
 *
 * FIX BUG-B2: la documentación reflejaba un comportamiento ("oscurecimiento
 * estilo obturador físico") que NO coincidía con el código real (destello
 * blanco). La industria (iOS, Google Camera) usa un destello blanco breve
 * porque sobre escenas oscuras un oscurecimiento se confunde con el rec.
 *
 * Comportamiento actual (intencional):
 *   1) snapTo(0f) instantáneo para no quedar en frame intermedio.
 *   2) Subida rápida a alpha = 0.28f en 50 ms (LinearEasing).
 *   3) Bajada suave a 0f en 200 ms (FastOutSlowInEasing).
 *   4) Renderiza solo cuando alpha > 0 para no añadir capas al árbol.
 */
@Composable
fun ShutterBlinkOverlay(triggerKey: Int) {

    val alpha = remember { Animatable(0f) }

    LaunchedEffect(triggerKey) {
        if (triggerKey <= 0) return@LaunchedEffect
        alpha.snapTo(0f)
        alpha.animateTo(
            targetValue = 0.28f,
            animationSpec = tween(durationMillis = 50, easing = LinearEasing)
        )
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
        )
    }

    if (alpha.value > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha.value }
                .background(Color.White)   // destello blanco estilo iOS
        )
    }
}
