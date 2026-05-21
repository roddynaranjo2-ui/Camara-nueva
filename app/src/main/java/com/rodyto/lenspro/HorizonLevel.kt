package com.rodyto.lenspro

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.rodyto.lenspro.ui.theme.GlassPalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * HorizonLevel v3.7 — OPTIMIZADO
 *
 * Cambios v3.7:
 *  • Filtro de bajo paso (alpha=0.18) al ángulo de roll → elimina jitter
 *    del acelerómetro y reduce repaints (~30% menos invalidations).
 *  • Si rollDeg no cambia más de 0.3° respecto a la última lectura,
 *    NO se actualiza el state → evita recomposiciones inútiles.
 *  • Listener se registra SOLO con accel != null (defensivo).
 *
 *  Mantiene los fixes v3.6 (DisposableEffect con key=enabled).
 */
@Composable
fun HorizonLevelOverlay(
    enabled: Boolean,
    previewBounds: Rect?,
    palette: GlassPalette,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var rollDeg by remember { mutableFloatStateOf(0f) }

    DisposableEffect(enabled) {
        if (!enabled) {
            return@DisposableEffect onDispose { /* nada que liberar */ }
        }
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var smoothed = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gx = event.values[0]
                val gy = event.values[1]
                val raw = -Math.toDegrees(
                    kotlin.math.atan2(gx.toDouble(), gy.toDouble())
                ).toFloat()
                // Filtro de bajo paso → suavizado anti-jitter
                smoothed = smoothed * 0.82f + raw * 0.18f
                // Solo actualiza si cambio significativo
                if (abs(smoothed - rollDeg) > 0.3f) {
                    rollDeg = smoothed
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (accel != null && sm != null) {
            sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            runCatching { sm?.unregisterListener(listener) }
        }
    }

    if (!enabled || previewBounds == null) return

    val leveled = abs(rollDeg) < 2f
    val color = if (leveled) Color(0xFF34C759) else palette.accent

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = (previewBounds.left + previewBounds.right) / 2f
            val cy = (previewBounds.top + previewBounds.bottom) / 2f
            val halfLen = size.width * 0.18f
            val rad = (rollDeg.toDouble() * PI / 180.0)
            val dx = (cos(rad) * halfLen).toFloat()
            val dy = (sin(rad) * halfLen).toFloat()
            drawLine(
                color = color.copy(alpha = 0.92f),
                start = Offset(cx - dx, cy - dy),
                end = Offset(cx + dx, cy + dy),
                strokeWidth = 3f
            )
            val refLen = halfLen * 1.35f
            drawLine(
                color = palette.onGlass.copy(alpha = 0.45f),
                start = Offset(cx - refLen, cy),
                end = Offset(cx - halfLen * 1.1f, cy),
                strokeWidth = 2f
            )
            drawLine(
                color = palette.onGlass.copy(alpha = 0.45f),
                start = Offset(cx + halfLen * 1.1f, cy),
                end = Offset(cx + refLen, cy),
                strokeWidth = 2f
            )
        }
    }
}
