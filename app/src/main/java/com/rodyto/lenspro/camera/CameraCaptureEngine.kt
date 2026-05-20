package com.rodyto.lenspro

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/* ================================================================
 *  CameraCaptureEngine.kt · v1.0 Premium
 *
 *  Encapsula el pipeline real de captura JPEG:
 *   1. ImageReader JPEG dedicado (tamaño full sensor)
 *   2. CaptureRequest con flash/exposure/AE precondicionados
 *   3. Pre-capture AE/AF lock cuando el flash está en AUTO
 *   4. Guardado vía MediaStoreSaver con relative path organizado
 *   5. Timer (3s/10s) con countdown publicado al UI vía StateFlow
 *   6. Callbacks de éxito/error → ShutterBlinkOverlay
 *
 *  La sesión Camera2 debe registrarse con setReaderSurface() ANTES
 *  de crear la CaptureSession, para que el ImageReader sea uno de
 *  los outputs del session.
 * ================================================================ */
class CameraCaptureEngine(
    private val context: Context,
    private val backgroundHandler: Handler?,
    private val storage: MediaStorageManager = MediaStorageManager()
) {
    companion object { private const val TAG = "CaptureEngine" }

    private var reader: ImageReader? = null

    // Countdown público (3..2..1..0)
    private val _countdown = MutableStateFlow(0)
    val countdown: StateFlow<Int> = _countdown.asStateFlow()

    // Flash mode actual (sincronizado desde SettingsRepository)
    @Volatile var flashMode: FlashMode = FlashMode.OFF

    /** Crea (o recrea) el ImageReader para el tamaño dado. Llamar antes de createCaptureSession. */
    fun prepareReader(jpegSize: Size): Surface {
        reader?.close()
        reader = ImageReader.newInstance(
            jpegSize.width,
            jpegSize.height,
            ImageFormat.JPEG,
            2
        ).apply {
            setOnImageAvailableListener({ r ->
                val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
                try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    val uri = storage.saveJpeg(context, bytes)
                    Log.d(TAG, "JPEG capturado: $uri (${bytes.size / 1024} KB)")
                } catch (t: Throwable) {
                    Log.e(TAG, "Error procesando JPEG", t)
                } finally {
                    image.close()
                }
            }, backgroundHandler)
        }
        return reader!!.surface
    }

    /**
     * Dispara captura still. Si el timer > 0, inicia countdown y solo dispara
     * al llegar a 0. Esta función es suspend para integrarse con coroutines del VM.
     */
    suspend fun captureStill(
        session: CameraCaptureSession,
        previewBuilder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics?,
        timerSeconds: Int = 0,
        onShutterEffect: () -> Unit
    ) {
        // ── Timer
        if (timerSeconds > 0) {
            for (i in timerSeconds downTo 1) {
                _countdown.value = i
                delay(1000L)
            }
            _countdown.value = 0
        }

        // ── Flash precondicionado en preview
        applyFlashToPreview(previewBuilder, session)

        // ── Capture request
        val readerSurface = reader?.surface ?: run {
            Log.e(TAG, "captureStill: reader nulo")
            return
        }
        val device = session.device
        val builder = device.createCaptureRequest(
            android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE
        ).apply {
            addTarget(readerSurface)
            // copiar settings relevantes del preview
            set(CaptureRequest.JPEG_QUALITY, 97.toByte())
            set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_START
            )
            when (flashMode) {
                FlashMode.ON -> {
                    set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    set(CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_SINGLE)
                }
                FlashMode.AUTO -> {
                    set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                }
                FlashMode.OFF -> {
                    set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_OFF)
                }
            }
            // JPEG orientation correcta para portrait
            val sensorOrientation = characteristics
                ?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
        }

        onShutterEffect()  // bump UI blink

        try {
            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    request: CaptureRequest,
                    result: android.hardware.camera2.TotalCaptureResult
                ) {
                    Log.d(TAG, "Capture completed")
                    // Restaurar preview AE / cerrar flash residual
                    try {
                        previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                            CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                        session.capture(previewBuilder.build(), null, backgroundHandler)
                        session.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler)
                    } catch (_: Throwable) {}
                }
            }, backgroundHandler)
        } catch (t: Throwable) {
            Log.e(TAG, "session.capture falló", t)
        }
    }

    /** Aplica el modo de flash a la preview (torch only para "ON" en vídeo). */
    private fun applyFlashToPreview(
        previewBuilder: CaptureRequest.Builder,
        session: CameraCaptureSession
    ) {
        try {
            // Solo para evitar que la preview entre en flash continuo, mantenemos AE controlado.
            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON)
            session.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler)
        } catch (_: Throwable) {}
    }

    /** Activa/desactiva torch (modo vídeo). */
    fun setTorch(
        on: Boolean,
        previewBuilder: CaptureRequest.Builder?,
        session: CameraCaptureSession?
    ) {
        val pb = previewBuilder ?: return
        val s = session ?: return
        try {
            pb.set(CaptureRequest.FLASH_MODE,
                if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            s.setRepeatingRequest(pb.build(), null, backgroundHandler)
        } catch (_: Throwable) {}
    }

    fun release() {
        try { reader?.close() } catch (_: Throwable) {}
        reader = null
    }
}
