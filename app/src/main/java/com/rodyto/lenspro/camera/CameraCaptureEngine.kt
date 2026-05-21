package com.rodyto.lenspro.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.Surface
import com.rodyto.lenspro.FlashMode
import com.rodyto.lenspro.capture.MediaStorageManager
import com.rodyto.lenspro.tuning.CameraTuning
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CameraCaptureEngine · v2.0 Premium
 *
 * Cambios v2.0:
 *  • FIX BUG-A1: nada de reader!!.surface. La función prepareReader retorna
 *    el Surface o null; readerSurface expone el último Surface preparado.
 *  • FIX BUG-E7: applyFlashToPreview ELIMINADO — el preview repeating es
 *    responsabilidad del CameraSessionController, no del engine.
 *  • FIX BUG-M7: prepareReader es idempotente (recicla si el tamaño coincide).
 *  • captureStill recibe isFrontCamera para JPEG_ORIENTATION correcto.
 */
class CameraCaptureEngine(
    private val context: Context,
    private var backgroundHandler: Handler? = null,
    private val storage: MediaStorageManager = MediaStorageManager()
) {
    companion object { private const val TAG = "CaptureEngine" }

    fun setBackgroundHandler(h: Handler?) { backgroundHandler = h }

    private var reader: ImageReader? = null
    private var readerSize: Size? = null

    /** Surface del ImageReader. Null si aún no se preparó. */
    val readerSurface: Surface?
        get() = reader?.surface?.takeIf { it.isValid }

    // Countdown público (3..2..1..0) — el VM lo redirige al StateHolder.
    private val _countdown = MutableStateFlow(0)
    val countdown: StateFlow<Int> = _countdown.asStateFlow()

    @Volatile var flashMode: FlashMode = FlashMode.OFF

    /** BUG-M7: crea o recicla el ImageReader. Retorna el Surface o null. */
    fun prepareReader(jpegSize: Size): Surface? {
        if (readerSize == jpegSize && reader != null) return reader?.surface
        try { reader?.close() } catch (_: Throwable) {}
        return try {
            val r = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            r.setOnImageAvailableListener({ src ->
                val image = src.acquireNextImage() ?: return@setOnImageAvailableListener
                try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    val uri = storage.saveJpeg(context, bytes)
                    Log.d(TAG, "JPEG ${jpegSize.width}x${jpegSize.height} → $uri (${bytes.size/1024} KB)")
                } catch (t: Throwable) {
                    Log.e(TAG, "Error procesando JPEG", t)
                } finally { image.close() }
            }, backgroundHandler)
            reader = r
            readerSize = jpegSize
            r.surface
        } catch (t: Throwable) {
            Log.e(TAG, "prepareReader falló (${jpegSize.width}x${jpegSize.height})", t)
            reader = null
            readerSize = null
            null
        }
    }

    /**
     * Dispara captura still. Si el timer > 0, decrementa _countdown y solo
     * dispara al llegar a 0. La función es suspend para integrarse con el
     * scope del ViewModel (FIX BUG-C1/C2).
     */
    suspend fun captureStill(
        session: CameraCaptureSession,
        previewBuilder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics?,
        timerSeconds: Int = 0,
        isFrontCamera: Boolean = false,
        onShutterEffect: () -> Unit
    ) {
        // Timer
        if (timerSeconds > 0) {
            for (i in timerSeconds downTo 1) {
                _countdown.value = i
                delay(1000L)
            }
            _countdown.value = 0
        }

        // BUG-A1: validar reader
        val targetSurface = readerSurface ?: run {
            Log.e(TAG, "captureStill: reader no preparado, abortando")
            return
        }

        val device = session.device
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(targetSurface)
            // Heredar settings clave del preview para consistencia
            previewBuilder.get(CaptureRequest.CONTROL_AE_REGIONS)?.let { set(CaptureRequest.CONTROL_AE_REGIONS, it) }
            previewBuilder.get(CaptureRequest.CONTROL_AF_REGIONS)?.let { set(CaptureRequest.CONTROL_AF_REGIONS, it) }
            previewBuilder.get(CaptureRequest.SCALER_CROP_REGION)?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
            previewBuilder.get(CaptureRequest.CONTROL_ZOOM_RATIO)?.let { set(CaptureRequest.CONTROL_ZOOM_RATIO, it) }
            CameraTuning.applyJpegQuality(this, 97)
            CameraTuning.applyImageQuality(this, "PHOTO")
            set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            when (flashMode) {
                FlashMode.ON -> {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                }
                FlashMode.AUTO -> set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                FlashMode.OFF -> {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
            val sensorOri = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            CameraTuning.applyJpegOrientationDynamic(this, sensorOri, isFrontCamera, deviceRotationDeg = 0)
        }

        onShutterEffect()

        try {
            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) {
                    // Restaurar preview AF
                    try {
                        previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                        s.capture(previewBuilder.build(), null, backgroundHandler)
                        previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                        s.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler)
                    } catch (_: Throwable) {}
                }
            }, backgroundHandler)
        } catch (t: Throwable) {
            Log.e(TAG, "session.capture falló", t)
        }
    }

    /** Torch para modo vídeo. */
    fun setTorch(on: Boolean, previewBuilder: CaptureRequest.Builder?, session: CameraCaptureSession?) {
        val pb = previewBuilder ?: return
        val s = session ?: return
        try {
            pb.set(CaptureRequest.FLASH_MODE, if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            s.setRepeatingRequest(pb.build(), null, backgroundHandler)
        } catch (_: Throwable) {}
    }

    fun release() {
        try { reader?.close() } catch (_: Throwable) {}
        reader = null
        readerSize = null
        _countdown.value = 0
    }
}
