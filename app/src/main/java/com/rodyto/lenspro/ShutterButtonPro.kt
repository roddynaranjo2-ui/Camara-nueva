package com.rodyto.lenspro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Botón obturador premium estilo iOS 19.
 *
 *  ╭─────────────────╮      ╭─────────────────╮
 *  │   ◯ blanco      │  →   │   ◯ rojo morph  │
 *  │   anillo white  │      │  cuadrado rec   │
 *  ╰─────────────────╯      ╰─────────────────╯
 *      FOTO (idle)              VIDEO (rec)
 *
 *  - Anillo exterior siempre BLANCO (no amarillo).
 *  - Núcleo interior: blanco en FOTO, rojo en VIDEO.
 *  - Squish a 0.92× al presionar (spring crítico).
 *  - Pulso lento durante grabación (anillo respira).
 *  - Drag horizontal cambia FOTO ↔ VIDEO.
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
            isRecording -> 26.dp
            mode == "VIDEO" -> 54.dp
            else -> 60.dp
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
    val ringColor = Color.White
    val innerShape = if (isRecording) RoundedCornerShape(8.dp) else CircleShape

    // Pulso "respiración" durante grabación
    val pulse = rememberInfiniteTransition(label = "rec_pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900)),
        label = "rec_pulse_alpha"
    )

    val ringFinalColor = if (isRecording)
        ringColor.copy(alpha = pulseAlpha)
    else ringColor

    val dragThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }

    Box(
        modifier = Modifier
            .size(size + 8.dp)
            .graphicsLayer { scaleX = scaleAnim.value; scaleY = scaleAnim.value }
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
            .pointerInput(isRecording, mode) {
                var totalDx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDx = 0f },
                    onDragEnd = { totalDx = 0f },
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
        // Anillo exterior
        Box(
            Modifier
                .size(size)
                .border(3.5.dp, ringFinalColor, CircleShape)
                .padding(3.dp)
        ) {
            // Espacio interior (transparente)
            Box(Modifier.size(size - 6.dp))
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
