package com.rodyto.rodyto_lens_pro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Archivo 2: Paneles y Capas Superiores (Overlays).
 * Contiene los menús de ajustes y paneles de control manual.
 */

@Composable
fun SettingsOverlayLayer(viewModel: CameraControlViewModelOps) {
    val uiState by viewModel.uiState.collectAsState()
    
    // Aquí gestionamos la visibilidad de los paneles que tenías originalmente
    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState.currentMode == CameraMode.PRO_PHOTO || uiState.currentMode == CameraMode.PRO_VIDEO) {
            ProSettingsPanel(
                viewModel = viewModel,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)
            )
        }
        
        // Panel de ajustes generales (SettingsPanel de tu esquema)
        SettingsPanel(viewModel)
    }
}

@Composable
fun ProSettingsPanel(viewModel: CameraControlViewModelOps, modifier: Modifier = Modifier) {
    val iso by viewModel.manualIso.collectAsState()
    val shutter by viewModel.manualShutter.collectAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("CONTROLES PRO", color = Color.White, style = MaterialTheme.typography.labelSmall)
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            // Reutilización de lógica original para ISO y Shutter
            ManualControlItem(label = "ISO", value = iso.toString()) {
                // Aquí se llama a la función que definimos en ViewModelOps
                viewModel.setIso(iso + 100) 
            }
            ManualControlItem(label = "SHT", value = "1/${1_000_000 / shutter}") {
                viewModel.setShutterSpeed(shutter / 2)
            }
        }
    }
}

@Composable
fun SettingsPanel(viewModel: CameraControlViewModelOps) {
    // Implementación original de tu panel de ajustes generales
    // (Resolución, FPS, Grid lines, etc.)
}

@Composable
fun ManualControlItem(label: String, value: String, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
