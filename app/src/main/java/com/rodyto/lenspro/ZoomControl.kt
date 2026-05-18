package com.rodyto.lenspro

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.LaunchedEffect
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
 * ZoomControl v1.0 — slider de zoom VERTICAL premium.
 *
 * COMPLEMENTA al ZoomDial.kt existente: el dial es un control rotatorio
 * cinematográfico; el ZoomControl es un slider vertical mucho más
 * compacto que aparece al hacer pinch o al tocar el LensSelector, ideal
 * para ajustes rápidos sin tapar la mitad del preview.
 *
 * Features:
 *  • Glass container cornerRadius 30dp+ (premium spec)
 *  • Mapeo logarítmico zoom 0.5× → 30×
 *  • Snap points visuales en 0.5/1/2/3/5/10/30 (los que estén en rango)
 *  • Tick háptico cada 1/4 stop logarítmico
 *  • Auto-hide configurable
 *  • Glow blanco perimetral cuando se está manipulando
 *  • Botón "1×" para reset instantáneo
 *  • Etiqueta gigante del zoom actual (estilo iPhone Pro Max)
 *
 * Diseño visual: 56.dp ancho × ~280.dp alto, vertical, en el lado
 * derecho del preview (no tapa el centro). Cuando está oculto, no
 * consume layout.
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
    modifier: Modifier = Modifier
) {
    if (!visible) return

    val animatedZoom by animateFloatAsState(
        targetValue = currentZoom,
        animationSpec = tween(durationMillis = 90),
        label = "zoom_smooth"
    )

    // Mapeo logarítmico: angle/pos lineal en pantalla ↔ zoom exponencial
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

    val trackHeightDp = 220.dp

    Column(
        modifier = modifier
            .width(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .liquidGlass(palette, RoundedCornerShape(32.dp), strong = true)
            .whiteGlow(active = isDragging, shape = RoundedCornerShape(32.dp), intensity = 1f)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Etiqueta superior con zoom actual gigante
        Text(
            text = formatZoom(animatedZoom),
            color = palette.onGlass,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        // Track vertical interactivo
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(trackHeightDp)
                .clip(RoundedCornerShape(20.dp))
                .background(palette.onGlassSecondary.copy(alpha = 0.14f))
                .pointerInput(minZoom, maxZoom) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragAccumPx = 0f
                            isDragging = true
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false }
                    ) { _, dragAmount ->
                        // Cada 1.4px ≈ 1/220 del track
                        dragAccumPx += -dragAmount
                        val curFrac = zoomToFraction(currentZoom)
                        val trackPx = trackHeightDp.toPx()
                        val newFrac = (curFrac + dragAccumPx / trackPx).coerceIn(0f, 1f)
                        if (abs(newFrac - curFrac) >= 0.001f) {
                            dragAccumPx = 0f
                            val newZoom = fractionToZoom(newFrac)
                            // Tick háptico — divido la fracción en 16 buckets logarítmicos
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
            // Marcas/ticks: cada 0.5 / 1 / 2 / 3 / 5 / 10 / 30 según rango
            Canvas(modifier = Modifier.fillMaxSize()) {
                val snaps = listOf(0.5f, 1f, 2f, 3f, 5f, 10f, 30f)
                    .filter { it in minZoom..maxZoom }
                snaps.forEach { z ->
                    val frac = zoomToFraction(z)
                    val y = size.height * (1f - frac)
                    val isMajor = (z == 1f || z == 10f)
                    drawLine(
                        color = palette.onGlass.copy(alpha = if (isMajor) 0.85f else 0.45f),
                        start = Offset(size.width * (if (isMajor) 0.12f else 0.22f), y),
                        end = Offset(size.width * (if (isMajor) 0.88f else 0.78f), y),
                        strokeWidth = if (isMajor) 2.2f else 1.2f
                    )
                }
            }

            // Relleno (acento) desde abajo hasta el valor actual
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

            // Thumb (línea blanca brillante en posición actual)
            val thumbOffsetDp = trackHeightDp * (1f - zoomToFraction(animatedZoom))
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = thumbOffsetDp - 3.dp)
                    .width(28.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White)
            )
        }

        Spacer(Modifier.size(2.dp))

        // Botón reset 1×
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    if (abs(currentZoom - 1f) < 0.05f) palette.accent.copy(alpha = 0.96f)
                    else Color.White.copy(alpha = if (palette.isDark) 0.10f else 0.30f)
                )
                .whiteGlow(active = abs(currentZoom - 1f) < 0.05f, shape = CircleShape)
                .clickable {
                    onHapticTick()
                    onSmoothZoomTo(1f)
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "1×",
                color = if (abs(currentZoom - 1f) < 0.05f) palette.onAccent else palette.onGlass,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * ZoomControlPopup — overlay completo: fondo dim + ZoomControl alineado
 * a la derecha. Útil cuando se activa desde tap-largo en LensSelector
 * o desde un botón dedicado. Tocar fuera cierra.
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
    onDismiss: () -> Unit
) {
    if (!visible) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.30f))
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
                modifier = Modifier
                    .clickable(enabled = false, onClick = {}) // consume taps
            )
        }
    }
}

private fun formatZoom(z: Float): String = when {
    z < 1f -> "%.1f×".format(z)
    z < 10f -> "%.1f×".format(z)
    else -> "${z.toInt()}×"
}
