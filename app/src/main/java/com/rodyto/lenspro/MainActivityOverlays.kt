package com.rodyto.lenspro

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/* ================================================================
 *  Rodyto Lens Pro · MainActivityOverlays.kt · v3.8 Pro
 *
 *  NOVEDADES v3.8 (sobre v3.6):
 *   • Recibe `previewBounds: Rect?` desde RodytoLensApp y se lo pasa
 *     a HorizonLevelOverlay para que el nivel artificial REALMENTE
 *     se dibuje sobre el área del preview (fix bug B-04).
 *   • Observa `viewModel.shutterBlinkKey` y lo pasa como triggerKey
 *     a ShutterBlinkOverlay para que la animación de oscurecimiento
 *     se dispare en cada captura (fix bug B-05).
 *
 *  Inalterado v3.6:
 *   • ActionChipBar wiring (flash, HDR, RAW, timer, sonido, aspect).
 *   • Histograma top-right si está activado.
 * ================================================================ */

@Composable
fun SettingsOverlayLayer(
    viewModel: CameraControlViewModel,
    palette: GlassPalette,
    repo: SettingsRepository,
    previewBounds: Rect? = null   // v3.8 — fix B-04
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── Estados reactivos persistidos ───────────────────────────
    val flashStr    by repo.flashMode.collectAsStateWithLifecycle(initialValue = "OFF")
    val hdrOn       by repo.hdrEnabled.collectAsStateWithLifecycle(initialValue = false)
    val rawOn       by repo.rawCapture.collectAsStateWithLifecycle(initialValue = false)
    val timerSec    by repo.timerSeconds.collectAsStateWithLifecycle(initialValue = 0)
    val soundOn     by repo.shutterSound.collectAsStateWithLifecycle(initialValue = true)
    val aspectStr   by repo.manualAspect.collectAsStateWithLifecycle(initialValue = null)
    val histOn      by repo.histogramEnabled.collectAsStateWithLifecycle(initialValue = false)
    val horizonOn   by repo.horizonEnabled.collectAsStateWithLifecycle(initialValue = false)

    // ── Estados del HAL / VM ────────────────────────────────────
    val histBins        by viewModel.histogramBins.collectAsStateWithLifecycle()
    // v3.8 — fix B-05: observar el contador de blink expuesto por el VM.
    val shutterBlinkKey by viewModel.shutterBlinkKey.collectAsStateWithLifecycle()

    val flashMode = remember(flashStr) { repo.flashFromString(flashStr) }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Chips superiores (encima del preview) ───────────────
        ActionChipBar(
            palette = palette,
            flashMode = flashMode,
            onToggleFlash = {
                val next = when (flashMode) {
                    FlashMode.OFF -> FlashMode.AUTO
                    FlashMode.AUTO -> FlashMode.ON
                    FlashMode.ON -> FlashMode.OFF
                }
                scope.launch { repo.setFlashMode(repo.flashToString(next)) }
            },
            hdrOn = hdrOn,
            onToggleHdr = { scope.launch { repo.setHdr(!hdrOn) } },
            timerSec = timerSec,
            onCycleTimer = {
                val next = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
                scope.launch { repo.setTimer(next) }
            },
            soundOn = soundOn,
            onToggleSound = { scope.launch { repo.setSound(!soundOn) } },
            aspectLabel = aspectStr ?: "Auto",
            onCycleAspect = {
                val next = when (aspectStr) {
                    null -> "3:4"; "3:4" -> "9:16"; "9:16" -> "1:1"
                    "1:1" -> "FULL"; else -> null
                }
                scope.launch { repo.setManualAspect(next) }
                // Reflejamos en el VM el aspect ratio efectivo.
                val ratio = repo.aspectFromLabel(next)?.ratio ?: (3f / 4f)
                viewModel.setPreviewAspectRatio(if (ratio <= 0f) 9f / 16f else ratio)
            },
            onOpenMore = { /* Hook para quick-panel; reservado */ },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp, start = 14.dp, end = 14.dp),
            rawOn = rawOn,
            onToggleRaw = { scope.launch { repo.setRaw(!rawOn) } },
            onOpenSettings = {
                context.startActivity(Intent(context, SettingsActivity::class.java))
            }
        )

        // ── Histograma top-right si está activado ───────────────
        if (histOn) {
            HistogramView(
                bins = histBins,
                palette = palette,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 96.dp, end = 14.dp)
            )
        }

        // ── Horizon Level (nivel artificial) ────────────────────
        // FIX v3.8 (bug B-04): previewBounds ya no es null literal sino que
        // viene del estado compartido en RodytoLensApp. Cuando horizonOn=true
        // Y previewBounds!=null, el nivel SE DIBUJA realmente.
        HorizonLevelOverlay(
            enabled = horizonOn,
            previewBounds = previewBounds,
            palette = palette,
            modifier = Modifier.fillMaxSize()
        )

        // ── Shutter blink (reacciona al contador del VM) ────────
        // FIX v3.8 (bug B-05): triggerKey ahora es el contador dinámico
        // expuesto por CameraControlViewModelState._shutterBlinkKey. Cada
        // takePhoto() lo incrementa y la animación se reproduce.
        ShutterBlinkOverlay(triggerKey = shutterBlinkKey)
    }
}
