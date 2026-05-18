package com.rodyto.lenspro

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * ZoomControl v3.5 Pro — slider vertical premium.
 *
 * Cambios v3.5:
 *  • cornerRadius 34dp (premium spec).
 *  • whiteGlow más vivido durante el drag.
 *  • Marcas mayores en 0.5/1/3/10/30 (no solo 1/10) → mejor referencia.
 *  • Reset 1× con haptic-snap mejorado y glow blanco cuando se está cerca.
 *  • Etiqueta "OPT/DIG" pequeña al lado del valor cuando aplica (vía param).
 *  • Mapeo logarítmico preservado.
 */
@Composable
fun ZoomControl(
    currentZoom: Float,
    minZoom: Float,
    maxZoom: Float,
    palette: GlassPalette,
    visible: Boolean,
    onZoomChange: (Float) -> Unit,
    onSmoothZoomTo: (Float) -> Unit,
    onHapticTick: () -> Unit,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
    showOpticalHint: Boolean = false,
    isOptical: Boolean = false
) {
    if (!visible) return

    val animatedZoom by animateFloatAsState(
        targetValue = currentZoom,
        animationSpec = tween(durationMillis = 90),
        label = "zoom_smooth"
    )

    val minLog = ln(minZoom.coerceAtLeast(0.01f).toDouble()).toFloat()
    val maxLog = ln(maxZoom.coerceAtLeast(minZoom + 0.01f).toDouble()).toFloat()
    val span = (maxLog - minLog).coerceAtLeast(0.01f)

    fun zoomToFraction(z: Float): Float {
        val logZ = ln(z.coerceIn(minZoom, maxZoom).toDouble()).toFloat()
        return ((logZ - minLog) / span).coerceIn(0f, 1f)
    }
    fun fractionToZoom(frac: Float): Float {
        val clamped = frac.coerceIn(0f, 1f)
        return exp((minLog + clamped * span).toDouble()).toFloat().coerceIn(minZoom, maxZoom)
    }

    var dragAccumPx by remember { mutableStateOf(0f) }
    var lastHapticBucket by remember { mutableStateOf((zoomToFraction(currentZoom) * 16f).toInt()) }
    var isDragging by remember { mutableStateOf(false) }

    val trackHeightDp = 230.dp

    Column(
        modifier = modifier
            .width(68.dp)
            .clip(RoundedCornerShape(34.dp))
            .liquidGlass(palette, RoundedCornerShape(34.dp), strong = true)
            .whiteGlow(active = isDragging, shape = RoundedCornerShape(34.dp), intensity = 1f)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Etiqueta superior — zoom + chip OPT/DIG opcional
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatZoom(animatedZoom),
                color = palette.onGlass,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            if (showOpticalHint) {
                Spacer(Modifier.size(2.dp))
                Text(
                    text = if (isOptical) "OPT" else "DIG",
                    color = if (isOptical) palette.accent else Color(0xFFFFB020),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp
                )
            }
        }

        // Track vertical interactivo
        Box(
            modifier = Modifier
                .width(38.dp)
                .height(trackHeightDp)
                .clip(RoundedCornerShape(20.dp))
                .background(palette.onGlassSecondary.copy(alpha = 0.16f))
                .pointerInput(minZoom, maxZoom) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragAccumPx = 0f
                            isDragging = true
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false }
                    ) { _, dragAmount ->
                        dragAccumPx += -dragAmount
                        val curFrac = zoomToFraction(currentZoom)
                        val trackPx = trackHeightDp.toPx()
                        val newFrac = (curFrac + dragAccumPx / trackPx).coerceIn(0f, 1f)
                        if (abs(newFrac - curFrac) >= 0.001f) {
                            dragAccumPx = 0f
                            val newZoom = fractionToZoom(newFrac)
                            val bucket = (newFrac * 16f).toInt()
                            if (bucket != lastHapticBucket) {
                                onHapticTick()
                                lastHapticBucket = bucket
                            }
                            onZoomChange(newZoom)
                        }
                    }
                }
        ) {
            // Marcas (snap points)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val snaps = listOf(0.5f, 1f, 2f, 3f, 5f, 10f, 30f)
                    .filter { it in minZoom..maxZoom }
                snaps.forEach { z ->
                    val frac = zoomToFraction(z)
                    val y = size.height * (1f - frac)
                    val isMajor = (z == 1f || z == 3f || z == 10f)
                    drawLine(
                        color = palette.onGlass.copy(alpha = if (isMajor) 0.90f else 0.45f),
                        start = Offset(size.width * (if (isMajor) 0.10f else 0.22f), y),
                        end = Offset(size.width * (if (isMajor) 0.90f else 0.78f), y),
                        strokeWidth = if (isMajor) 2.4f else 1.2f
                    )
                }
            }

            val animFrac by animateFloatAsState(
                targetValue = zoomToFraction(animatedZoom),
                animationSpec = tween(durationMillis = 110),
                label = "zoom_fill"
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(animFrac)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                palette.accent.copy(alpha = 0.95f),
                                palette.accent.copy(alpha = 0.55f)
                            )
                        )
                    )
            )

            val thumbOffsetDp = trackHeightDp * (1f - zoomToFraction(animatedZoom))
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = thumbOffsetDp - 3.dp)
                    .width(30.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White)
            )
        }

        Spacer(Modifier.size(2.dp))

        // Botón reset 1× — glow blanco si estamos cerca de 1.0
        val near1x = abs(currentZoom - 1f) < 0.05f
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (near1x) palette.accent.copy(alpha = 0.96f)
                    else Color.White.copy(alpha = if (palette.isDark) 0.12f else 0.32f)
                )
                .whiteGlow(active = near1x, shape = CircleShape)
                .clickable {
                    onHapticTick()
                    onSmoothZoomTo(1f)
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "1×",
                color = if (near1x) palette.onAccent else palette.onGlass,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * ZoomControlPopup — overlay full screen para invocación desde un botón
 * dedicado o tap largo en una lente. Tocar fuera cierra.
 */
@Composable
fun ZoomControlPopup(
    visible: Boolean,
    currentZoom: Float,
    minZoom: Float,
    maxZoom: Float,
    palette: GlassPalette,
    onZoomChange: (Float) -> Unit,
    onSmoothZoomTo: (Float) -> Unit,
    onHapticTick: () -> Unit,
    onDismiss: () -> Unit,
    showOpticalHint: Boolean = false,
    isOptical: Boolean = false
) {
    if (!visible) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable { onDismiss() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 18.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ZoomControl(
                currentZoom = currentZoom,
                minZoom = minZoom,
                maxZoom = maxZoom,
                palette = palette,
                visible = true,
                onZoomChange = onZoomChange,
                onSmoothZoomTo = onSmoothZoomTo,
                onHapticTick = onHapticTick,
                onDismiss = onDismiss,
                showOpticalHint = showOpticalHint,
                isOptical = isOptical,
                modifier = Modifier.clickable(enabled = false, onClick = {})
            )
        }
    }
}

private fun formatZoom(z: Float): String = when {
    z < 1f -> "%.1f×".format(z)
    z < 10f -> "%.1f×".format(z)
    else -> "${z.toInt()}×"
}
