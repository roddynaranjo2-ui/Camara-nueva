package com.rodyto.lenspro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Dial de zoom circular tipo "Apple Watch crown" — pieza maestra de física.
 *
 *  - Drag tangencial al borde del dial → rotación.
 *  - La rotación se mapea logarítmicamente al zoom: zoom = base × exp(angle × k).
 *    Da granularidad fina cerca de 1× y se acelera hacia 10×.
 *  - Al soltar, `Animatable.animateDecay(splineBasedDecay)` produce una
 *    deceleración logarítmica (inercia + fricción) idéntica al sistema iOS.
 *  - Tick háptico cada Δ angular suficiente: simula muescas físicas (Taptic Engine).
 */
@Composable
fun ZoomDial(
    currentZoom: Float,
    minZoom: Float = 0.5f,
    maxZoom: Float = 10f,
    palette: GlassPalette,
    onZoomChange: (Float) -> Unit,
    onHapticTick: () -> Unit
) {
    val rotation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var lastTickAngle by remember { mutableStateOf(0f) }
    var center by remember { mutableStateOf(Offset.Zero) }

    // Sincronizar rotación externa → interna al cambiar de lente
    LaunchedEffect(currentZoom) {
        // Solo si el cambio NO viene del dial mismo (delta grande)
        val mappedAngle = zoomToAngle(currentZoom)
        if (kotlin.math.abs(mappedAngle - rotation.value) > 30f) {
            rotation.snapTo(mappedAngle)
            lastTickAngle = mappedAngle
        }
    }

    Box(
        modifier = Modifier
            .size(220.dp)
            .clip(CircleShape)
            .gaussianBlur(20f, strong = true)
            .background(palette.ultraBase, CircleShape)
            .border(0.8.dp, palette.ultraStroke, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        center = Offset(size.width / 2f, size.height / 2f)
                        rotation.stop() // detiene la decay anterior
                    },
                    onDragEnd = {
                        // Inercia logarítmica
                        scope.launch {
                            rotation.animateDecay(
                                initialVelocity = rotation.velocity,
                                animationSpec = exponentialDecay(
                                    frictionMultiplier = 1.2f,
                                    absVelocityThreshold = 0.5f
                                )
                            ) {
                                val z = angleToZoom(value).coerceIn(minZoom, maxZoom)
                                onZoomChange(z)
                                tickIfNeeded(value, lastTickAngleRef = ::lastTickAngle::set, lastTickAngle, onHapticTick)
                                if (kotlin.math.abs(value - lastTickAngle) >= TICK_DEG) {
                                    lastTickAngle = value
                                }
                            }
                        }
                    },
                    onDrag = { change, drag ->
                        val pos = change.position
                        val c = Offset(size.width / 2f, size.height / 2f)
                        // Ángulo (en grados) del puntero respecto al centro
                        val a1 = atan2(
                            (pos.y - c.y).toDouble(),
                            (pos.x - c.x).toDouble()
                        )
                        val prev = pos - drag
                        val a0 = atan2(
                            (prev.y - c.y).toDouble(),
                            (prev.x - c.x).toDouble()
                        )
                        var dAngle = Math.toDegrees(a1 - a0).toFloat()
                        // Wrap-around
                        if (dAngle > 180f) dAngle -= 360f
                        if (dAngle < -180f) dAngle += 360f
                        scope.launch {
                            rotation.snapTo(rotation.value + dAngle)
                            val z = angleToZoom(rotation.value).coerceIn(minZoom, maxZoom)
                            onZoomChange(z)
                            if (kotlin.math.abs(rotation.value - lastTickAngle) >= TICK_DEG) {
                                onHapticTick()
                                lastTickAngle = rotation.value
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Marcas radiales pintadas
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f - 6f
            val tickCount = 60
            for (i in 0 until tickCount) {
                val theta = (i.toFloat() / tickCount) * 2f * PI.toFloat() + Math.toRadians(rotation.value.toDouble()).toFloat()
                val isMajor = i % 5 == 0
                val rOuter = radius
                val rInner = radius - if (isMajor) 14f else 7f
                val sx = this.center.x + cos(theta) * rOuter
                val sy = this.center.y + sin(theta) * rOuter
                val ex = this.center.x + cos(theta) * rInner
                val ey = this.center.y + sin(theta) * rInner
                drawLine(
                    color = if (isMajor) palette.onGlass else palette.onGlassSecondary,
                    start = Offset(sx, sy),
                    end = Offset(ex, ey),
                    strokeWidth = if (isMajor) 2.4f else 1.2f
                )
            }
        }
        // Etiqueta central de zoom actual
        Text(
            text = "%.1fx".format(angleToZoom(rotation.value).coerceIn(minZoom, maxZoom)),
            color = palette.onGlass,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private const val TICK_DEG = 6f          // muesca cada 6° → ~60 ticks/vuelta
private const val ZOOM_K   = 0.012f      // sensibilidad (rad/° equivalente)

/** Mapeo logarítmico: angle → zoom. */
private fun angleToZoom(angleDeg: Float): Float =
    Math.exp((angleDeg * ZOOM_K).toDouble()).toFloat()

/** Inverso logarítmico: zoom → angle (deg). */
private fun zoomToAngle(zoom: Float): Float =
    (ln(zoom.coerceAtLeast(0.01f).toDouble()) / ZOOM_K).toFloat()

private fun tickIfNeeded(
    angle: Float,
    lastTickAngleRef: (Float) -> Unit,
    lastTickAngle: Float,
    onTick: () -> Unit
) {
    if (kotlin.math.abs(angle - lastTickAngle) >= TICK_DEG) {
        onTick()
        lastTickAngleRef(angle)
    }
}
