package com.rodyto.rodyto_lens_pro

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Archivo 3: Componentes de Control y Utilidades.
 * Aquí están los botones de acción y elementos visuales pequeños.
 */

@Composable
fun MainControlsLayer(viewModel: CameraControlViewModelOps) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize().padding(bottom = 30.dp)) {
        // Slider de Zoom Lateral o Inferior
        ZoomSlider(
            currentZoom = uiState.zoomLevel,
            onZoomChange = { viewModel.setZoom(it) },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp)
        )

        // Botón de Disparo Principal
        ShutterButton(
            isRecording = uiState.isRecording,
            mode = uiState.currentMode,
            onClick = {
                if (uiState.currentMode == CameraMode.VIDEO) {
                    viewModel.toggleRecording()
                } else {
                    viewModel.takePhoto()
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun ShutterButton(
    isRecording: Boolean,
    mode: CameraMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = if (mode == CameraMode.VIDEO || mode == CameraMode.PRO_VIDEO) Color.Red else Color.White
    
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(80.dp)
            .border(4.dp, Color.White.copy(alpha = 0.5f), CircleShape)
            .padding(4.dp)
    ) {
        Surface(
            shape = if (isRecording) RoundedCornerShape(8.dp) else CircleShape,
            color = color,
            modifier = Modifier.fillMaxSize()
        ) {}
    }
}

@Composable
fun ZoomSlider(currentZoom: Float, onZoomChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    // Implementación del slider vertical/horizontal para el zoom
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${"%.1f".format(currentZoom)}x", color = Color.White)
        Slider(
            value = currentZoom,
            onValueChange = onZoomChange,
            valueRange = 1f..10f,
            modifier = Modifier.height(200.dp) // Si es vertical
        )
    }
}

// --- UTILIDADES ---

fun formatTimestamp(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(minutes, secs)
}
