package com.rodyto.lenspro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import com.rodyto.lenspro.settings.SettingsRepository
import com.rodyto.lenspro.ui.theme.GlassPalette

/* ================================================================
 *  MainActivityOverlays.kt · v4.0 Premium
 *  (Compatibilidad — los overlays activos están en CameraPreview.kt)
 * ================================================================ */
@Composable
fun SettingsOverlayLayer(
    viewModel: CameraControlViewModel,
    palette: GlassPalette,
    repo: SettingsRepository,
    previewBounds: Rect? = null
) {
    Box(modifier = Modifier.fillMaxSize())
}
