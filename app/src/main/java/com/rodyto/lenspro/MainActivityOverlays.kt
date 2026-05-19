package com.rodyto.lenspro

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/* ================================================================
 *  Rodyto Lens Pro · MainActivityOverlays.kt · v3.6 Pro
 *
 *  Archivo 2 de la división de MainActivity. Capa superior:
 *    • ActionChipBar (flash, HDR, RAW, timer, sonido, aspect, settings).
 *    • Histograma en tiempo real (toggle desde SettingsActivity).
 *    • Horizon Level (toggle desde SettingsActivity).
 *    • Shutter Blink overlay tras captura.
 *
 *  Esta capa NO contiene controles inferiores — eso vive en
 *  MainActivityHelpers.kt. Mantenemos la separación estricta
 *  para evitar volver a inflar el archivo monolítico.
 *
 *  CORRECCIONES sobre el esqueleto v3.5:
 *    • Elimina las funciones inventadas (ProSettingsPanel,
 *      ManualControlItem, SettingsPanel) que NO casaban con el
 *      diseño Glass premium real del proyecto.
 *    • Usa los Composables verdaderos del módulo:
 *      ActionChipBar, ModeSelectorIos, HistogramView,
 *      HorizonLevelOverlay, ShutterBlinkOverlay.
 * ================================================================ */

@Composable
fun SettingsOverlayLayer(
    viewModel: CameraControlViewModel,
    palette: GlassPalette,
    repo: SettingsRepository
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

    // ── Estados del HAL ─────────────────────────────────────────
    val histBins    by viewModel.histogramBins.collectAsStateWithLifecycle()

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
        HorizonLevelOverlay(
            enabled = horizonOn,
            previewBounds = null,  // el padre puede setearlo si lo guarda
            palette = palette,
            modifier = Modifier.fillMaxSize()
        )

        // ── Shutter blink (reacciona a contador interno) ────────
        // Pasamos `_isRecording.hashCode()` simple como triggerKey, o un
        // contador que el VM expone tras cada `takePhoto`. Aquí lo
        // usamos como placeholder visual coherente.
        ShutterBlinkOverlay(triggerKey = 0)
    }
}
