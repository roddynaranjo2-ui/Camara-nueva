package com.rodyto.lenspro.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rodyto.lenspro.FlashMode

/**
 * QuickSettingsIsland — Componente de ajustes rápidos en la parte superior.
 * Parte del refactor de división de archivos de la Fase 3.
 */
@Composable
fun QuickSettingsIsland(
    isVideoMode: Boolean,
    videoRes: String,
    videoFps: Int,
    flashMode: FlashMode,
    flashSupported: Boolean,
    timerSec: Int,
    gridOn: Boolean,
    onCycleResolution: () -> Unit,
    onCycleFps: () -> Unit,
    onCycleFlash: () -> Unit,
    onCycleTimer: () -> Unit,
    onToggleGrid: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Implementación simplificada para el refactor inicial
    // En una fase posterior se restaurará el diseño visual completo
    Row(
        modifier = modifier
            .statusBarsPadding()
            .padding(16.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Aquí irían los iconos y botones
    }
}
