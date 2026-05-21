package com.rodyto.lenspro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import com.rodyto.lenspro.settings.SettingsRepository
import com.rodyto.lenspro.ui.theme.GlassPalette

/* ================================================================
 *  MainActivityOverlays.kt · v5.0
 *
 *  Slot reservado para overlays adicionales que NO viven dentro del
 *  recuadro focal del preview (ej. focus peaking pantalla completa,
 *  panel pro inferior, etc.). Hoy es un Box vacío — actúa como punto
 *  de extensión documentado.
 *
 *  FIX BUG-K2: ya no se invoca desde MainActivityCore (la antigua
 *  invocación huérfana se eliminó). Se mantiene el archivo porque
 *  SettingsActivity y futuros features pueden poblarlo sin tocar
 *  CameraPreview.kt.
 * ================================================================ */
@Composable
fun SettingsOverlayLayer(
    @Suppress("UNUSED_PARAMETER") viewModel: CameraControlViewModel,
    @Suppress("UNUSED_PARAMETER") palette: GlassPalette,
    @Suppress("UNUSED_PARAMETER") repo: SettingsRepository,
    @Suppress("UNUSED_PARAMETER") previewBounds: Rect? = null
) {
    // Reservado para overlays futuros (focus peaking, RGB scope, etc.)
    Box(modifier = Modifier.fillMaxSize())
}
