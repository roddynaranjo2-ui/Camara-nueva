package com.rodyto.lenspro

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
 *  Rodyto Lens Pro · CameraXBridge.kt · v3.6 Pro · OPTIMIZADO
 *
 *  CORRECCIONES v3.6 (sobre v3.5):
 *   ① El executor YA NO se crea si el flag useCameraXAnalysis está
 *      a false → cero consumo de threads/CPU en el caso por defecto.
 *   ② DisposableEffect con key reactiva — si el usuario cambia el
 *      flag en runtime, el bridge se reactiva/desactiva correctamente.
 *   ③ Reset robusto en onDispose: unbindAll + shutdown del executor
 *      + null en provider. Sin fugas si la activity se recrea.
 *   ④ Documentación reforzada del CONFLICTO HAL: en muchos dispositivos
 *      (especialmente Samsung Exynos) no se puede abrir el mismo
 *      cameraId desde dos clientes simultáneos. Si el usuario activa
 *      useCameraXAnalysis y observa preview en negro o crash al
 *      cambiar lente, debe desactivar este flag.
 *
 *  ⚠️ AVISO PRODUCCIÓN:
 *      Este bridge SOLO debería estar activo cuando el dispositivo
 *      tiene un HAL3.4+ que permite multi-cliente. Para el grueso de
 *      dispositivos Samsung pre-Snapdragon 8 Gen 2 el flag debe
 *      permanecer en OFF.
 * ================================================================ */

@Composable
fun CameraXAnalysisBridge(vm: CameraControlViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // FIX v3.6: collectAsStateWithLifecycle nos permite reactivar el bridge
    // sin necesidad de re-componer toda la jerarquía superior.
    val active by vm.useCameraXAnalysis.collectAsStateWithLifecycle()
    val frontFlag by vm.isFrontCamera.collectAsStateWithLifecycle()

    // Key reactiva: si cambia `active` o el lensFacing → el effect se relanza
    DisposableEffect(active, frontFlag) {
        if (!active) {
            // FIX v3.6: salida temprana SIN crear executor ni futures
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
            // FIX v3.6: reset proper — unbind primero, shutdown después, null al final.
            try { providerRef?.unbindAll() } catch (_: Throwable) {}
            providerRef = null
            try {
                executor.shutdownNow()
            } catch (_: Throwable) {}
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

        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(640, 480))

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

        provider.unbindAll()
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
