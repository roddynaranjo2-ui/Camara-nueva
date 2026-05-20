package com.rodyto.lenspro.ui.overlays

import com.rodyto.lenspro.CameraControlViewModel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/* ================================================================
 *  PreviewOverlays.kt · v1.0 Premium
 *
 *  Overlays funcionales que se renderizan ENCIMA del preview focal:
 *   • GridOverlay         — Cuadrícula 3×3 estilo iOS (líneas 0.5dp)
 *   • TimerCountdownOverlay — Cuenta regresiva del timer (3..2..1)
 * ================================================================ */

@Composable
fun GridOverlay(viewModel: CameraControlViewModel) {
    val gridOn by viewModel.gridEnabled.collectAsStateWithLifecycle()
    AnimatedVisibility(
        visible = gridOn,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeW = 0.6.dp.toPx()
            val color = Color.White.copy(alpha = 0.35f)
            // Verticales: 1/3 y 2/3
            val x1 = size.width / 3f
            val x2 = size.width * 2f / 3f
            drawLine(color, Offset(x1, 0f), Offset(x1, size.height),
                strokeWidth = strokeW, cap = StrokeCap.Round)
            drawLine(color, Offset(x2, 0f), Offset(x2, size.height),
                strokeWidth = strokeW, cap = StrokeCap.Round)
            // Horizontales
            val y1 = size.height / 3f
            val y2 = size.height * 2f / 3f
            drawLine(color, Offset(0f, y1), Offset(size.width, y1),
                strokeWidth = strokeW, cap = StrokeCap.Round)
            drawLine(color, Offset(0f, y2), Offset(size.width, y2),
                strokeWidth = strokeW, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun TimerCountdownOverlay(viewModel: CameraControlViewModel) {
    val count by viewModel.activeCountdown.collectAsStateWithLifecycle()
    val visible = count > 0
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.6f,
        label = "timer_scale"
    )
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(128.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = count.toString(),
                    color = Color.White,
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
