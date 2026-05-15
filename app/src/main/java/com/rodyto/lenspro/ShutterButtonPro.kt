package com.rodyto.lenspro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * Botón obturador "Liquid Glass" con física Squishy & Morphing.
 *
 *  - Encogimiento a 0.95× al tocar (spring crítico amortiguado).
 *  - Morphing: círculo amarillo (FOTO) → rectángulo redondeado rojo (VIDEO grabando).
 *  - Drag horizontal a la derecha (> umbral) cambia a modo VIDEO y dispara
 *    el cambio en el ViewModel via [onSwipeToVideo]. Drag a la izquierda → FOTO.
 *
 *  Las animaciones son spring para sensación orgánica (no lineal).
 */
@Composable
fun ShutterButtonPro(
    isRecording: Boolean,
    mode: String,              // "FOTO" / "VIDEO"
    onTap: () -> Unit,
    onSwipeToVideo: () -> Unit,
    onSwipeToPhoto: () -> Unit,
    onPressFeedback: () -> Unit = {},
    size: Dp = 82.dp
) {
    val scaleAnim = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    // Tamaño del núcleo: círculo grande (FOTO) ↔ rectángulo pequeño (REC)
    val innerSize by animateDpAsState(
        targetValue = when {
            isRecording -> 28.dp
            mode == "VIDEO" -> 56.dp
            else -> 60.dp
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "shutter_inner_size"
    )

    val innerColor = when {
        isRecording -> LensRecRed
        mode == "VIDEO" -> LensRecRed
        else -> LensAccent
    }
    val ringColor = if (mode == "VIDEO") LensRecRedSoft else LensAccent
    val innerShape = if (isRecording) RoundedCornerShape(8.dp) else CircleShape

    // Acumulador de drag para detección "swipe to video"
    val dragThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { scaleX = scaleAnim.value; scaleY = scaleAnim.value }
            .pointerInput(isRecording, mode) {
                detectTapGestures(
                    onPress = {
                        onPressFeedback()
                        scaleAnim.animateTo(
                            0.95f,
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
                .border(3.5.dp, ringColor, CircleShape)
        )
        // Núcleo morphing
        Box(
            Modifier
                .size(innerSize)
                .clip(innerShape)
                .background(innerColor)
        )
    }
}
