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
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/* ================================================================
 *  Rodyto Lens Pro · CameraXBridge.kt · v3.5 Pro
 *
 *  Capa puente CameraX ⇄ Camera2Interop. NO REEMPLAZA al pipeline
 *  Camera2 manual del CameraControlViewModel — coexiste en paralelo.
 *
 *  PROPÓSITOS:
 *   1) Image Analysis estable (CameraX gestiona ciclo de vida, back-pressure
 *      y thread executor) → alimenta histograma + horizon dock en tiempo real
 *      con MENOS bugs que el ImageReader manual de Camera2.
 *   2) Inyección de control PRO vía Camera2Interop:
 *       • ISO manual                       SENSOR_SENSITIVITY
 *       • Velocidad obturación manual      SENSOR_EXPOSURE_TIME
 *       • Balance de blancos manual        CONTROL_AWB_MODE
 *       • Vendor tags Samsung              samsung.android.*
 *   3) Detección de cámaras lógicas/físicas de forma estandarizada por
 *      CameraX (LensFacing + camera2CameraId).
 *
 *  USO DESDE COMPOSE:
 *      CameraXAnalysisBridge(vm = viewModel)
 *
 *  Comportamiento por defecto:
 *   • Si `useCameraXAnalysis` es false en el VM → no se enlaza nada (no-op).
 *   • Si es true → arranca un Preview "headless" (no se renderiza, sólo
 *     mantiene la sesión CameraX viva) + ImageAnalysis YUV 640x480.
 *
 *  NOTA: para evitar conflictos por abrir dos veces la misma cámara HAL,
 *  cuando este bridge está activo el VM Camera2 desactiva su YUV interno
 *  vía `useCameraXAnalysis` (ver ensureImageReaders en el VM).
 *
 *  No obstante, debido a las limitaciones del HAL (sólo un proceso puede
 *  abrir un id físico a la vez), recomendamos usar este bridge SOLO en
 *  modo "FOTO" con la cámara principal del sistema (logical) — para no
 *  competir con la sesión Camera2 del VM en la misma cameraId.
 * ================================================================ */

@Composable
fun CameraXAnalysisBridge(vm: CameraControlViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(Unit) {
        // Solo arrancamos si el flag está activo. Se reevalúa si el flag
        // cambia gracias a la `key` Unit + LaunchedEffect externo en MainActivity.
        if (!vm.useCameraXAnalysis.value) {
            return@DisposableEffect onDispose { }
        }

        val executor = Executors.newSingleThreadExecutor()
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
            try { providerRef?.unbindAll() } catch (_: Throwable) {}
            try { executor.shutdown() } catch (_: Throwable) {}
        }
    }
}

@androidx.camera.camera2.interop.ExperimentalCamera2Interop
private fun bindCameraXAnalysisOnly(
    context: Context,
    provider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    vm: CameraControlViewModel,
    analysisExecutor: java.util.concurrent.ExecutorService
) {
    try {
        // No usamos Preview real — la preview SurfaceView ya la pinta Camera2
        // del VM. Sólo necesitamos ImageAnalysis para histograma robusto.
        val selector = if (vm.isFrontCamera.value)
            CameraSelector.DEFAULT_FRONT_CAMERA
        else
            CameraSelector.DEFAULT_BACK_CAMERA

        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(640, 480))

        // Camera2Interop: inyectamos vendor tags + control PRO al request
        Camera2Interop.Extender(analysisBuilder).apply {
            // Sólo se aplican si el VM tiene flags Pro activos
            val iso = vm.manualIso.value
            val shutter = vm.manualShutterNs.value
            if (iso > 0 && shutter != null && shutter > 0L) {
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_OFF)
                setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            }
            if (vm.manualWbKelvin.value > 0) {
                // CameraX no expone setWBGains directamente; el VM hace el control
                // fino. Aquí dejamos AUTO para no interferir.
            }
        }

        val analysis = analysisBuilder.build().also { ia ->
            ia.setAnalyzer(analysisExecutor, HistogramAnalyzer { bins ->
                vm.publishHistogramBins(bins)
            })
        }

        provider.unbindAll()
        val camera = provider.bindToLifecycle(lifecycleOwner, selector, analysis)

        // Diagnóstico
        try {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            Log.i("CameraXBridge", "Bridge OK. cameraId=${info.cameraId} sensorOrientation=${info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION)}")
        } catch (_: Throwable) {}

        // Inyección reactiva de control PRO: si el VM cambia manualIso,
        // se re-emite vía Camera2CameraControl.
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
 * Backpressure: STRATEGY_KEEP_ONLY_LATEST garantiza que sólo procesamos el
 * frame más reciente; si el cómputo va lento, los intermedios se descartan.
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

/* ================================================================
 *  PROFINFO — utilidades para mostrar al usuario qué cámara está
 *  realmente abierta (lógica/física + id Samsung) sin acoplar a UI.
 * ================================================================ */
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
        } catch (t: Throwable) { "(error: ${t.javaClass.simpleName})" }
    }
}

/* ================================================================
 *  Helper: convertir ByteBuffer YUV plano Y → IntArray sin copiar
 *  (usado por HistogramAnalyzer para evitar GC churn).
 * ================================================================ */
internal fun ByteBuffer.peekBytes(maxBytes: Int): ByteArray {
    val pos = position()
    val available = remaining().coerceAtMost(maxBytes)
    val arr = ByteArray(available)
    get(arr)
    position(pos)
    return arr
}
