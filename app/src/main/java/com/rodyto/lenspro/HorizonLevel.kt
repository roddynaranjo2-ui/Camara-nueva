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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * HorizonLevel — horizonte artificial.
 *
 * Lee el acelerómetro vía SensorManager. Si la inclinación está dentro de
 * ±2° muestra la línea verde (nivel perfecto); fuera de eso, amarilla.
 * No registra listener si el sensor no existe (defensive).
 */
@Composable
fun HorizonLevelOverlay(
    enabled: Boolean,
    previewBounds: Rect?,
    palette: GlassPalette,
    modifier: Modifier = Modifier
) {
    if (!enabled || previewBounds == null) return
    val context = LocalContext.current
    var rollDeg by remember { mutableFloatStateOf(0f) }

    DisposableEffect(enabled) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gx = event.values[0]
                val gy = event.values[1]
                // roll en deg respecto al eje vertical del device portrait
                val angle = Math.toDegrees(kotlin.math.atan2(gx.toDouble(), gy.toDouble())).toFloat()
                rollDeg = -angle
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (accel != null) {
            sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            runCatching { sm?.unregisterListener(listener) }
        }
    }

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
            // Puntos de referencia fijos
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
