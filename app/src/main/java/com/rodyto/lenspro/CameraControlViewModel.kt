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
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class VideoResolution(val label: String, val width: Int, val height: Int) {
    HD("HD", 1280, 720),
    FHD("FHD", 1920, 1080),
    UHD("4K", 3840, 2160)
}

enum class VideoFps(val label: String, val value: Int) {
    FPS30("30", 30),
    FPS60("60", 60)
}

enum class PreviewAspect(val label: String, val ratio: Float) {
    RATIO_3_4("3:4", 3f / 4f),
    RATIO_9_16("9:16", 9f / 16f),
    RATIO_1_1("1:1", 1f),
    RATIO_FULL("FULL", 9f / 19.5f)
}

enum class FlashMode(val label: String) {
    OFF("Off"),
    AUTO("Auto"),
    ON("On")
}

class CameraControlViewModel : ViewModel() {

    companion object {
        private const val TAG = "RodytoLensPro"
        const val MAX_DIGITAL_ZOOM = 30f
        const val HYBRID_ZOOM_THRESHOLD = 10f
        const val MIN_ZOOM = 0.5f

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

    private data class CameraCandidate(
        val id: String, val facing: Int, val focal: Float,
        val isLogicalMultiCamera: Boolean, val sensorArea: Rect?
    )
    private data class CameraSelection(val cameraId: String, val opticalBaseZoom: Float)

    @Suppress("unused")
    private external fun getPhysicalCameraIdsNative(): Array<String>

    private fun safeNativePhysicalIds(): Array<String> = try {
        if (nativeLibLoaded) getPhysicalCameraIdsNative() else emptyArray()
    } catch (e: Throwable) {
        Log.w(TAG, "Fallo getPhysicalCameraIdsNative", e); emptyArray()
    }

    // ─── StateFlows existentes ────────────────────────────────────────────────
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

    private val _flashMode = MutableStateFlow(FlashMode.OFF)
    val flashMode: StateFlow<FlashMode> = _flashMode.asStateFlow()

    private val _exposureLevel = MutableStateFlow(0)
    val exposureLevel: StateFlow<Int> = _exposureLevel.asStateFlow()

    private val _lastPhotoUri = MutableStateFlow<Uri?>(null)
    val lastPhotoUri: StateFlow<Uri?> = _lastPhotoUri.asStateFlow()

    private val _zoomLevel = MutableStateFlow(1f)
    val zoomLevel: StateFlow<Float> = _zoomLevel.asStateFlow()

    private val _zoomMax = MutableStateFlow(10f)
    val zoomMax: StateFlow<Float> = _zoomMax.asStateFlow()

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

    private val _manualAspect = MutableStateFlow<PreviewAspect?>(null)
    val manualAspect: StateFlow<PreviewAspect?> = _manualAspect.asStateFlow()

    private val _previewAspectRatio = MutableStateFlow(3f / 4f)
    val previewAspectRatio: StateFlow<Float> = _previewAspectRatio.asStateFlow()

    private val _darkTheme = MutableStateFlow<Boolean?>(null)
    val darkTheme: StateFlow<Boolean?> = _darkTheme.asStateFlow()

    private val _accentStyle = MutableStateFlow(AccentStyle.ICE_BLUE)
    val accentStyle: StateFlow<AccentStyle> = _accentStyle.asStateFlow()

    private val _hapticsEnabled = MutableStateFlow(true)
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    private val _hevcEnabled = MutableStateFlow(false)
    val hevcEnabled: StateFlow<Boolean> = _hevcEnabled.asStateFlow()

    private val _manualFocus = MutableStateFlow<Float?>(null)
    val manualFocus: StateFlow<Float?> = _manualFocus.asStateFlow()

    private val _minFocusDistance = MutableStateFlow(0f)
    val minFocusDistance: StateFlow<Float> = _minFocusDistance.asStateFlow()

    // ─── NUEVOS StateFlows (v2.0) ─────────────────────────────────────────────
    private val _histogramEnabled = MutableStateFlow(false)
    val histogramEnabled: StateFlow<Boolean> = _histogramEnabled.asStateFlow()

    private val _horizonEnabled = MutableStateFlow(false)
    val horizonEnabled: StateFlow<Boolean> = _horizonEnabled.asStateFlow()

    private val _histogramBins = MutableStateFlow<IntArray?>(null)
    val histogramBins: StateFlow<IntArray?> = _histogramBins.asStateFlow()

    private val _rawCapture = MutableStateFlow(false)
    val rawCapture: StateFlow<Boolean> = _rawCapture.asStateFlow()

    private val _smoothZoomEnabled = MutableStateFlow(true)
    val smoothZoomEnabled: StateFlow<Boolean> = _smoothZoomEnabled.asStateFlow()

    private val _supports60fps = MutableStateFlow(true)
    val supports60fps: StateFlow<Boolean> = _supports60fps.asStateFlow()

    private val _iso = MutableStateFlow<Int?>(null)        // sensor metadata
    val iso: StateFlow<Int?> = _iso.asStateFlow()

    private val _shutterSpeedNs = MutableStateFlow<Long?>(null)
    val shutterSpeedNs: StateFlow<Long?> = _shutterSpeedNs.asStateFlow()

    // ─── Camera2 state ────────────────────────────────────────────────────────
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private val multiReader = MultiChannelImageReader()
    private var mediaRecorder: MediaRecorder? = null
    private var previewSurface: Surface? = null
    private var recordSurface: Surface? = null
    private var videoUri: Uri? = null
    private var videoPfd: ParcelFileDescriptor? = null
    private var currentCharacteristics: CameraCharacteristics? = null
    private var currentCameraId: String = ""
    private var sensorArea: Rect? = null
    private var sensorOrientation: Int = 90
    private var maxHwZoom: Float = 1f
    private var currentOpticalBaseZoom: Float = 1f
    private var supportedFpsRanges: Array<Range<Int>> = emptyArray()
    private var supportsVideoStabilization: Boolean = false

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private val sessionMutex = Mutex()

    @Volatile private var cameraRunning = false
    fun isCameraRunning(): Boolean = cameraRunning

    fun getZoomMaxValue(): Float = _zoomMax.value
    fun getZoomMinValue(): Float = MIN_ZOOM

    @Volatile private var previewSurfaceWidth: Int = 0
    @Volatile private var previewSurfaceHeight: Int = 0
    fun notifyPreviewSize(width: Int, height: Int) {
        previewSurfaceWidth = width; previewSurfaceHeight = height
    }

    private var smoothZoomJob: Job? = null

    // ─── Ciclo de vida ────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startCameraSession(context: Context, surface: Surface, lens: String) {
        previewSurface = surface
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock { startCameraSessionLocked(context, surface, lens) }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun startCameraSessionLocked(context: Context, surface: Surface, lens: String) {
        if (cameraRunning) return
        try {
            ensureThreads()
            cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val mgr = cameraManager!!
            val selection = pickCameraSelection(mgr, _isFrontCamera.value, lens)
            currentCameraId = selection.cameraId
            currentOpticalBaseZoom = selection.opticalBaseZoom
            currentCharacteristics = mgr.getCameraCharacteristics(selection.cameraId)
            sensorArea = currentCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            sensorOrientation = currentCharacteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            maxHwZoom = currentCharacteristics?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            _minFocusDistance.value = currentCharacteristics
                ?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            supportedFpsRanges = currentCharacteristics
                ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()

            val vstabModes = currentCharacteristics
                ?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
            supportsVideoStabilization = vstabModes.any {
                it == CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON
            }

            // ▼ Validar 60 fps en res actual
            _supports60fps.value = CameraTuning.supportsFpsAtResolution(
                currentCharacteristics, _videoResolution.value, 60
            )

            val hwBased = maxHwZoom * currentOpticalBaseZoom
            _zoomMax.value = min(max(hwBased, 10f), MAX_DIGITAL_ZOOM)

            safeNativePhysicalIds()

            val device: CameraDevice = suspendCancellableCoroutine { cont ->
                try {
                    mgr.openCamera(selection.cameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(device: CameraDevice) {
                            if (cont.isActive) cont.resume(device)
                        }
                        override fun onDisconnected(device: CameraDevice) {
                            device.close(); cameraRunning = false
                            if (cont.isActive) cont.cancel()
                        }
                        override fun onError(device: CameraDevice, error: Int) {
                            Log.e(TAG, "openCamera error=$error cameraId=${selection.cameraId}")
                            device.close(); cameraRunning = false
                            if (cont.isActive) cont.cancel()
                        }
                    }, cameraHandler)
                } catch (e: Exception) {
                    Log.e(TAG, "openCamera exception", e)
                    if (cont.isActive) cont.cancel(e)
                }
            }

            cameraDevice = device
            cameraRunning = true
            ensureImageReaders()
            createPreviewSessionSuspending(device)
        } catch (e: Exception) {
            Log.e(TAG, "startCameraSessionLocked fallo", e); cameraRunning = false
        }
    }

    fun closeCamera() {
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock { closeCameraLocked() }
        }
    }

    private fun closeCameraLocked() {
        try {
            if (_isRecording.value) safeStopMediaRecorder()
            try { captureSession?.stopRepeating() } catch (_: Throwable) {}
            captureSession?.close(); captureSession = null
            cameraDevice?.close(); cameraDevice = null
            multiReader.release()
            mediaRecorder?.release(); mediaRecorder = null
            recordSurface?.release(); recordSurface = null
        } catch (e: Throwable) {
            Log.w(TAG, "closeCamera warn", e)
        } finally { cameraRunning = false }
    }

    override fun onCleared() {
        super.onCleared(); closeCamera()
        cameraThread?.quitSafely(); cameraThread = null; cameraHandler = null
        bgThread?.quitSafely(); bgThread = null; bgHandler = null
    }

    // ─── Toggles ──────────────────────────────────────────────────────────────

    fun toggleFlash() {
        val next = when (_flashMode.value) {
            FlashMode.OFF -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.OFF
        }
        setFlashMode(next)
    }

    fun setFlashMode(mode: FlashMode) {
        _flashMode.value = mode
        _flashEnabled.value = (mode != FlashMode.OFF)
        applyRepeatingPreview()
    }

    fun toggleHdr() { _hdrEnabled.value = !_hdrEnabled.value; applyRepeatingPreview() }
    fun toggleGrid() { _gridEnabled.value = !_gridEnabled.value }
    fun toggleShutterSound() { _shutterSoundEnabled.value = !_shutterSoundEnabled.value }
    fun toggleHaptics() { _hapticsEnabled.value = !_hapticsEnabled.value }
    fun toggleHevc() { _hevcEnabled.value = !_hevcEnabled.value }
    fun toggleHistogram() {
        _histogramEnabled.value = !_histogramEnabled.value
        // Si se activa requerimos YUV reader → reconstruimos session si está activa
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                if (cameraRunning) {
                    ensureImageReaders()
                    cameraDevice?.let { createPreviewSessionSuspending(it) }
                }
            }
        }
    }
    fun toggleHorizon() { _horizonEnabled.value = !_horizonEnabled.value }
    fun toggleRaw() {
        _rawCapture.value = !_rawCapture.value
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                if (cameraRunning) {
                    ensureImageReaders()
                    cameraDevice?.let { createPreviewSessionSuspending(it) }
                }
            }
        }
    }
    fun toggleSmoothZoom() { _smoothZoomEnabled.value = !_smoothZoomEnabled.value }

    fun cycleTimer() {
        _timerSeconds.value = when (_timerSeconds.value) { 0 -> 3; 3 -> 10; else -> 0 }
    }

    fun cycleTheme() {
        _darkTheme.value = when (_darkTheme.value) { null -> true; true -> false; false -> null }
    }

    /**
     * Cicla por TODAS las paletas, incluyendo las nuevas (Obsidian, Grafito, Oro, Desert, Crimson).
     */
    fun cycleAccentStyle() {
        val all = AccentStyle.entries
        val nextIdx = (all.indexOf(_accentStyle.value) + 1) % all.size
        _accentStyle.value = all[nextIdx]
    }

    fun setAccentStyle(style: AccentStyle) { _accentStyle.value = style }
    fun setDarkPref(value: Boolean?) { _darkTheme.value = value }

    fun setCameraMode(mode: String) {
        if (mode !in listOf("FOTO", "VIDEO")) return
        val previous = _cameraMode.value
        _cameraMode.value = mode
        if (_manualAspect.value == null) {
            _previewAspectRatio.value = if (mode == "VIDEO") 9f / 16f else 3f / 4f
        }
        if (previous != mode) applyRepeatingPreview()
    }

    fun setManualAspect(aspect: PreviewAspect?) {
        _manualAspect.value = aspect
        _previewAspectRatio.value = aspect?.ratio ?: if (_cameraMode.value == "VIDEO") 9f / 16f else 3f / 4f
    }

    fun setVideoResolution(r: VideoResolution) {
        _videoResolution.value = r
        _supports60fps.value = CameraTuning.supportsFpsAtResolution(
            currentCharacteristics, r, 60
        )
        if (!_supports60fps.value && _videoFps.value == VideoFps.FPS60) {
            // fallback automático: la res no soporta 60
            _videoFps.value = VideoFps.FPS30
            Log.w(TAG, "Resolución $r no soporta 60fps → fallback 30fps")
        }
        applyRepeatingPreview()
    }

    /**
     * ▼ FIX 60 FPS ▼
     * Antes: solo guardaba el valor. Ahora:
     *   1. Verifica que el HAL soporta el FPS pedido a la resolución actual.
     *   2. Si no, hace fallback automático y deja log.
     *   3. Re-aplica el repeating preview para que el cambio sea inmediato.
     */
    fun setVideoFps(f: VideoFps) {
        val supported = if (f == VideoFps.FPS60) {
            CameraTuning.supportsFpsAtResolution(currentCharacteristics, _videoResolution.value, 60)
        } else true
        if (!supported) {
            Log.w(TAG, "Sensor no soporta 60fps en ${_videoResolution.value} → manteniendo 30fps")
            _videoFps.value = VideoFps.FPS30
            _supports60fps.value = false
        } else {
            _videoFps.value = f
            _supports60fps.value = (f == VideoFps.FPS60)
        }
        applyRepeatingPreview()
    }

    fun toggleFrontCamera(context: Context) {
        _isFrontCamera.value = !_isFrontCamera.value
        val surface = previewSurface ?: return
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                closeCameraLocked(); startCameraSessionLocked(context, surface, _currentLens.value)
            }
        }
    }

    fun switchLens(context: Context, lens: String) {
        if (_currentLens.value == lens) return
        _currentLens.value = lens
        _zoomLevel.value = when (lens) {
            "0.5x" -> 0.5f; "1x" -> 1f; "2x" -> 2f; "3x" -> 3f; else -> 1f
        }
        val surface = previewSurface ?: return
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                closeCameraLocked(); startCameraSessionLocked(context, surface, lens)
            }
        }
    }

    fun setZoom(ratio: Float) {
        val clamped = ratio.coerceIn(MIN_ZOOM, _zoomMax.value)
        _zoomLevel.value = clamped; applyRepeatingPreview()
    }

    fun setZoomContinuous(ratio: Float) {
        val clamped = ratio.coerceIn(MIN_ZOOM, _zoomMax.value)
        if (abs(clamped - _zoomLevel.value) < 0.01f) return
        _zoomLevel.value = clamped; applyRepeatingPreview()
    }

    /**
     * ▼ NUEVO (punto 17): Smooth zoom interpolado.
     * Anima desde el zoom actual hasta el destino en N steps a 60Hz.
     */
    fun smoothZoomTo(target: Float, durationMs: Long = 380L) {
        val from = _zoomLevel.value
        val to = target.coerceIn(MIN_ZOOM, _zoomMax.value)
        if (!_smoothZoomEnabled.value || abs(to - from) < 0.05f) {
            setZoom(to); return
        }
        smoothZoomJob?.cancel()
        smoothZoomJob = viewModelScope.launch {
            val steps = (durationMs / 16L).coerceAtLeast(8L).toInt()
            for (i in 1..steps) {
                val t = i / steps.toFloat()
                // ease-out cubic
                val ease = 1f - (1f - t) * (1f - t) * (1f - t)
                _zoomLevel.value = from + (to - from) * ease
                applyRepeatingPreview()
                delay(16L)
            }
            _zoomLevel.value = to
            applyRepeatingPreview()
        }
    }

    fun getExposureRange(): Range<Int>? = currentCharacteristics
        ?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)

    fun setExposure(level: Int) {
        val range = getExposureRange() ?: return
        _exposureLevel.value = level.coerceIn(range.lower, range.upper)
        applyRepeatingPreview()
    }

    fun setManualFocus(value: Float?) {
        _manualFocus.value = value?.coerceIn(0f, 1f)
        if (value != null) _focusLocked.value = true
        applyRepeatingPreview()
    }

    fun resetManualFocus() {
        _manualFocus.value = null; _focusLocked.value = false; applyRepeatingPreview()
    }

    fun tapToFocus(
        x: Float, y: Float, previewW: Int, previewH: Int,
        previewLeft: Int = 0, previewTop: Int = 0
    ) {
        val area = sensorArea ?: return
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        if (previewW <= 0 || previewH <= 0) return
        try {
            val localX = (x - previewLeft).coerceIn(0f, previewW.toFloat())
            val localY = (y - previewTop).coerceIn(0f, previewH.toFloat())
            val nx = localX / previewW; val ny = localY / previewH

            val (rx, ry) = when (sensorOrientation % 360) {
                90 -> ny to (1f - nx)
                180 -> (1f - nx) to (1f - ny)
                270 -> (1f - ny) to nx
                else -> nx to ny
            }
            val finalX = if (_isFrontCamera.value) 1f - rx else rx

            val sx = (finalX * area.width()).toInt().coerceIn(0, area.width() - 1)
            val sy = (ry * area.height()).toInt().coerceIn(0, area.height() - 1)
            val half = (min(area.width(), area.height()) * 0.05f).toInt().coerceAtLeast(100)
            val rect = Rect(
                (sx - half).coerceAtLeast(0), (sy - half).coerceAtLeast(0),
                (sx + half).coerceAtMost(area.width() - 1), (sy + half).coerceAtMost(area.height() - 1)
            )
            val metering = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)

            _manualFocus.value = null; _focusLocked.value = false

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurface?.let { builder.addTarget(it) }
            applyCommon(builder)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(metering))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(metering))
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            session.capture(builder.build(), null, cameraHandler)
            applyRepeatingPreview()
        } catch (e: Exception) {
            Log.w(TAG, "tapToFocus error", e)
        }
    }

    fun tapToFocus(x: Float, y: Float, screenW: Int, screenH: Int) {
        tapToFocus(x, y, screenW, screenH, 0, 0)
    }

    fun toggleFocusLock() {
        _focusLocked.value = !_focusLocked.value; applyRepeatingPreview()
    }

    // ─── FOTO con flash funcional ─────────────────────────────────────────────

    fun takePicture(storage: MediaStorageManager, context: Context) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val jpegReader = multiReader.jpeg ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                jpegReader.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                        // ▼ Guardado en background thread (no en cameraHandler) → no bloquea preview
                        bgHandler?.post {
                            val uri = storage.saveJpeg(context, bytes)
                            if (uri != null) _lastPhotoUri.value = uri
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Procesar JPEG fallo", e)
                    } finally {
                        image.close()
                        // Restablecer AE_LOCK y preview
                        runCatching { unlockAeAndResume() }
                    }
                }, bgHandler)

                // ▼ FIX FLASH ▼ — precaptura robusta SI hay flash
                val flashWanted = _flashMode.value != FlashMode.OFF
                if (flashWanted) runPrecaptureRepeating(device, session)

                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(jpegReader.surface)
                applyCommon(builder)
                CameraTuning.applyImageQuality(builder, "FOTO", supportsVideoStabilization)
                CameraTuning.applyJpegQuality(builder)
                CameraTuning.applyJpegOrientation(builder, sensorOrientation, _isFrontCamera.value)
                SamsungVendorTags.applyCaptureSnapshotHint(builder)
                SamsungVendorTags.applyHdr(builder, _hdrEnabled.value)
                SamsungVendorTags.applyProTone(builder, _hdrEnabled.value)

                // ▼ FIX FLASH: configuración explícita por modo
                when (_flashMode.value) {
                    FlashMode.ON -> {
                        builder.set(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                        builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                    }
                    FlashMode.AUTO -> {
                        builder.set(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                        builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                    }
                    FlashMode.OFF -> {
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    }
                }

                session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession, r: CaptureRequest, t: TotalCaptureResult
                    ) {
                        // Lectura de metadatos en tiempo real (punto 3)
                        _iso.value = t.get(CaptureResult.SENSOR_SENSITIVITY)
                        _shutterSpeedNs.value = t.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    }
                }, cameraHandler)
            } catch (e: Exception) {
                Log.e(TAG, "takePicture fallo", e); applyRepeatingPreview()
            }
        }
    }

    /**
     * ▼ FIX FLASH DEFINITIVO ▼
     *
     * Usa `setRepeatingRequest` para que el AE pueda converger frame a frame
     * (clave en Samsung — un único capture() rara vez hace switching del flash).
     * Tras converger (o timeout 1.6s), aplica AE_LOCK=true para mantener el flash
     * preparado durante el STILL_CAPTURE.
     */
    private suspend fun runPrecaptureRepeating(device: CameraDevice, session: CameraCaptureSession) {
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurface?.let { builder.addTarget(it) }
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            when (_flashMode.value) {
                FlashMode.ON -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                }
                FlashMode.AUTO -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                }
                FlashMode.OFF -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
            }
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)

            suspendCancellableCoroutine<Unit> { cont ->
                var resolved = false
                val deadline = System.currentTimeMillis() + 1600L
                val callback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession, r: CaptureRequest, t: TotalCaptureResult
                    ) {
                        if (resolved) return
                        val ae = t.get(CaptureResult.CONTROL_AE_STATE)
                        val ok = ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                            ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                            ae == CaptureResult.CONTROL_AE_STATE_LOCKED ||
                            System.currentTimeMillis() > deadline
                        if (ok) {
                            resolved = true
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                }
                try {
                    // ▼ CLAVE: repeating, no single capture
                    session.setRepeatingRequest(builder.build(), callback, cameraHandler)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(Unit)
                }
                cont.invokeOnCancellation { resolved = true }
            }

            // Cancelar el precapture trigger (limpieza)
            try {
                val cancel = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                previewSurface?.let { cancel.addTarget(it) }
                applyCommon(cancel)
                cancel.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL)
                session.capture(cancel.build(), null, cameraHandler)
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    private fun unlockAeAndResume() {
        try {
            val device = cameraDevice ?: return
            val session = captureSession ?: return
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurface?.let { builder.addTarget(it) }
            applyCommon(builder)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
            session.capture(builder.build(), null, cameraHandler)
        } catch (_: Throwable) {}
        applyRepeatingPreview()
    }

    // ─── VIDEO ────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startVideoRecording(context: Context, storage: MediaStorageManager) {
        val device = cameraDevice ?: return
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                try {
                    val uri = storage.createVideoUri(context) ?: return@withLock
                    val pfd = storage.openVideoFd(context, uri) ?: return@withLock
                    videoUri = uri; videoPfd = pfd

                    val resolution = _videoResolution.value
                    val fps = _videoFps.value.value
                    val codecInt = CameraTuning.preferredEncoder(_hevcEnabled.value)
                    val codecLabel = CameraTuning.preferredCodecLabel(_hevcEnabled.value)
                    val bitrate = VideoBitrateCalculator.preset(resolution, fps, codecLabel)

                    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        MediaRecorder(context)
                    } else {
                        @Suppress("DEPRECATION") MediaRecorder()
                    }.apply {
                        setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                        setVideoSource(MediaRecorder.VideoSource.SURFACE)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setOutputFile(pfd.fileDescriptor)
                        setVideoSize(resolution.width, resolution.height)
                        setVideoFrameRate(fps)
                        setVideoEncodingBitRate(bitrate)
                        setVideoEncoder(codecInt)
                        try {
                            val profile = CameraTuning.preferredProfile(_hevcEnabled.value)
                            val level = CameraTuning.preferredLevel(resolution, fps, _hevcEnabled.value)
                            setVideoEncodingProfileLevel(profile, level)
                        } catch (_: Throwable) {}
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioEncodingBitRate(192_000)
                        setAudioSamplingRate(48_000)
                        setOrientationHint(jpegRotationHint())
                        prepare()
                    }
                    mediaRecorder = recorder
                    recordSurface = recorder.surface

                    val preview = previewSurface ?: return@withLock
                    val recording = recordSurface ?: return@withLock

                    try { captureSession?.stopRepeating() } catch (_: Throwable) {}
                    try { captureSession?.close() } catch (_: Throwable) {}
                    captureSession = null

                    val configured: CameraCaptureSession = suspendCancellableCoroutine { cont ->
                        try {
                            @Suppress("DEPRECATION")
                            device.createCaptureSession(
                                listOf(preview, recording),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        if (cont.isActive) cont.resume(session)
                                    }
                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        Log.e(TAG, "Record session config FAILED")
                                        if (cont.isActive) cont.cancel()
                                    }
                                },
                                cameraHandler
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "createCaptureSession (video) exception", e)
                            if (cont.isActive) cont.cancel(e)
                        }
                    }

                    captureSession = configured
                    val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    builder.addTarget(preview); builder.addTarget(recording)
                    applyCommon(builder)
                    CameraTuning.applyImageQuality(builder, "VIDEO", supportsVideoStabilization)
                    SamsungVendorTags.applyBase(builder, "VIDEO", isRecording = true)
                    SamsungVendorTags.applyRecordingFps(builder, fps)
                    SamsungVendorTags.applyHdr(builder, _hdrEnabled.value)

                    // ▼ FIX 60 FPS ▼ rango DEFINITIVO calculado por CameraTuning
                    val targetRange = CameraTuning.bestFpsRange(supportedFpsRanges, fps)
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetRange)
                    Log.d(TAG, "Video FPS target=$fps → applied range=$targetRange")

                    applyFlashForRecording(builder)
                    waitFirstFrameThenStart(configured, builder.build(), recorder)
                    _isRecording.value = true
                } catch (e: Exception) {
                    Log.e(TAG, "startVideoRecording fallo", e)
                    try { safeStopMediaRecorder() } catch (_: Throwable) {}
                    try { videoPfd?.close() } catch (_: Throwable) {}
                    videoPfd = null; videoUri = null
                    val dev = cameraDevice
                    if (dev != null) createPreviewSessionSuspending(dev)
                }
            }
        }
    }

    private suspend fun waitFirstFrameThenStart(
        session: CameraCaptureSession, request: CaptureRequest, recorder: MediaRecorder
    ) {
        suspendCancellableCoroutine<Unit> { cont ->
            var armed = false
            val cb = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession, r: CaptureRequest, t: TotalCaptureResult
                ) {
                    if (armed) return
                    armed = true
                    try { recorder.start() } catch (e: Throwable) {
                        Log.e(TAG, "recorder.start() falló dentro del warm-up", e)
                    }
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            try {
                session.setRepeatingRequest(request, cb, cameraHandler)
            } catch (e: Exception) {
                Log.e(TAG, "setRepeatingRequest (record warmup) error", e)
                try { recorder.start() } catch (_: Throwable) {}
                if (cont.isActive) cont.resume(Unit)
            }
            cont.invokeOnCancellation { armed = true }
        }
        delay(40)
    }

    fun stopVideoRecording(context: Context, storage: MediaStorageManager) {
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                try {
                    delay(80)
                    safeStopMediaRecorder()
                    _isRecording.value = false
                    val pfd = videoPfd; videoPfd = null
                    val uri = videoUri; videoUri = null
                    try { pfd?.close() } catch (_: Throwable) {}
                    uri?.let { storage.finalizeVideo(context, it) }

                    try { captureSession?.stopRepeating() } catch (_: Throwable) {}
                    try { captureSession?.close() } catch (_: Throwable) {}
                    captureSession = null
                    val device = cameraDevice
                    if (device != null) createPreviewSessionSuspending(device)
                } catch (e: Exception) {
                    Log.e(TAG, "stopVideoRecording fallo", e)
                }
            }
        }
    }

    private fun safeStopMediaRecorder() {
        try {
            mediaRecorder?.let {
                try { it.stop() } catch (_: Throwable) {}
                try { it.reset() } catch (_: Throwable) {}
                it.release()
            }
        } catch (_: Throwable) {}
        mediaRecorder = null
        try { recordSurface?.release() } catch (_: Throwable) {}
        recordSurface = null
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private fun ensureThreads() {
        if (cameraThread == null || !cameraThread!!.isAlive) {
            cameraThread = HandlerThread("CameraBg").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
        if (bgThread == null || !bgThread!!.isAlive) {
            bgThread = HandlerThread("CameraIO").apply { start() }
            bgHandler = Handler(bgThread!!.looper)
        }
    }

    private fun ensureImageReaders() {
        val ch = currentCharacteristics ?: return
        multiReader.configure(
            characteristics = ch,
            wantRaw = _rawCapture.value && multiReader.supportsRaw(ch),
            wantYuv = _histogramEnabled.value
        )
        // Listener YUV → histograma
        multiReader.yuv?.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val bins = HistogramComputer.computeY(img)
                if (bins != null) _histogramBins.value = bins
            } finally { img.close() }
        }, bgHandler)
    }

    private suspend fun createPreviewSessionSuspending(device: CameraDevice) {
        val surface = previewSurface ?: return
        try {
            ensureImageReaders()
            val targets = listOfNotNull(surface) + multiReader.targetSurfaces()

            val session: CameraCaptureSession = suspendCancellableCoroutine { cont ->
                try {
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(
                        targets,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                if (cont.isActive) cont.resume(session)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "Preview session config FAILED")
                                if (cont.isActive) cont.cancel()
                            }
                        }, cameraHandler
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "createCaptureSession (preview) exception", e)
                    if (cont.isActive) cont.cancel(e)
                }
            }
            captureSession = session
            applyRepeatingPreview()
        } catch (e: Exception) {
            Log.e(TAG, "createPreviewSessionSuspending fallo", e)
        }
    }

    fun applyRepeatingPreview() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            // El YUV reader recibe los frames del preview para el histograma
            multiReader.yuv?.surface?.let { builder.addTarget(it) }
            applyCommon(builder)
            CameraTuning.applyImageQuality(builder, _cameraMode.value, supportsVideoStabilization)
            SamsungVendorTags.applyBase(builder, _cameraMode.value, _isRecording.value)
            SamsungVendorTags.applyHdr(builder, _hdrEnabled.value)
            if (_focusLocked.value) {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
            if (_cameraMode.value == "VIDEO" && _flashMode.value == FlashMode.ON) {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            } else {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }

            // Listener para metadatos sensor (ISO + shutter speed) → punto 3
            val cb = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession, r: CaptureRequest, t: TotalCaptureResult
                ) {
                    _iso.value = t.get(CaptureResult.SENSOR_SENSITIVITY)
                    _shutterSpeedNs.value = t.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                }
            }
            session.setRepeatingRequest(builder.build(), cb, cameraHandler)
        } catch (e: Exception) {
            Log.w(TAG, "applyRepeatingPreview error", e)
        }
    }

    private fun applyFlashForRecording(builder: CaptureRequest.Builder) {
        when (_flashMode.value) {
            FlashMode.ON -> {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            FlashMode.AUTO, FlashMode.OFF -> {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
        }
    }

    private fun applyCommon(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        val manual = _manualFocus.value
        if (manual != null && _minFocusDistance.value > 0f) {
            val diopter = manual * _minFocusDistance.value
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopter)
        } else {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (_cameraMode.value == "VIDEO") CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

        if (_exposureLevel.value != 0) {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, _exposureLevel.value)
        }

        val requestedZoom = _zoomLevel.value
        val sensorZoom = (requestedZoom / currentOpticalBaseZoom).coerceIn(1f, MAX_DIGITAL_ZOOM)
        if (abs(sensorZoom - 1f) > 0.001f) {
            val applied = SamsungVendorTags.applyZoomRatio(builder, sensorZoom)
            if (!applied) {
                sensorArea?.let { area ->
                    val effective = sensorZoom.coerceIn(1f, MAX_DIGITAL_ZOOM)
                    val newW = (area.width() / effective).toInt().coerceAtLeast(1)
                    val newH = (area.height() / effective).toInt().coerceAtLeast(1)
                    val left = (area.width() - newW) / 2 + area.left
                    val top = (area.height() - newH) / 2 + area.top
                    builder.set(CaptureRequest.SCALER_CROP_REGION,
                        Rect(left, top, left + newW, top + newH))
                }
            }
        }

        if (_cameraMode.value == "VIDEO") {
            val fps = _videoFps.value.value
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                CameraTuning.bestFpsRange(supportedFpsRanges, fps))
        }
    }

    private fun jpegRotationHint(): Int {
        val deviceRotation = 0
        return if (_isFrontCamera.value) (sensorOrientation + deviceRotation) % 360
        else (sensorOrientation - deviceRotation + 360) % 360
    }

    private fun pickCameraSelection(mgr: CameraManager, front: Boolean, lens: String): CameraSelection {
        val ids = mgr.cameraIdList
        if (front) {
            ids.forEach { id ->
                val c = mgr.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    return CameraSelection(id, 1f)
                }
            }
        }
        val backCandidates = ids.mapNotNull { id ->
            val c = mgr.getCameraCharacteristics(id)
            val facing = c.get(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null
            if (facing != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull() ?: return@mapNotNull null
            CameraCandidate(id, facing, focal, CameraTuning.isLogicalMultiCamera(c),
                c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE))
        }
        if (backCandidates.isEmpty()) return CameraSelection(ids.firstOrNull() ?: "0", 1f)

        val logicalPrimary = backCandidates.firstOrNull { it.isLogicalMultiCamera }
        val widePhysical = backCandidates.minByOrNull { c ->
            kotlin.math.abs(c.focal - 5f) + if (c.isLogicalMultiCamera) 0.7f else 0f
        } ?: backCandidates.first()
        val ultraPhysical = backCandidates.minByOrNull { it.focal }
        val telePhysical = backCandidates.maxByOrNull { it.focal }
        val hasDedicatedTele = telePhysical != null && ultraPhysical != null &&
            telePhysical.id != widePhysical.id && telePhysical.focal >= widePhysical.focal * 1.8f
        val hasDedicatedUltra = ultraPhysical != null &&
            ultraPhysical.id != widePhysical.id && ultraPhysical.focal <= widePhysical.focal * 0.75f

        return when (lens) {
            "0.5x" -> if (hasDedicatedUltra && ultraPhysical != null)
                CameraSelection(ultraPhysical.id, 0.5f)
            else CameraSelection(logicalPrimary?.id ?: widePhysical.id, 1f)
            "3x" -> if (hasDedicatedTele && telePhysical != null)
                CameraSelection(telePhysical.id, 3f)
            else CameraSelection(logicalPrimary?.id ?: widePhysical.id, 1f)
            else -> CameraSelection(logicalPrimary?.id ?: widePhysical.id, 1f)
        }
    }
}
