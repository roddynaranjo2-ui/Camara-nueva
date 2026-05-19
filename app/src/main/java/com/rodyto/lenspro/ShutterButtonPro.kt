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
 * ShutterButtonPro v3.8 — OPTIMIZADO
 *
 * NOVEDADES v3.8 (sobre v3.6):
 *  • FIX bug B-06: el bloque `pointerInput(isRecording)` antiguo dejaba
 *    una captura STALE del parámetro `mode` cuando cambiaba FOTO↔VIDEO:
 *    la lambda interna `if (mode != "VIDEO")` seguía evaluando con el
 *    `mode` de la última vez que el pointerInput se relanzó, lo que
 *    causaba que los swipes ignoraran cambios de modo recientes.
 *    Ahora `pointerInput(isRecording, mode)` se relanza también con
 *    cada cambio de modo y la lambda captura el valor actual.
 *
 *  Corrección v3.6 preservada:
 *  ① rememberInfiniteTransition aislado en RecordingRingPulse (sólo
 *    vivo durante isRecording=true).
 *  ② Anillo estático extraído en StaticRing.
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
            // FIX v3.8 (bug B-06): incluir `mode` en la key para que la
            // lambda capture siempre el valor más reciente. Antes, al
            // cambiar de modo el swipe seguía evaluando el mode anterior.
            .pointerInput(isRecording, mode) {
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
            // FIX v3.8 (bug B-06): mismo motivo — `mode` en la key.
            .pointerInput(isRecording, mode) {
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
