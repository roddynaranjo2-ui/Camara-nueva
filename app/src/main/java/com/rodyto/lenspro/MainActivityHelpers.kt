package com.rodyto.lenspro

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/* ================================================================
 *  Rodyto Lens Pro · MainActivityHelpers.kt · v3.6 Pro
 *
 *  Archivo 3 de la división de MainActivity. Capa inferior:
 *    • LensSelectorRow (0.5x / 1x / 3x).
 *    • ModeSelectorIos (FOTO / VIDEO).
 *    • ShutterGlass (botón de disparo con swipe FOTO/VIDEO).
 *    • ZoomControlPopup (slider vertical bajo tap largo en tele).
 *
 *  CORRECCIONES sobre el esqueleto v3.5:
 *    • Eliminados los Composables fantasma:
 *        – ShutterButton (duplicaba ShutterButtonPro / ShutterGlass).
 *        – ZoomSlider    (duplicaba ZoomControl / ZoomDial).
 *      Mantenerlos generaba conflictos de UX y código muerto.
 *    • Háptica unificada usando View.performHapticFeedback().
 *    • Formato de timestamp preservado como utilidad genérica.
 * ================================================================ */

@Composable
fun MainControlsLayer(
    viewModel: CameraControlViewModel,
    palette: GlassPalette,
    repo: SettingsRepository
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // ── Estados reactivos ───────────────────────────────────────
    val cameraMode by viewModel.cameraMode.collectAsStateWithLifecycle()
    val zoom       by viewModel.zoomLevel.collectAsStateWithLifecycle()
    val recording  by viewModel.isRecording.collectAsStateWithLifecycle()
    val lensLabel  by repo.lastLens.collectAsStateWithLifecycle(initialValue = "1x")
    val hapticsOn  by repo.hapticsEnabled.collectAsStateWithLifecycle(initialValue = true)

    var showZoomPopup by remember { mutableStateOf(false) }

    val haptic: () -> Unit = remember(hapticsOn) {
        { if (hapticsOn) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Selector de lentes (justo encima del shutter) ──────
        LensSelectorRow(
            currentLens = lensLabel,
            palette = palette,
            onSelect = { lens ->
                haptic()
                scope.launch { repo.setLastLens(lens) }
            },
            availableLenses = listOf("0.5x", "1x", "3x"),
            telephotoIsOptical = true, // la clase final lo determina por HAL
            onLongPressLens = { showZoomPopup = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 180.dp)
        )

        // ── Modo Foto / Video ──────────────────────────────────
        ModeSelectorIos(
            mode = if (cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.PRO_VIDEO)
                "VIDEO" else "FOTO",
            palette = palette,
            onModeChange = { newLabel ->
                haptic()
                val newMode = when (newLabel) {
                    "VIDEO" -> CameraMode.VIDEO
                    else    -> CameraMode.PHOTO
                }
                viewModel.setCameraMode(newMode)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )

        // ── Botón de disparo principal (glass premium) ─────────
        ShutterGlass(
            isRecording = recording,
            mode = if (cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.PRO_VIDEO)
                "VIDEO" else "FOTO",
            onTap = {
                haptic()
                if (cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.PRO_VIDEO) {
                    viewModel.toggleRecording()
                } else {
                    viewModel.takePhoto()
                }
            },
            onSwipeToVideo  = { haptic(); viewModel.setCameraMode(CameraMode.VIDEO) },
            onSwipeToPhoto  = { haptic(); viewModel.setCameraMode(CameraMode.PHOTO) },
            onPressFeedback = haptic,
            palette = palette
        )

        // ── Popup vertical de zoom (visible con long-press) ────
        ZoomControlPopup(
            visible = showZoomPopup,
            currentZoom = zoom,
            minZoom = viewModel.getZoomMinValue(),
            maxZoom = viewModel.getZoomMaxValue(),
            palette = palette,
            onZoomChange   = { viewModel.setZoomContinuous(it) },
            onSmoothZoomTo = { viewModel.setZoomContinuous(it) },
            onHapticTick   = haptic,
            onDismiss      = { showZoomPopup = false }
        )
    }
}

/* ── Utilidad pública preservada de la versión anterior ────────── */
fun formatTimestamp(seconds: Long): String {
    val minutes = seconds / 60
    val secs    = seconds % 60
    return "%02d:%02d".format(minutes, secs)
}
