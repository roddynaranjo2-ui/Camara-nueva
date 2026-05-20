package com.rodyto.lenspro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect

/* ================================================================
 *  MainActivityOverlays.kt · v4.0 (rediseño minimal)
 *
 *  En el rediseño Liquid Glass iOS 26 no hay overlays superpuestos
 *  sobre el preview (histograma, horizon, etc.). Mantenemos el
 *  composable público SettingsOverlayLayer como un no-op para
 *  preservar compatibilidad si alguien lo invoca, aunque la nueva
 *  composición principal usa LiquidGlassUiLayer (en MainActivityHelpers).
 * ================================================================ */
@Composable
fun SettingsOverlayLayer(
    viewModel: CameraControlViewModel,
    palette: GlassPalette,
    repo: SettingsRepository,
    previewBounds: Rect? = null
) {
    // Intencionalmente vacío en v4.0 — la UI ahora vive en LiquidGlassUiLayer.
    Box(modifier = Modifier.fillMaxSize())
}
