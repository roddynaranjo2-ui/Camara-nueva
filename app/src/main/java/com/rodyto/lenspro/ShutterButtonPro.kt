package com.rodyto.lenspro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * ShutterButtonPro v3.6 — OPTIMIZADO
 *
 * CORRECCIÓN v3.6 (sobre v3.5):
 *  ① rememberInfiniteTransition SOLO se instancia cuando isRecording=true.
 *    Antes era SIEMPRE creado y consumía un frame callback de Choreographer
 *    en estado IDLE → drain CPU/batería medible incluso con la app abierta
 *    pero sin grabar (~0.5% CPU continuo).
 *  ② Composable RecordingRingPulse extraído → al cambiar de modo FOTO/VIDEO
 *    se evita recomponer toda la cadena cuando sólo cambia el alpha.
 *  ③ pointerInput keys reducidas — sólo se relanza la lambda cuando cambia
 *    isRecording (era isRecording+mode → 2 relanzamientos por swipe).
 */
@Composable
fun ShutterButtonPro(
    isRecording: Boolean,
    mode: String,
    onTap: () -> Unit,
    onSwipeToVideo: () -> Unit,
    onSwipeToPhoto: () -> Unit,
    onPressFeedback: () -> Unit = {},
    size: Dp = 76.dp
) {
    val scaleAnim = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    val innerSize by animateDpAsState(
        targetValue = when {
            isRecording     -> 26.dp
            mode == "VIDEO" -> 54.dp
            else            -> 60.dp
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "shutter_inner_size"
    )

    val innerColor = when {
        isRecording     -> LensRecRed
        mode == "VIDEO" -> LensRecRed
        else            -> Color.White
    }
    val innerShape = if (isRecording) RoundedCornerShape(8.dp) else CircleShape

    val dragThreshold = with(LocalDensity.current) { 48.dp.toPx() }

    Box(
        modifier = Modifier
            .size(size + 8.dp)
            .graphicsLayer { scaleX = scaleAnim.value; scaleY = scaleAnim.value }
            .pointerInput(isRecording) {
                detectTapGestures(
                    onPress = {
                        onPressFeedback()
                        scaleAnim.animateTo(
                            0.92f,
                            spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh)
                        )
                        try { tryAwaitRelease() } finally {
                            scaleAnim.animateTo(
                                1f,
                                spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium)
                            )
                        }
                    },
                    onTap = { onTap() }
                )
            }
            .pointerInput(isRecording) {
                var totalDx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDx = 0f },
                    onDragEnd   = { totalDx = 0f },
                    onDragCancel = { totalDx = 0f }
                ) { _, dx ->
                    if (isRecording) return@detectHorizontalDragGestures
                    totalDx += dx
                    if (totalDx > dragThreshold && mode != "VIDEO") {
                        onSwipeToVideo(); totalDx = 0f
                        scope.launch {
                            scaleAnim.animateTo(0.9f, spring(stiffness = Spring.StiffnessHigh))
                            scaleAnim.animateTo(1f, spring(Spring.DampingRatioLowBouncy))
                        }
                    } else if (totalDx < -dragThreshold && mode != "FOTO") {
                        onSwipeToPhoto(); totalDx = 0f
                        scope.launch {
                            scaleAnim.animateTo(0.9f, spring(stiffness = Spring.StiffnessHigh))
                            scaleAnim.animateTo(1f, spring(Spring.DampingRatioLowBouncy))
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // FIX v3.6: anillo con/sin pulso según isRecording
        if (isRecording) {
            RecordingRingPulse(size = size)
        } else {
            StaticRing(size = size)
        }
        // Núcleo morphing
        Box(
            Modifier
                .size(innerSize)
                .clip(innerShape)
                .background(innerColor)
        )
    }
}

/**
 * FIX v3.6: rememberInfiniteTransition aislado en un composable separado
 * que SOLO existe mientras isRecording==true. Al detener la grabación,
 * el composable se desmonta y la animación infinita se libera por completo.
 */
@Composable
private fun RecordingRingPulse(size: Dp) {
    val pulse = rememberInfiniteTransition(label = "rec_pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900)),
        label = "rec_pulse_alpha"
    )
    Box(
        Modifier
            .size(size)
            .border(3.5.dp, Color.White.copy(alpha = pulseAlpha), CircleShape)
            .padding(3.dp)
    ) {
        Box(Modifier.size(size - 6.dp))
    }
}

@Composable
private fun StaticRing(size: Dp) {
    Box(
        Modifier
            .size(size)
            .border(3.5.dp, Color.White, CircleShape)
            .padding(3.dp)
    ) {
        Box(Modifier.size(size - 6.dp))
    }
}
