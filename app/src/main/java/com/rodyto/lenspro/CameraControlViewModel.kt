package com.rodyto.lenspro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import kotlin.math.max

enum class VideoResolution(val label: String, val width: Int, val height: Int) {
    HD("HD",   1280,  720),
    FHD("FHD", 1920, 1080),
    UHD("4K",  3840, 2160);
}

enum class VideoFps(val label: String, val value: Int) {
    FPS30("30", 30),
    FPS60("60", 60);
}

class CameraControlViewModel : ViewModel() {

    companion object {
        private const val TAG = "RodytoLensPro"
        @Volatile private var nativeLibLoaded = false
        init {
            try {
                System.loadLibrary("rodytolenspro")
                nativeLibLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Librería nativa no disponible (modo solo Kotlin)", e)
            }
        }
    }

    /**
     * [FIX #14] Antes este método estaba declarado pero jamás invocado (toda la
     * librería C++ era código muerto). Ahora se invoca de forma SEGURA dentro de
     * cacheCameraIds() para complementar la detección de IDs físicos en dispositivos
     * con multi-cámara lógica. Se conserva el método nativo intacto.
     */
    @Suppress("unused")
    private external fun getPhysicalCameraIdsNative(): Array<String>

    private fun safeNativePhysicalIds(): Array<String> = try {
        if (nativeLibLoaded) getPhysicalCameraIdsNative() else emptyArray()
    } catch (e: Throwable) {
        Log.w(TAG, "Fallo getPhysicalCameraIdsNative", e); emptyArray()
    }

    // ---------------- Estado UI expuesto ----------------
    // [FIX #3] Identificadores unificados a "0.5x" / "1x" / "3x" en TODO el proyecto.
    private val _currentLens = MutableStateFlow("1x")
    val currentLens: StateFlow<String> = _currentLens.asStateFlow()

    private val _cameraMode = MutableStateFlow("FOTO")
    val cameraMode: StateFlow<String> = _cameraMode.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _focusLocked = MutableStateFlow(false)
    val focusLocked: StateFlow<Boolean> = _focusLocked.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _flashEnabled = MutableStateFlow(false)
    val flashEnabled: StateFlow<Boolean> = _flashEnabled.asStateFlow()

    private val _exposureLevel = MutableStateFlow(0)
    val exposureLevel: StateFlow<Int> = _exposureLevel.asStateFlow()

    private val _lastPhotoUri = MutableStateFlow<Uri?>(null)
    val lastPhotoUri: StateFlow<Uri?> = _lastPhotoUri.asStateFlow()

    private val _zoomLevel = MutableStateFlow(1f)
    val zoomLevel: StateFlow<Float> = _zoomLevel.asStateFlow()

    private val _hdrEnabled = MutableStateFlow(false)
    val hdrEnabled: StateFlow<Boolean> = _hdrEnabled.asStateFlow()

    private val _gridEnabled = MutableStateFlow(false)
    val gridEnabled: StateFlow<Boolean> = _gridEnabled.asStateFlow()

    private val _timerSeconds = MutableStateFlow(0)
    val timerSeconds: StateFlow<Int> = _timerSeconds.asStateFlow()

    private val _shutterSoundEnabled = MutableStateFlow(true)
    val shutterSoundEnabled: StateFlow<Boolean> = _shutterSoundEnabled.asStateFlow()

    private val _videoResolution = MutableStateFlow(VideoResolution.FHD)
    val videoResolution: StateFlow<VideoResolution> = _videoResolution.asStateFlow()

    private val _videoFps = MutableStateFlow(VideoFps.FPS30)
    val videoFps: StateFlow<VideoFps> = _videoFps.asStateFlow()

    private val _previewAspectRatio = MutableStateFlow(3f / 4f)
    val previewAspectRatio: StateFlow<Float> = _previewAspectRatio.asStateFlow()

    // ---------------- Recursos Camera2 ----------------
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null
    private var previewSurface: Surface? = null
    private var cameraManager: CameraManager? = null
    private var currentCameraId: String = "0"
    private var videoUri: Uri? = null
    private var videoFd: ParcelFileDescriptor? = null

    private var backMainId: String? = null
    private var backUltraWideId: String? = null
    private var backTeleId: String? = null
    private var frontMainId: String? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val cameraMutex = Mutex()
    @Volatile private var isCameraActive = false
    @Volatile private var isStartingCamera = false

    init { startBackgroundThread() }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("RodytoLensProCamera").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join(500) } catch (_: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null
    }

    fun isCameraRunning(): Boolean = isCameraActive

    fun toggleFlash() { _flashEnabled.value = !_flashEnabled.value; updateRepeatingRequest() }
    fun setCameraMode(mode: String) { if (_isRecording.value) return; _cameraMode.value = mode }
    fun toggleFocusLock() { _focusLocked.value = !_focusLocked.value; updateRepeatingRequest() }

    fun toggleHdr() { _hdrEnabled.value = !_hdrEnabled.value; updateRepeatingRequest() }
    fun toggleGrid() { _gridEnabled.value = !_gridEnabled.value }
    fun cycleTimer() {
        _timerSeconds.value = when (_timerSeconds.value) { 0 -> 3; 3 -> 10; else -> 0 }
    }
    fun toggleShutterSound() { _shutterSoundEnabled.value = !_shutterSoundEnabled.value }

    fun setVideoResolution(r: VideoResolution) { if (!_isRecording.value) _videoResolution.value = r }
    fun setVideoFps(f: VideoFps) { if (!_isRecording.value) _videoFps.value = f }

    fun toggleFrontCamera(context: Context) {
        if (_isRecording.value) return
        _isFrontCamera.value = !_isFrontCamera.value
        _flashEnabled.value = false
        _focusLocked.value = false
        _currentLens.value = "1x"
        _zoomLevel.value = 1f
        val surface = previewSurface ?: return
        viewModelScope.launch(Dispatchers.IO) {
            cameraMutex.withLock {
                closeCameraInternal()
                openCameraLocked(context, surface, _currentLens.value)
            }
        }
    }

    fun switchLens(context: Context, lens: String) {
        if (_isRecording.value) return
        _currentLens.value = lens

        val teleAvailable = backTeleId != null
        _zoomLevel.value = when (lens) {
            "0.5x" -> 1f
            "1x"   -> 1f
            "3x"   -> if (teleAvailable) 1f else 3f
            else   -> 1f
        }

        val needsCameraSwap = !_isFrontCamera.value && targetCameraIdFor(lens) != currentCameraId
        if (needsCameraSwap) {
            val surface = previewSurface ?: return
            viewModelScope.launch(Dispatchers.IO) {
                cameraMutex.withLock {
                    closeCameraInternal()
                    openCameraLocked(context, surface, lens)
                }
            }
        } else {
            updateRepeatingRequest()
        }
    }

    // [FIX #11] Reformateado para legibilidad
    private fun targetCameraIdFor(lens: String): String = when {
        _isFrontCamera.value -> frontMainId ?: currentCameraId
        lens == "0.5x"       -> backUltraWideId ?: backMainId ?: currentCameraId
        lens == "3x"         -> backTeleId ?: backMainId ?: currentCameraId
        else                 -> backMainId ?: currentCameraId
    }

    fun setExposure(level: Int) {
        val range = getExposureRange() ?: return
        val clamped = level.coerceIn(range.lower, range.upper)
        if (clamped == _exposureLevel.value) return
        _exposureLevel.value = clamped
        updateRepeatingRequest()
    }

    fun getExposureRange(): Range<Int>? = try {
        cameraManager?.getCameraCharacteristics(currentCameraId)
            ?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    } catch (e: Exception) { Log.e(TAG, "Error getExposureRange", e); null }

    fun tapToFocus(x: Float, y: Float, screenWidth: Int, screenHeight: Int) {
        if (_focusLocked.value) return
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        val manager = cameraManager ?: return
        try {
            val ch = manager.getCameraCharacteristics(currentCameraId)
            val arr = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val sensorX = (y / screenHeight * arr.width()).toInt().coerceIn(0, arr.width() - 1)
            val sensorY = ((1 - x / screenWidth) * arr.height()).toInt().coerceIn(0, arr.height() - 1)
            val halfSize = max(arr.width(), arr.height()) / 20
            val region = MeteringRectangle(
                (sensorX - halfSize).coerceAtLeast(0),
                (sensorY - halfSize).coerceAtLeast(0),
                halfSize * 2, halfSize * 2,
                MeteringRectangle.METERING_WEIGHT_MAX - 1
            )
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            applyBaseSettings(builder)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), null, backgroundHandler)
            updateRepeatingRequest()
        } catch (e: Exception) { Log.e(TAG, "Error tapToFocus", e) }
    }

    fun startCameraSession(context: Context, surface: Surface, lens: String) {
        viewModelScope.launch(Dispatchers.IO) {
            cameraMutex.withLock {
                if (isStartingCamera || isCameraActive) return@withLock
                openCameraLocked(context, surface, lens)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraLocked(context: Context, surface: Surface, lens: String) {
        if (!surface.isValid) { Log.w(TAG, "Surface no válido"); return }
        isStartingCamera = true
        previewSurface = surface
        try {
            val mgr = context.applicationContext
                .getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager = mgr
            cacheCameraIds(mgr)
            currentCameraId = targetCameraIdFor(lens)

            try {
                val ch = mgr.getCameraCharacteristics(currentCameraId)
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val best = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                    ?.maxByOrNull { it.width.toLong() * it.height }
                if (best != null) {
                    _previewAspectRatio.value = best.height.toFloat() / best.width.toFloat()
                }
            } catch (_: Exception) {}

            mgr.openCamera(currentCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    isCameraActive = true; isStartingCamera = false
                    createPreviewSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    isCameraActive = false; isStartingCamera = false
                    try { camera.close() } catch (_: Exception) {}
                    cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Error abriendo cámara: $error")
                    isCameraActive = false; isStartingCamera = false
                    try { camera.close() } catch (_: Exception) {}
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            isCameraActive = false; isStartingCamera = false
            Log.e(TAG, "Error iniciando cámara", e)
        }
    }

    /**
     * [FIX #12 + #14] Cachea IDs de cámara. Eliminadas variables sin uso
     * (ultraFocal, teleFocal) y se integra la búsqueda nativa de IDs físicos
     * como complemento cuando esté disponible.
     */
    private fun cacheCameraIds(manager: CameraManager) {
        if (backMainId != null && frontMainId != null && (backUltraWideId != null || backTeleId != null))
            return
        try {
            val ids = manager.cameraIdList

            backMainId = ids.firstOrNull {
                manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            frontMainId = ids.firstOrNull {
                manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }

            val mainFocal = backMainId?.let {
                manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull()
            } ?: 0f

            var ultraId: String? = null
            var teleId: String? = null

            val combinedIds = (ids.toSet() + safeNativePhysicalIds().toSet()).toList()

            combinedIds.forEach { id ->
                runCatching {
                    val ch = manager.getCameraCharacteristics(id)
                    if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK)
                        return@runCatching
                    val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: return@runCatching
                    val minF = focals.minOrNull() ?: return@runCatching
                    val maxF = focals.maxOrNull() ?: return@runCatching
                    if (id != backMainId) {
                        if (minF < mainFocal) ultraId = id
                        if (maxF > mainFocal + 5f) teleId = id
                    }
                }
            }
            backUltraWideId = ultraId
            backTeleId      = teleId
            Log.d(TAG, "IDs → main=$backMainId, ultra=$backUltraWideId, tele=$backTeleId, front=$frontMainId")
        } catch (e: Exception) { Log.e(TAG, "cacheCameraIds error", e) }
    }

    fun hasTelephoto(): Boolean = backTeleId != null

    private fun createPreviewSession() {
        val camera = cameraDevice ?: return
        val surface = previewSurface ?: return
        if (!surface.isValid) return
        try {
            val ch = cameraManager?.getCameraCharacteristics(currentCameraId)
            val map = ch?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSize = map?.getOutputSizes(ImageFormat.JPEG)
                ?.maxByOrNull { it.width.toLong() * it.height } ?: Size(1920, 1080)

            imageReader?.close()
            imageReader = ImageReader.newInstance(
                jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2
            )

            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(surface, imageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        updateRepeatingRequest()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Falló CaptureSession")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) { Log.e(TAG, "Error createPreviewSession", e) }
    }

    private fun applyBaseSettings(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            if (_flashEnabled.value && !_isFrontCamera.value)
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
            else
                CaptureRequest.CONTROL_AE_MODE_ON
        )
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, _exposureLevel.value)
        builder.set(CaptureRequest.CONTROL_AE_LOCK, _focusLocked.value)
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, _focusLocked.value)

        if (_hdrEnabled.value) {
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
        }
        applyZoom(builder)
    }

    private fun updateRepeatingRequest() {
        try {
            val camera = cameraDevice ?: return
            val session = captureSession ?: return
            val surface = previewSurface ?: return
            if (!surface.isValid) return
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            applyBaseSettings(builder)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) { Log.e(TAG, "Error updateRepeatingRequest", e) }
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        try {
            val ch = cameraManager?.getCameraCharacteristics(currentCameraId) ?: return
            val rect = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val zoom = _zoomLevel.value.coerceAtLeast(1f)
            if (zoom == 1f) return
            val cropW = (rect.width() / zoom).toInt()
            val cropH = (rect.height() / zoom).toInt()
            val cropX = (rect.width() - cropW) / 2
            val cropY = (rect.height() - cropH) / 2
            builder.set(
                CaptureRequest.SCALER_CROP_REGION,
                Rect(cropX, cropY, cropX + cropW, cropY + cropH)
            )
        } catch (e: Exception) { Log.e(TAG, "Error applyZoom", e) }
    }

    fun takePicture(storage: MediaStorageManager, context: Context) {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        val appContext = context.applicationContext

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer: ByteBuffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val uri = storage.saveJpeg(appContext, bytes)
                if (uri != null) _lastPhotoUri.value = uri
            } catch (e: Exception) { Log.e(TAG, "Error procesando imagen", e) }
            finally { image.close() }
        }, backgroundHandler)

        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            applyBaseSettings(builder)
            builder.set(
                CaptureRequest.JPEG_ORIENTATION,
                if (_isFrontCamera.value) 270 else 90
            )
            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) { updateRepeatingRequest() }
            }, backgroundHandler)
        } catch (e: Exception) { Log.e(TAG, "Error takePicture", e) }
    }

    @SuppressLint("MissingPermission")
    fun startVideoRecording(context: Context, storage: MediaStorageManager) {
        if (_isRecording.value) return
        val camera = cameraDevice ?: return
        val surface = previewSurface ?: return
        if (!surface.isValid) return
        val appContext = context.applicationContext
        try {
            captureSession?.close(); captureSession = null

            val uri = storage.createVideoUri(appContext) ?: return
            val fd  = storage.openVideoFd(appContext, uri) ?: return
            videoUri = uri; videoFd = fd

            val res  = _videoResolution.value
            val fps  = _videoFps.value.value
            val rate = when (res) {
                VideoResolution.HD  ->  6_000_000
                VideoResolution.FHD -> 12_000_000
                VideoResolution.UHD -> 40_000_000
            }

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(appContext)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(fd.fileDescriptor)
                setVideoEncodingBitRate(rate)
                setVideoFrameRate(fps)
                setVideoSize(res.width, res.height)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOrientationHint(if (_isFrontCamera.value) 270 else 90)
                prepare()
            }

            val recorderSurface = mediaRecorder!!.surface

            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(surface, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            builder.addTarget(surface)
                            builder.addTarget(recorderSurface)
                            applyBaseSettings(builder)
                            builder.set(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                Range(fps, fps)
                            )
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                            mediaRecorder?.start()
                            _isRecording.value = true
                        } catch (e: Exception) {
                            Log.e(TAG, "Error iniciando grabación", e); cleanupRecorder()
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Falló sesión de grabación"); cleanupRecorder()
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) { Log.e(TAG, "Error startVideoRecording", e); cleanupRecorder() }
    }

    fun stopVideoRecording(context: Context, storage: MediaStorageManager) {
        if (!_isRecording.value) return
        val appContext = context.applicationContext
        try {
            try { mediaRecorder?.stop() } catch (e: Exception) { Log.e(TAG, "stop()", e) }
            cleanupRecorder()
            videoUri?.let { storage.finalizeVideo(appContext, it) }
            videoUri = null
            _isRecording.value = false
            createPreviewSession()
        } catch (e: Exception) { Log.e(TAG, "Error stopVideoRecording", e) }
    }

    private fun cleanupRecorder() {
        try { mediaRecorder?.reset() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        try { videoFd?.close() } catch (_: Exception) {}
        videoFd = null
    }

    fun closeCamera() {
        viewModelScope.launch(Dispatchers.IO) {
            cameraMutex.withLock { closeCameraInternal() }
        }
    }

    private fun closeCameraInternal() {
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        cleanupRecorder()
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        isCameraActive = false; isStartingCamera = false
    }

    override fun onCleared() {
        super.onCleared()
        closeCameraInternal()
        stopBackgroundThread()
    }
}
