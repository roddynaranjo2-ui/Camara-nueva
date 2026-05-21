package com.rodyto.lenspro.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import com.rodyto.lenspro.CameraMode
import com.rodyto.lenspro.CameraSessionState
import com.rodyto.lenspro.FlashMode
import com.rodyto.lenspro.SamsungVendorTags
import com.rodyto.lenspro.VideoResolution
import com.rodyto.lenspro.tuning.CameraTuning
import com.rodyto.lenspro.ui.CameraUiStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * CameraSessionController · v2.1 Premium
 *
 * Cambios v2.1 (sobre v2.0):
 *  • FIX 8: startRecording acepta resolution/fps/allowHevc del VM.
 *  • FIX 8.b: cierre sincronizado de la session previa antes de crear
 *    la nueva con [preview, recorderSurface] — evita estados intermedios.
 *  • FIX 9: pickJpegSize ahora consulta el sensor activo (no asume 12MP).
 *
 *  Mantiene TODOS los fixes v2.0 (M1/M5/M7/A2/C1/C2/E1/E5).
 */
class CameraSessionController(
    private val context: Context,
    private val stateHolder: CameraUiStateHolder
) {
    companion object { private const val TAG = "CameraSession" }

    private var backgroundThread: HandlerThread? = null
    var backgroundHandler: Handler? = null
        private set

    var cameraDevice: CameraDevice? = null
        private set
    var captureSession: CameraCaptureSession? = null
        private set
    var previewRequestBuilder: CaptureRequest.Builder? = null
        private set

    private var pendingSurface: Surface? = null
    private var currentCharacteristics: CameraCharacteristics? = null
    private var currentCameraId: String? = null
    private var currentLongEdgeHint: Int = 0
    private var currentMode: CameraMode = CameraMode.PHOTO
    private var currentAspect: Float = 3f / 4f
    private var currentProVendorTags = true

    private val captureEngine = CameraCaptureEngine(context)
    val captureEngineCountdown: StateFlow<Int> get() = captureEngine.countdown

    private val videoRecorder = VideoRecordingController(context)

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            Log.d(TAG, "CameraDevice abierto: ${camera.id}")
            pendingSurface?.let { createPreviewSession(it) }
        }
        override fun onDisconnected(camera: CameraDevice) { closeCamera() }
        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "CameraDevice error: $error")
            stateHolder.setSessionState(CameraSessionState.ERROR)
            closeCamera()
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread?.isAlive == true && backgroundHandler != null) return
        val t = HandlerThread("CameraBG").apply {
            priority = android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
            start()
        }
        backgroundThread = t
        val looper = t.looper ?: run {
            Log.e(TAG, "HandlerThread looper nulo, abortando background thread")
            t.quitSafely()
            backgroundThread = null
            return
        }
        val h = Handler(looper)
        backgroundHandler = h
        captureEngine.setBackgroundHandler(h)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join(500L) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        backgroundThread = null
        backgroundHandler = null
    }

    @SuppressLint("MissingPermission")
    fun openCamera(
        cameraId: String,
        previewSurface: Surface,
        characteristics: CameraCharacteristics?,
        previewAspect: Float,
        longEdgeHint: Int,
        proVendorTagsEnabled: Boolean,
        cameraMode: CameraMode
    ) {
        this.pendingSurface = previewSurface
        this.currentCharacteristics = characteristics
        this.currentCameraId = cameraId
        this.currentLongEdgeHint = longEdgeHint
        this.currentMode = cameraMode
        this.currentAspect = previewAspect
        this.currentProVendorTags = proVendorTagsEnabled

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permiso CAMERA no concedido")
            stateHolder.setSessionState(CameraSessionState.ERROR)
            return
        }

        startBackgroundThread()
        stateHolder.setSessionState(CameraSessionState.OPENING)

        val jpegSize = pickJpegSize(characteristics)
        runCatching { captureEngine.prepareReader(jpegSize) }
            .onFailure { Log.w(TAG, "prepareReader falló", it) }

        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo cámara", e)
            stateHolder.setSessionState(CameraSessionState.ERROR)
        }
    }

    /** FIX 9: prioriza el tamaño máximo del HAL del sensor activo. */
    private fun pickJpegSize(chars: CameraCharacteristics?): Size {
        val fallback = Size(4000, 3000)   // S21 FE main sensor real
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return fallback
        val jpeg = map.getOutputSizes(ImageFormat.JPEG) ?: return fallback
        return jpeg.maxByOrNull { it.width.toLong() * it.height } ?: fallback
    }

    private fun createPreviewSession(surface: Surface) {
        val device = cameraDevice ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            applyDefaultControlsToBuilder(builder)
            previewRequestBuilder = builder

            val outputs = buildList {
                add(surface)
                captureEngine.readerSurface?.let { add(it) }
            }

            device.createCaptureSession(
                outputs,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                            stateHolder.setSessionState(CameraSessionState.PREVIEWING)
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "setRepeatingRequest", e)
                            stateHolder.setSessionState(CameraSessionState.ERROR)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "createCaptureSession.onConfigureFailed")
                        stateHolder.setSessionState(CameraSessionState.ERROR)
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createCaptureSession", e)
            stateHolder.setSessionState(CameraSessionState.ERROR)
        }
    }

    private fun applyDefaultControlsToBuilder(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        val afMode = if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.PRO_VIDEO)
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        else
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        b.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        CameraTuning.applyImageQuality(b, mode = if (currentMode == CameraMode.VIDEO) "VIDEO" else "PHOTO")

        if (currentProVendorTags && SamsungVendorTags.isSamsungHal(currentCharacteristics)) {
            val tagMode = if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.PRO_VIDEO) "VIDEO" else "PHOTO"
            SamsungVendorTags.applyBase(b, tagMode, isRecording = false)
        }
    }

    fun closeCamera() {
        val s = stateHolder.sessionState.value
        if (s == CameraSessionState.IDLE || s == CameraSessionState.CLOSING) return
        stateHolder.setSessionState(CameraSessionState.CLOSING)

        videoRecorder.abortIfActive()

        try { captureSession?.stopRepeating() } catch (_: Throwable) {}
        try { captureSession?.abortCaptures() } catch (_: Throwable) {}
        captureSession?.close()
        captureSession = null
        previewRequestBuilder = null

        cameraDevice?.close()
        cameraDevice = null
        pendingSurface = null

        captureEngine.release()
        stateHolder.setSessionState(CameraSessionState.IDLE)
    }

    fun release() {
        closeCamera()
        videoRecorder.release()
        stopBackgroundThread()
    }

    fun takePhoto(
        timerSeconds: Int,
        flashMode: FlashMode,
        isFrontCamera: Boolean,
        onShutterEffect: () -> Unit,
        scope: CoroutineScope
    ) {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        captureEngine.flashMode = flashMode
        scope.launch {
            captureEngine.captureStill(
                session = session,
                previewBuilder = builder,
                characteristics = currentCharacteristics,
                timerSeconds = timerSeconds,
                isFrontCamera = isFrontCamera,
                onShutterEffect = onShutterEffect
            )
        }
    }

    /** FIX 19: ahora respeta resolución/fps/codec elegidos por el usuario. */
    fun startRecording(
        isFrontCamera: Boolean,
        resolution: VideoResolution = VideoResolution.FHD,
        fps: Int = 30,
        allowHevc: Boolean = false
    ): Boolean {
        val device = cameraDevice ?: return false
        val previewSurface = pendingSurface ?: return false
        val chars = currentCharacteristics
        val recorderSurface = videoRecorder.prepareRecorder(
            characteristics = chars,
            isFrontCamera = isFrontCamera,
            resolution = resolution,
            fps = fps,
            allowHevc = allowHevc
        ) ?: return false

        try {
            // FIX 8.b: cierre sincronizado de la session previa
            try { captureSession?.stopRepeating() } catch (_: Throwable) {}
            try { captureSession?.abortCaptures() } catch (_: Throwable) {}
            captureSession?.close()
            captureSession = null

            val recordBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            recordBuilder.addTarget(previewSurface)
            recordBuilder.addTarget(recorderSurface)
            recordBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            recordBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            if (currentProVendorTags && SamsungVendorTags.isSamsungHal(chars)) {
                SamsungVendorTags.applyBase(recordBuilder, "VIDEO", isRecording = true)
            }
            previewRequestBuilder = recordBuilder

            device.createCaptureSession(
                listOf(previewSurface, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(recordBuilder.build(), null, backgroundHandler)
                            videoRecorder.start()
                            stateHolder.setSessionState(CameraSessionState.RECORDING)
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "record setRepeating fail", e)
                            stateHolder.setSessionState(CameraSessionState.ERROR)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "record session configure fail")
                        videoRecorder.abortIfActive()
                        stateHolder.setSessionState(CameraSessionState.ERROR)
                    }
                },
                backgroundHandler
            )
            return true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording fallo", e)
            videoRecorder.abortIfActive()
            return false
        }
    }

    fun stopRecording() {
        videoRecorder.stop()
        val surface = pendingSurface ?: return
        try { captureSession?.stopRepeating() } catch (_: Throwable) {}
        captureSession?.close()
        captureSession = null
        createPreviewSession(surface)
    }

    fun applyManualParams(
        manualIso: Int,
        manualShutterNs: Long?,
        cameraMode: CameraMode,
        isRecording: Boolean,
        proVendorTagsEnabled: Boolean
    ) {
        currentMode = cameraMode
        currentProVendorTags = proVendorTagsEnabled
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        try {
            if (manualIso > 0 && manualShutterNs != null && manualShutterNs > 0L) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualShutterNs)
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            val afMode = if (cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.PRO_VIDEO)
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            else
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
            if (proVendorTagsEnabled && SamsungVendorTags.isSamsungHal(currentCharacteristics)) {
                val tagMode = if (cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.PRO_VIDEO) "VIDEO" else "PHOTO"
                SamsungVendorTags.applyBase(builder, tagMode, isRecording)
            }
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "applyManualParams", e)
        }
    }

    fun applyZoom(zoom: Float, chars: CameraCharacteristics) {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (range != null) {
                    val clamped = zoom.coerceIn(range.lower, range.upper)
                    builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clamped)
                    if (currentProVendorTags) SamsungVendorTags.applyZoomRatio(builder, clamped)
                    session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                    return
                }
            }
            val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            val z = zoom.coerceIn(1f, max(1f, maxZoom))
            val cropW = (active.width() / z).roundToInt()
            val cropH = (active.height() / z).roundToInt()
            val cx = active.centerX(); val cy = active.centerY()
            val crop = android.graphics.Rect(
                cx - cropW / 2, cy - cropH / 2,
                cx + cropW / 2, cy + cropH / 2
            )
            builder.set(CaptureRequest.SCALER_CROP_REGION, crop)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "applyZoom", e)
        }
    }
}
