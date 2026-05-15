package com.rodyto.lenspro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Parpadeo visual de 50 ms al capturar — confirma la toma de forma coherente
 * sin bloquear el visor (totalmente independiente del Surface de cámara).
 *
 * Uso: incrementar `triggerKey` cada vez que se quiera disparar el blink.
 */
@Composable
fun ShutterBlinkOverlay(triggerKey: Int) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(triggerKey) {
        if (triggerKey > 0) {
            // 50 ms total: 25 ms fade-in + 25 ms fade-out
            alpha.snapTo(0f)
            alpha.animateTo(1f, animationSpec = tween(durationMillis = 25))
            alpha.animateTo(0f, animationSpec = tween(durationMillis = 25))
        }
    }
    if (alpha.value > 0f) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha.value }
                .background(Color.White)
        )
    }
}
