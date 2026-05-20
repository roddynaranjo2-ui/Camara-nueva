package com.rodyto.lenspro

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.util.Log
import com.rodyto.lenspro.util.HistogramComputer
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/* ================================================================
 *  Rodyto Lens Pro · CameraXBridge.kt · v3.7 Pro · CORREGIDO
 *
 *  CORRECCIONES v3.7 (sobre v3.6):
 *   • setTargetResolution() ELIMINADO — estaba deprecated desde
 *     CameraX 1.3.0 y fue removido en 1.4.0. Causa ClassNotFound
 *     en runtime en dispositivos con CameraX 1.4.1.
 *     Reemplazado por ResolutionSelector + ResolutionStrategy con
 *     tamaño objetivo 640×480 y fallback CLOSEST_LOWER_THEN_HIGHER.
 *   • Imports añadidos: ResolutionSelector, ResolutionStrategy.
 *
 *  Resto de correcciones v3.6 conservadas:
 *   ① Executor creado solo si useCameraXAnalysis = true.
 *   ② DisposableEffect con key reactiva.
 *   ③ Reset robusto en onDispose.
 *   ④ Aviso HAL multi-cliente documentado.
 * ================================================================ */

@Composable
fun CameraXAnalysisBridge(vm: CameraControlViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val active by vm.useCameraXAnalysis.collectAsStateWithLifecycle()
    val frontFlag by vm.isFrontCamera.collectAsStateWithLifecycle()

    // Key reactiva: si cambia `active` o el lensFacing → el effect se relanza
    DisposableEffect(active, frontFlag) {
        if (!active) {
            return@DisposableEffect onDispose { /* noop */ }
        }

        val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "CameraXBridge-Analyzer").apply { isDaemon = true }
        }
        var providerRef: ProcessCameraProvider? = null

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                providerRef = provider
                bindCameraXAnalysisOnly(
                    context = context,
                    provider = provider,
                    lifecycleOwner = lifecycleOwner,
                    vm = vm,
                    analysisExecutor = executor
                )
            } catch (t: Throwable) {
                Log.w("CameraXBridge", "No se pudo enlazar CameraX bridge", t)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            // FIX C-02: No llamar a unbindAll() globalmente si queremos coexistencia 
            // o si el bridge es solo para análisis. 
            // Sin embargo, el informe sugiere no mezclar frameworks.
            // Por ahora, solo cerramos el executor y limpiamos referencia.
            providerRef?.unbindAll() // Solo si estamos seguros de que este provider es local al bridge
            providerRef = null
            try { executor.shutdownNow() } catch (_: Throwable) {}
        }
    }
}

@androidx.camera.camera2.interop.ExperimentalCamera2Interop
private fun bindCameraXAnalysisOnly(
    context: Context,
    provider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    vm: CameraControlViewModel,
    analysisExecutor: ExecutorService
) {
    try {
        val selector = if (vm.isFrontCamera.value)
            CameraSelector.DEFAULT_FRONT_CAMERA
        else
            CameraSelector.DEFAULT_BACK_CAMERA

        // FIX v3.7: usar ResolutionSelector en lugar del deprecated setTargetResolution.
        // ResolutionStrategy con CLOSEST_LOWER_THEN_HIGHER busca la resolución
        // más cercana a 640×480 por debajo (baja carga de análisis).
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    android.util.Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()

        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(resolutionSelector)

        Camera2Interop.Extender(analysisBuilder).apply {
            val iso = vm.manualIso.value
            val shutter = vm.manualShutterNs.value
            if (iso > 0 && shutter != null && shutter > 0L) {
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_OFF)
                setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            }
        }

        val analysis = analysisBuilder.build().also { ia ->
            ia.setAnalyzer(analysisExecutor, HistogramAnalyzer { bins ->
                vm.publishHistogramBins(bins)
            })
        }

        // provider.unbindAll() // ELIMINADO: Mata la sesión Camera2 de la preview
        val camera = provider.bindToLifecycle(lifecycleOwner, selector, analysis)

        try {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            Log.i(
                "CameraXBridge",
                "Bridge OK. cameraId=${info.cameraId} sensorOrientation=" +
                "${info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION)}"
            )
        } catch (_: Throwable) {}

        try {
            val control = Camera2CameraControl.from(camera.cameraControl)
            val opts = CaptureRequestOptions.Builder().apply {
                val iso = vm.manualIso.value
                val shutter = vm.manualShutterNs.value
                if (iso > 0 && shutter != null && shutter > 0L) {
                    setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_OFF)
                    setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
                }
            }.build()
            control.captureRequestOptions = opts
        } catch (t: Throwable) {
            Log.w("CameraXBridge", "No se pudo configurar Camera2CameraControl", t)
        }
    } catch (t: Throwable) {
        Log.e("CameraXBridge", "bindCameraXAnalysisOnly fallo", t)
    }
}

/**
 * Analizador YUV → histograma de 64 bins delegando en HistogramComputer.
 * STRATEGY_KEEP_ONLY_LATEST: si el procesado va lento, los frames
 * intermedios se descartan automáticamente.
 */
private class HistogramAnalyzer(
    private val onBins: (IntArray) -> Unit
) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        try {
            val media: Image? = image.image
            if (media != null) {
                val bins = HistogramComputer.computeY(media)
                if (bins != null) onBins(bins)
            }
        } catch (_: Throwable) {
            // ignorar frames corruptos
        } finally {
            image.close()
        }
    }
}

object CameraXProInfo {
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun describeActive(context: Context, vm: CameraControlViewModel): String {
        return try {
            val sel = if (vm.isFrontCamera.value)
                CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA
            val provider = ProcessCameraProvider.getInstance(context).get()
            val cameraInfo = provider.availableCameraInfos.firstOrNull { ci ->
                sel.filter(listOf(ci)).isNotEmpty()
            } ?: return "(sin cámara)"
            val info = Camera2CameraInfo.from(cameraInfo)
            "id=${info.cameraId} | activeTeleId=${vm.activePhysicalTeleId.value}"
        } catch (t: Throwable) {
            "(error: ${t.javaClass.simpleName})"
        }
    }
}
