package com.rodyto.lenspro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import kotlin.math.sin

/**
 * ZoomDial v3.6 — OPTIMIZADO
 *
 * Cambios v3.6 (sobre v2.1):
 *  ① Throttle haptic por TIEMPO real (MIN_HAPTIC_INTERVAL_MS=20ms) además
 *    del threshold angular TICK_DEG. Antes con flings rápidos podían
 *    dispararse hasta ~120 hápticos/seg → micro-stutters perceptibles.
 *  ② onSmoothZoom: si se pasa, se usa también en el animateDecay final
 *    del fling para asegurar consistencia con el VM.
 *  ③ shadowing seguro `springFloat` preservado.
 */
@Composable
fun ZoomDial(
    currentZoom: Float,
    minZoom: Float = 0.5f,
    maxZoom: Float = CameraControlViewModel.MAX_DIGITAL_ZOOM,
    palette: GlassPalette,
    onZoomChange: (Float) -> Unit,
    onHapticTick: () -> Unit,
    onSmoothZoom: ((Float) -> Unit)? = null
) {
    val rotation = remember { Animatable(zoomToAngle(currentZoom)) }
    val scope = rememberCoroutineScope()
    var lastTickAngle by remember { mutableStateOf(zoomToAngle(currentZoom)) }
    // FIX v3.6: throttle haptic por tiempo
    var lastHapticMs by remember { mutableLongStateOf(0L) }

    fun maybeHaptic(currentAngle: Float) {
        val nowMs = System.currentTimeMillis()
        if (kotlin.math.abs(currentAngle - lastTickAngle) >= TICK_DEG &&
            nowMs - lastHapticMs >= MIN_HAPTIC_INTERVAL_MS
        ) {
            onHapticTick()
            lastTickAngle = currentAngle
            lastHapticMs = nowMs
        }
    }

    LaunchedEffect(currentZoom) {
        val mappedAngle = zoomToAngle(currentZoom)
        if (kotlin.math.abs(mappedAngle - rotation.value) > 8f) {
            rotation.snapTo(mappedAngle)
            lastTickAngle = mappedAngle
        }
    }

    androidx.compose.foundation.layout.Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(220.dp)
                .clip(CircleShape)
                .gaussianBlur(20f, strong = true)
                .background(palette.ultraBase, CircleShape)
                .border(0.8.dp, palette.ultraStroke, CircleShape)
                .pointerInput(maxZoom) {
                    detectDragGestures(
                        onDragStart = {
                            scope.launch { rotation.stop() }
                        },
                        onDragEnd = {
                            scope.launch {
                                rotation.animateDecay(
                                    initialVelocity = 0f,
                                    animationSpec = exponentialDecay(
                                        frictionMultiplier = 0.9f,
                                        absVelocityThreshold = 0.5f
                                    )
                                ) {
                                    val z = angleToZoom(value).coerceIn(minZoom, maxZoom)
                                    onZoomChange(z)
                                    maybeHaptic(value)
                                }
                            }
                        },
                        onDrag = { change, drag ->
                            val pos = change.position
                            val c = Offset(size.width / 2f, size.height / 2f)
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
                            if (dAngle > 180f) dAngle -= 360f
                            if (dAngle < -180f) dAngle += 360f
                            scope.launch {
                                val maxAngle = zoomToAngle(maxZoom)
                                val minAngle = zoomToAngle(minZoom)
                                val newAngle = (rotation.value + dAngle).coerceIn(minAngle, maxAngle)
                                rotation.snapTo(newAngle)
                                val z = angleToZoom(rotation.value).coerceIn(minZoom, maxZoom)
                                onZoomChange(z)
                                maybeHaptic(rotation.value)
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f - 6f
                val tickCount = 60
                for (i in 0 until tickCount) {
                    val theta = (i.toFloat() / tickCount) * 2f * PI.toFloat() +
                                Math.toRadians(rotation.value.toDouble()).toFloat()
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
            val z = angleToZoom(rotation.value).coerceIn(minZoom, maxZoom)
            Text(
                text = formatZoomLabel(z),
                color = palette.onGlass,
                fontSize = if (z >= 10f) 24.sp else 22.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(palette.ultraBase)
                .border(0.6.dp, palette.borderSoft, RoundedCornerShape(24.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val snaps = listOf(0.5f, 1f, 3f, 10f, 30f).filter { it in minZoom..maxZoom }
            snaps.forEach { snap ->
                ZoomSnapChip(
                    label = formatZoomLabel(snap),
                    selected = kotlin.math.abs(currentZoom - snap) < 0.05f,
                    palette = palette
                ) {
                    scope.launch {
                        rotation.animateTo(
                            zoomToAngle(snap),
                            springFloat(
                                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                                stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                            )
                        )
                    }
                    if (onSmoothZoom != null) onSmoothZoom(snap) else onZoomChange(snap)
                    onHapticTick()
                }
            }
        }
    }
}

@Composable
private fun ZoomSnapChip(
    label: String,
    selected: Boolean,
    palette: GlassPalette,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (selected) palette.accent.copy(alpha = 0.96f)
                else Color.Transparent
            )
            .border(
                width = if (selected) 0.dp else 0.5.dp,
                color = if (selected) Color.Transparent else palette.borderSoft,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (selected) palette.onAccent else palette.onGlass,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatZoomLabel(z: Float): String = when {
    z < 1f -> ".5×"
    z < 10f -> "%.1f×".format(z)
    else -> "${z.toInt()}×"
}

@Suppress("FunctionName")
private fun springFloat(
    dampingRatio: Float,
    stiffness: Float
) = androidx.compose.animation.core.spring<Float>(
    dampingRatio = dampingRatio,
    stiffness = stiffness
)

private const val TICK_DEG = 6f          // muesca cada 6° → ~60 ticks/vuelta
private const val ZOOM_K   = 0.011f      // sensibilidad logarítmica
private const val MIN_HAPTIC_INTERVAL_MS = 20L

private fun angleToZoom(angleDeg: Float): Float =
    Math.exp((angleDeg * ZOOM_K).toDouble()).toFloat()

private fun zoomToAngle(zoom: Float): Float =
    (ln(zoom.coerceAtLeast(0.01f).toDouble()) / ZOOM_K).toFloat()
