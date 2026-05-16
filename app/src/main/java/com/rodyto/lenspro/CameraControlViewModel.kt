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
import android.media.ImageReader
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

// ──────────────────────────────────────────────────────────────────────────────
//  ENUMS PÚBLICOS — sin cambios en API externa.
// ──────────────────────────────────────────────────────────────────────────────

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

/** Modo de flash tri-estado (OFF / ON / AUTO) — afecta foto y torch en video. */
enum class FlashMode(val label: String) {
    OFF("Off"),
    AUTO("Auto"),
    ON("On")
}

class CameraControlViewModel : ViewModel() {

    companion object {
        private const val TAG = "RodytoLensPro"
        // Tope absoluto de zoom digital total que la app expone.
        const val MAX_DIGITAL_ZOOM = 30f
        // Punto en que conmutamos de CROP-only → CROP + upscale GPU.
        const val HYBRID_ZOOM_THRESHOLD = 10f
        // Mínimo zoom que la UI permite (ultra-wide simulada).
        const val MIN_ZOOM = 0.5f

        @Volatile
        private var nativeLibLoaded = false

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
        val id: String,
        val facing: Int,
        val focal: Float,
        val isLogicalMultiCamera: Boolean,
        val sensorArea: Rect?
    )

    private data class CameraSelection(
        val cameraId: String,
        val opticalBaseZoom: Float
    )

    @Suppress("unused")
    private external fun getPhysicalCameraIdsNative(): Array<String>

    private fun safeNativePhysicalIds(): Array<String> = try {
        if (nativeLibLoaded) getPhysicalCameraIdsNative() else emptyArray()
    } catch (e: Throwable) {
        Log.w(TAG, "Fallo getPhysicalCameraIdsNative", e)
        emptyArray()
    }

    // ─── StateFlows ───────────────────────────────────────────────────────────
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

    /** API legacy (boolean) preservada para retro-compatibilidad. */
    private val _flashEnabled = MutableStateFlow(false)
    val flashEnabled: StateFlow<Boolean> = _flashEnabled.asStateFlow()

    /** Modo de flash extendido (OFF/AUTO/ON). _flashEnabled queda sincronizado. */
    private val _flashMode = MutableStateFlow(FlashMode.OFF)
    val flashMode: StateFlow<FlashMode> = _flashMode.asStateFlow()

    private val _exposureLevel = MutableStateFlow(0)
    val exposureLevel: StateFlow<Int> = _exposureLevel.asStateFlow()

    private val _lastPhotoUri = MutableStateFlow<Uri?>(null)
    val lastPhotoUri: StateFlow<Uri?> = _lastPhotoUri.asStateFlow()

    private val _zoomLevel = MutableStateFlow(1f)
    val zoomLevel: StateFlow<Float> = _zoomLevel.asStateFlow()

    /** Tope dinámico de zoom (calculado a partir del sensor + clamp 30x). */
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

    /** Foco manual (0f=∞, 1f=cercano). null = automático. */
    private val _manualFocus = MutableStateFlow<Float?>(null)
    val manualFocus: StateFlow<Float?> = _manualFocus.asStateFlow()

    /** Distancia mínima de enfoque del sensor (diópter). */
    private val _minFocusDistance = MutableStateFlow(0f)
    val minFocusDistance: StateFlow<Float> = _minFocusDistance.asStateFlow()

    // ─── Camera2 state ────────────────────────────────────────────────────────
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
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

    private val sessionMutex = Mutex()

    @Volatile
    private var cameraRunning = false

    fun isCameraRunning(): Boolean = cameraRunning

    fun getZoomMaxValue(): Float = _zoomMax.value
    fun getZoomMinValue(): Float = MIN_ZOOM

    // ─── Preview size hint ────────────────────────────────────────────────────
    /** Tamaño actual del SurfaceView en píxeles (notificado desde CameraPreview). */
    @Volatile private var previewSurfaceWidth: Int = 0
    @Volatile private var previewSurfaceHeight: Int = 0

    /**
     * Llamado desde CameraPreview.surfaceChanged() para informar al ViewModel
     * del tamaño real del buffer de preview. Se usa en tapToFocus y para future
     * optimizaciones de buffer.
     */
    fun notifyPreviewSize(width: Int, height: Int) {
        previewSurfaceWidth = width
        previewSurfaceHeight = height
    }

    // ─── Ciclo de vida principal ──────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startCameraSession(context: Context, surface: Surface, lens: String) {
        previewSurface = surface
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                startCameraSessionLocked(context, surface, lens)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun startCameraSessionLocked(context: Context, surface: Surface, lens: String) {
        if (cameraRunning) return
        try {
            ensureThread()
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

            // FIX: detectar si VSTAB está realmente soportado para no forzarla en HALs que la fingen
            val vstabModes = currentCharacteristics
                ?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
            supportsVideoStabilization = vstabModes.any {
                it == CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON
            }

            // FIX: zoom máximo dinámico clamped al tope 30x global.
            val hwBased = maxHwZoom * currentOpticalBaseZoom
            _zoomMax.value = min(max(hwBased, 10f), MAX_DIGITAL_ZOOM)

            safeNativePhysicalIds()

            val device: CameraDevice = suspendCancellableCoroutine { cont ->
                try {
                    mgr.openCamera(
                        selection.cameraId,
                        object : CameraDevice.StateCallback() {
                            override fun onOpened(device: CameraDevice) {
                                if (cont.isActive) cont.resume(device)
                            }

                            override fun onDisconnected(device: CameraDevice) {
                                device.close()
                                cameraRunning = false
                                if (cont.isActive) cont.cancel()
                            }

                            override fun onError(device: CameraDevice, error: Int) {
                                Log.e(TAG, "openCamera error=$error cameraId=${selection.cameraId}")
                                device.close()
                                cameraRunning = false
                                if (cont.isActive) cont.cancel()
                            }
                        },
                        cameraHandler
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "openCamera exception", e)
                    if (cont.isActive) cont.cancel(e)
                }
            }

            cameraDevice = device
            cameraRunning = true
            createPreviewSessionSuspending(device)

        } catch (e: Exception) {
            Log.e(TAG, "startCameraSessionLocked fallo", e)
            cameraRunning = false
        }
    }

    fun closeCamera() {
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                closeCameraLocked()
            }
        }
    }

    private fun closeCameraLocked() {
        try {
            if (_isRecording.value) safeStopMediaRecorder()
            try { captureSession?.stopRepeating() } catch (_: Throwable) {}
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            mediaRecorder?.release()
            mediaRecorder = null
            recordSurface?.release()
            recordSurface = null
        } catch (e: Throwable) {
            Log.w(TAG, "closeCamera warn", e)
        } finally {
            cameraRunning = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeCamera()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    // ─── Toggle / set helpers ─────────────────────────────────────────────────

    /** Ciclo tri-estado del flash: OFF → AUTO → ON → OFF. */
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

    fun toggleHdr() {
        _hdrEnabled.value = !_hdrEnabled.value
        applyRepeatingPreview()
    }

    fun toggleGrid() {
        _gridEnabled.value = !_gridEnabled.value
    }

    fun toggleShutterSound() {
        _shutterSoundEnabled.value = !_shutterSoundEnabled.value
    }

    fun toggleHaptics() {
        _hapticsEnabled.value = !_hapticsEnabled.value
    }

    fun toggleHevc() {
        _hevcEnabled.value = !_hevcEnabled.value
    }

    fun cycleTimer() {
        _timerSeconds.value = when (_timerSeconds.value) {
            0 -> 3
            3 -> 10
            else -> 0
        }
    }

    fun cycleTheme() {
        _darkTheme.value = when (_darkTheme.value) {
            null -> true
            true -> false
            false -> null
        }
    }

    fun cycleAccentStyle() {
        _accentStyle.value = when (_accentStyle.value) {
            AccentStyle.ICE_BLUE -> AccentStyle.AURORA
            AccentStyle.AURORA -> AccentStyle.JADE
            AccentStyle.JADE -> AccentStyle.ICE_BLUE
        }
    }

    fun setCameraMode(mode: String) {
        if (mode !in listOf("FOTO", "VIDEO")) return
        val previous = _cameraMode.value
        _cameraMode.value = mode
        if (_manualAspect.value == null) {
            // FIX: Foto = 3:4 (vertical), Video = 9:16 (vertical 16:9 tradicional)
            _previewAspectRatio.value = if (mode == "VIDEO") 9f / 16f else 3f / 4f
        }
        // Si cambia entre modos, re-aplicar repeating para tomar el modo correcto de AE/AF
        if (previous != mode) applyRepeatingPreview()
    }

    fun setManualAspect(aspect: PreviewAspect?) {
        _manualAspect.value = aspect
        _previewAspectRatio.value = aspect?.ratio ?: if (_cameraMode.value == "VIDEO") 9f / 16f else 3f / 4f
    }

    fun setVideoResolution(r: VideoResolution) {
        _videoResolution.value = r
    }

    fun setVideoFps(f: VideoFps) {
        _videoFps.value = f
    }

    fun toggleFrontCamera(context: Context) {
        _isFrontCamera.value = !_isFrontCamera.value
        val surface = previewSurface ?: return
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                closeCameraLocked()
                startCameraSessionLocked(context, surface, _currentLens.value)
            }
        }
    }

    fun switchLens(context: Context, lens: String) {
        if (_currentLens.value == lens) return
        _currentLens.value = lens
        _zoomLevel.value = when (lens) {
            "0.5x" -> 0.5f
            "1x" -> 1f
            "2x" -> 2f
            "3x" -> 3f
            else -> 1f
        }
        val surface = previewSurface ?: return
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                closeCameraLocked()
                startCameraSessionLocked(context, surface, lens)
            }
        }
    }

    /** Zoom continuo: respeta el clamp dinámico (hasta 30x). */
    fun setZoom(ratio: Float) {
        val clamped = ratio.coerceIn(MIN_ZOOM, _zoomMax.value)
        _zoomLevel.value = clamped
        applyRepeatingPreview()
    }

    /**
     * NUEVO: Zoom continuo optimizado para slider/pinch.
     * No re-arma toda la sesión, solo actualiza repeating request.
     */
    fun setZoomContinuous(ratio: Float) {
        val clamped = ratio.coerceIn(MIN_ZOOM, _zoomMax.value)
        if (abs(clamped - _zoomLevel.value) < 0.01f) return
        _zoomLevel.value = clamped
        applyRepeatingPreview()
    }

    fun getExposureRange(): Range<Int>? = currentCharacteristics
        ?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)

    fun setExposure(level: Int) {
        val range = getExposureRange() ?: return
        _exposureLevel.value = level.coerceIn(range.lower, range.upper)
        applyRepeatingPreview()
    }

    /**
     * Foco manual: 0f→infinito, 1f→muy cerca. Pasar null para volver al AF automático.
     */
    fun setManualFocus(value: Float?) {
        _manualFocus.value = value?.coerceIn(0f, 1f)
        if (value != null) {
            _focusLocked.value = true
        }
        applyRepeatingPreview()
    }

    fun resetManualFocus() {
        _manualFocus.value = null
        _focusLocked.value = false
        applyRepeatingPreview()
    }

    /**
     * Tap-to-focus mejorado:
     *  - usa el rectángulo del preview real (no el de pantalla completa)
     *  - aplica la rotación del sensor
     *  - clamp riguroso para evitar IllegalArgumentException
     *  - resetea focusLocked si estaba en manual
     */
    fun tapToFocus(
        x: Float, y: Float,
        previewW: Int, previewH: Int,
        previewLeft: Int = 0, previewTop: Int = 0
    ) {
        val area = sensorArea ?: return
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        if (previewW <= 0 || previewH <= 0) return
        try {
            // Coordenadas dentro del preview (no pantalla)
            val localX = (x - previewLeft).coerceIn(0f, previewW.toFloat())
            val localY = (y - previewTop).coerceIn(0f, previewH.toFloat())

            // Normalizadas a [0..1] del preview
            val nx = localX / previewW
            val ny = localY / previewH

            // Rotación del sensor (90 / 270 invierte ejes)
            val (rx, ry) = when (sensorOrientation % 360) {
                90 -> ny to (1f - nx)
                180 -> (1f - nx) to (1f - ny)
                270 -> (1f - ny) to nx
                else -> nx to ny
            }
            // Cámara frontal: espejado horizontal
            val finalX = if (_isFrontCamera.value) 1f - rx else rx

            val sx = (finalX * area.width()).toInt().coerceIn(0, area.width() - 1)
            val sy = (ry * area.height()).toInt().coerceIn(0, area.height() - 1)
            // Tamaño de la zona de medición un poco más pequeño = enfoque más preciso
            val half = (min(area.width(), area.height()) * 0.05f).toInt().coerceAtLeast(100)
            val rect = Rect(
                (sx - half).coerceAtLeast(0),
                (sy - half).coerceAtLeast(0),
                (sx + half).coerceAtMost(area.width() - 1),
                (sy + half).coerceAtMost(area.height() - 1)
            )
            val metering = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)

            // Si veníamos de modo manual, lo liberamos
            _manualFocus.value = null
            _focusLocked.value = false

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurface?.let { builder.addTarget(it) }
            applyCommon(builder)
            // OVERRIDE: estos van DESPUÉS de applyCommon para no ser sobrescritos
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(metering))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(metering))
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)

            session.capture(builder.build(), null, cameraHandler)
            // Después del trigger, restablecer repeating con AF continuo
            applyRepeatingPreview()
        } catch (e: Exception) {
            Log.w(TAG, "tapToFocus error", e)
        }
    }

    /** Versión legacy con pantalla completa (mantengo compat). */
    fun tapToFocus(x: Float, y: Float, screenW: Int, screenH: Int) {
        tapToFocus(x, y, screenW, screenH, 0, 0)
    }

    fun toggleFocusLock() {
        _focusLocked.value = !_focusLocked.value
        applyRepeatingPreview()
    }

    // ─── FOTO: takePicture con AE convergence real ────────────────────────────

    fun takePicture(storage: MediaStorageManager, context: Context) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureImageReader()
                val targetSurface = imageReader?.surface ?: return@launch

                // Listener registrado ANTES del disparo para no perder la imagen
                imageReader?.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val uri = storage.saveJpeg(context, bytes)
                        if (uri != null) _lastPhotoUri.value = uri
                    } catch (e: Throwable) {
                        Log.e(TAG, "Procesar JPEG fallo", e)
                    } finally {
                        image.close()
                        applyRepeatingPreview()
                    }
                }, cameraHandler)

                // Pre-capture AE convergence si vamos a usar flash (ON o AUTO)
                val flashRequested = _flashMode.value != FlashMode.OFF
                if (flashRequested) {
                    runPrecaptureAndAwait(device, session)
                }

                // Disparo STILL
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(targetSurface)
                applyCommon(builder)
                CameraTuning.applyImageQuality(builder, "FOTO", supportsVideoStabilization)
                CameraTuning.applyJpegQuality(builder)
                CameraTuning.applyJpegOrientation(builder, sensorOrientation, _isFrontCamera.value)
                SamsungVendorTags.applyCaptureSnapshotHint(builder)
                SamsungVendorTags.applyHdr(builder, _hdrEnabled.value)
                SamsungVendorTags.applyProTone(builder, _hdrEnabled.value)

                // FIX FLASH: estos overrides van DESPUÉS de applyCommon
                when (_flashMode.value) {
                    FlashMode.ON -> {
                        builder.set(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                        builder.set(CaptureRequest.FLASH_MODE,
                            CaptureRequest.FLASH_MODE_SINGLE)
                    }
                    FlashMode.AUTO -> {
                        builder.set(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                    }
                    FlashMode.OFF -> {
                        builder.set(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON)
                        builder.set(CaptureRequest.FLASH_MODE,
                            CaptureRequest.FLASH_MODE_OFF)
                    }
                }

                session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) { /* preview se restaura en el image listener */ }
                }, cameraHandler)

            } catch (e: Exception) {
                Log.e(TAG, "takePicture fallo", e)
                applyRepeatingPreview()
            }
        }
    }

    /**
     * FIX FLASH: Lanza pre-capture trigger y espera hasta que AE converja (max 1500 ms).
     * NO llama a applyCommon ni sobrescribe AE_MODE — eso rompía la convergencia con flash.
     */
    private suspend fun runPrecaptureAndAwait(
        device: CameraDevice,
        session: CameraCaptureSession
    ) {
        try {
            val precaptureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurface?.let { precaptureBuilder.addTarget(it) }

            // Configuración mínima coherente con el modo de flash deseado.
            // No usamos applyCommon porque sobrescribe CONTROL_AE_MODE y mata la convergencia.
            precaptureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            precaptureBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (_cameraMode.value == "VIDEO")
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                else
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            precaptureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

            // FIX clave: AE_MODE acorde al flash deseado (esto faltaba)
            when (_flashMode.value) {
                FlashMode.ON -> precaptureBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                )
                FlashMode.AUTO -> precaptureBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                )
                FlashMode.OFF -> precaptureBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON
                )
            }

            precaptureBuilder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )

            // Esperamos a CONVERGED o timeout (1.5s — flash AUTO necesita más tiempo)
            suspendCancellableCoroutine<Unit> { cont ->
                var resolved = false
                val deadline = System.currentTimeMillis() + 1500L
                val callback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession, r: CaptureRequest, t: TotalCaptureResult
                    ) {
                        if (resolved) return
                        val state = t.get(CaptureResult.CONTROL_AE_STATE)
                        val converged = state == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                            state == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                            state == CaptureResult.CONTROL_AE_STATE_LOCKED ||
                            System.currentTimeMillis() > deadline
                        if (converged) {
                            resolved = true
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                }
                try {
                    session.capture(precaptureBuilder.build(), callback, cameraHandler)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(Unit)
                }
                cont.invokeOnCancellation { resolved = true }
            }
        } catch (_: Throwable) { /* ignorar — flash funcionará mejor o peor */ }
    }

    // ─── VIDEO: refactor profundo (sin lag, FPS estable) ──────────────────────

    @SuppressLint("MissingPermission")
    fun startVideoRecording(context: Context, storage: MediaStorageManager) {
        val device = cameraDevice ?: return
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                try {
                    val uri = storage.createVideoUri(context) ?: return@withLock
                    val pfd = storage.openVideoFd(context, uri) ?: return@withLock
                    videoUri = uri
                    videoPfd = pfd

                    val resolution = _videoResolution.value
                    val fps = _videoFps.value.value
                    val codecInt = CameraTuning.preferredEncoder(_hevcEnabled.value)
                    val codecLabel = CameraTuning.preferredCodecLabel(_hevcEnabled.value)
                    val bitrate = VideoBitrateCalculator.preset(resolution, fps, codecLabel)

                    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        MediaRecorder(context)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaRecorder()
                    }.apply {
                        // ORDEN CRÍTICO en MediaRecorder
                        setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                        setVideoSource(MediaRecorder.VideoSource.SURFACE)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setOutputFile(pfd.fileDescriptor)
                        setVideoSize(resolution.width, resolution.height)
                        setVideoFrameRate(fps)
                        setVideoEncodingBitRate(bitrate)
                        setVideoEncoder(codecInt)
                        // Perfil/Level explícitos: evita que el HAL caiga en Baseline
                        try {
                            val profile = CameraTuning.preferredProfile(_hevcEnabled.value)
                            val level = CameraTuning.preferredLevel(resolution, fps, _hevcEnabled.value)
                            setVideoEncodingProfileLevel(profile, level)
                        } catch (_: Throwable) { /* opcional */ }
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

                    // FIX CRÍTICO: cerrar la sesión actual ANTES de crear la nueva
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
                    builder.addTarget(preview)
                    builder.addTarget(recording)
                    applyCommon(builder)
                    CameraTuning.applyImageQuality(builder, "VIDEO", supportsVideoStabilization)
                    SamsungVendorTags.applyBase(builder, "VIDEO", isRecording = true)
                    SamsungVendorTags.applyRecordingFps(builder, fps)
                    SamsungVendorTags.applyHdr(builder, _hdrEnabled.value)

                    // FPS range robusto: elegimos el rango soportado más cercano a fps fijo,
                    // o caemos a [fps/2, fps] como fallback (evita lag en poca luz).
                    val targetRange = bestFpsRange(fps)
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetRange)

                    // TORCH durante grabación si el usuario tiene flash ON o AUTO con baja luz.
                    applyFlashForRecording(builder)

                    // FIX VIDEO LAG: Esperar a que la pipeline esté caliente (primer onCaptureCompleted)
                    // antes de arrancar el recorder. Esto elimina los drops iniciales que causaban
                    // 6 fps efectivos en la mayoría de dispositivos.
                    waitFirstFrameThenStart(configured, builder.build(), recorder)
                    _isRecording.value = true

                } catch (e: Exception) {
                    Log.e(TAG, "startVideoRecording fallo", e)
                    try { safeStopMediaRecorder() } catch (_: Throwable) {}
                    try { videoPfd?.close() } catch (_: Throwable) {}
                    videoPfd = null
                    videoUri = null
                    // Restaurar preview en caso de error
                    val dev = cameraDevice
                    if (dev != null) createPreviewSessionSuspending(dev)
                }
            }
        }
    }

    /**
     * NUEVO — Warm-up del pipeline: arranca repeating, espera primer frame, luego recorder.start().
     * Esto evita los frames-drop iniciales que provocaban grabación a 6 FPS en muchos dispositivos.
     */
    private suspend fun waitFirstFrameThenStart(
        session: CameraCaptureSession,
        request: CaptureRequest,
        recorder: MediaRecorder
    ) {
        suspendCancellableCoroutine<Unit> { cont ->
            var armed = false
            val cb = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession, r: CaptureRequest, t: TotalCaptureResult
                ) {
                    if (armed) return
                    armed = true
                    try {
                        recorder.start()
                    } catch (e: Throwable) {
                        Log.e(TAG, "recorder.start() falló dentro del warm-up", e)
                    }
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            try {
                session.setRepeatingRequest(request, cb, cameraHandler)
            } catch (e: Exception) {
                Log.e(TAG, "setRepeatingRequest (record warmup) error", e)
                // Fallback: arrancar directo
                try { recorder.start() } catch (_: Throwable) {}
                if (cont.isActive) cont.resume(Unit)
            }
            cont.invokeOnCancellation { armed = true }
        }
        // Pequeño extra para que el encoder llene su cola
        delay(40)
    }

    fun stopVideoRecording(context: Context, storage: MediaStorageManager) {
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                try {
                    // Dar un poco de margen al encoder antes del stop
                    delay(80)
                    safeStopMediaRecorder()
                    _isRecording.value = false

                    // FIX RACE: cerrar el FD ANTES de finalize para que el contenedor MP4
                    // esté completamente flusheado en disco antes de IS_PENDING=0.
                    try { videoPfd?.close() } catch (_: Throwable) {}
                    videoPfd = null

                    videoUri?.let { storage.finalizeVideo(context, it) }
                    videoUri = null

                    // Cerramos la sesión de record y abrimos preview limpia
                    try { captureSession?.stopRepeating() } catch (_: Throwable) {}
                    try { captureSession?.close() } catch (_: Throwable) {}
                    captureSession = null

                    val device = cameraDevice
                    if (device != null) {
                        createPreviewSessionSuspending(device)
                    }
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

    /** Mejor rango FPS soportado: prioriza [fps,fps], luego [fps/2,fps]. */
    private fun bestFpsRange(fps: Int): Range<Int> {
        val ranges = supportedFpsRanges
        if (ranges.isEmpty()) return Range(fps, fps)
        // Exact match
        ranges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it }
        // Upper exactly fps y lower lo más alto posible (evita FPS variable bajo)
        ranges.filter { it.upper == fps }
            .maxByOrNull { it.lower }
            ?.let { return it }
        // Upper >= fps
        ranges.filter { it.upper >= fps }
            .minByOrNull { abs(it.upper - fps) + abs(it.lower - fps) }
            ?.let { return Range(min(it.lower, fps), fps) }
        // Fallback: el rango con upper más alto
        return ranges.maxByOrNull { it.upper } ?: Range(fps, fps)
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private fun ensureThread() {
        if (cameraThread == null || !cameraThread!!.isAlive) {
            cameraThread = HandlerThread("CameraBg").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
    }

    private fun ensureImageReader() {
        if (imageReader != null) return
        val characteristics = currentCharacteristics ?: return
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: return
        val largest = sizes.maxByOrNull { it.width.toLong() * it.height } ?: return
        imageReader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.JPEG, 2)
    }

    private suspend fun createPreviewSessionSuspending(device: CameraDevice) {
        val surface = previewSurface ?: return
        try {
            ensureImageReader()
            val targets = listOfNotNull(surface, imageReader?.surface)

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
                        },
                        cameraHandler
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
            applyCommon(builder)
            CameraTuning.applyImageQuality(builder, _cameraMode.value, supportsVideoStabilization)
            SamsungVendorTags.applyBase(builder, _cameraMode.value, _isRecording.value)
            SamsungVendorTags.applyHdr(builder, _hdrEnabled.value)
            if (_focusLocked.value) {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
            // Torch sólo si estamos en VIDEO y flash ON (constante durante preview de video)
            if (_cameraMode.value == "VIDEO" && _flashMode.value == FlashMode.ON) {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            } else {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.w(TAG, "applyRepeatingPreview error", e)
        }
    }

    /** Aplica torch al builder de grabación si procede. */
    private fun applyFlashForRecording(builder: CaptureRequest.Builder) {
        when (_flashMode.value) {
            FlashMode.ON -> {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            FlashMode.AUTO -> {
                // En video AUTO no enciende torch; deja AE decidir flash en preview-no-flash
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            FlashMode.OFF -> {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
        }
    }

    private fun applyCommon(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        // ─── Enfoque ───
        val manual = _manualFocus.value
        if (manual != null && _minFocusDistance.value > 0f) {
            // Foco manual (override del AF)
            val diopter = manual * _minFocusDistance.value
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopter)
        } else {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (_cameraMode.value == "VIDEO") {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                } else {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                }
            )
        }

        // ─── AE / Flash ───
        // En preview siempre AE_ON; el flash real se gestiona en cada disparo o por torch en video.
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

        if (_exposureLevel.value != 0) {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, _exposureLevel.value)
        }

        // ─── ZOOM (híbrido óptico-base × digital) ───
        // FIX CRÍTICO 3X: el clamp permite ahora hasta MAX_DIGITAL_ZOOM aunque maxHwZoom=1
        val requestedZoom = _zoomLevel.value
        val sensorZoom = (requestedZoom / currentOpticalBaseZoom)
            .coerceIn(1f, MAX_DIGITAL_ZOOM)

        if (abs(sensorZoom - 1f) > 0.001f) {
            // Intentamos primero el vendor tag de Samsung (más eficiente, sin pérdida)
            val applied = SamsungVendorTags.applyZoomRatio(builder, sensorZoom)
            if (!applied) {
                // Fallback API >=30: SCALER_CROP_REGION estándar
                sensorArea?.let { area ->
                    // FIX 3X: usar el zoom completo solicitado, no el limitado por maxHwZoom.
                    // Esto permite crop digital de hasta 30x sobre la lente principal cuando
                    // no hay tele física, en lugar de quedarse en el límite del HW.
                    val effective = sensorZoom.coerceIn(1f, MAX_DIGITAL_ZOOM)
                    val newW = (area.width() / effective).toInt().coerceAtLeast(1)
                    val newH = (area.height() / effective).toInt().coerceAtLeast(1)
                    val left = (area.width() - newW) / 2 + area.left
                    val top = (area.height() - newH) / 2 + area.top
                    builder.set(
                        CaptureRequest.SCALER_CROP_REGION,
                        Rect(left, top, left + newW, top + newH)
                    )
                }
            }
        }

        // ─── FPS range para VIDEO ───
        if (_cameraMode.value == "VIDEO") {
            val fps = _videoFps.value.value
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestFpsRange(fps))
        }
    }

    /** Calcula el JPEG_ORIENTATION para la cámara (orientación del dispositivo). */
    private fun jpegRotationHint(): Int {
        // Asumiendo portrait fijo (la app está bloqueada a portrait).
        val deviceRotation = 0
        return if (_isFrontCamera.value) {
            (sensorOrientation + deviceRotation) % 360
        } else {
            (sensorOrientation - deviceRotation + 360) % 360
        }
    }

    private fun pickCameraSelection(mgr: CameraManager, front: Boolean, lens: String): CameraSelection {
        val ids = mgr.cameraIdList
        if (front) {
            ids.forEach { id ->
                val characteristics = mgr.getCameraCharacteristics(id)
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    return CameraSelection(id, 1f)
                }
            }
        }

        val backCandidates = ids.mapNotNull { id ->
            val characteristics = mgr.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                ?: return@mapNotNull null
            if (facing != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
            val focal = characteristics
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull() ?: return@mapNotNull null
            CameraCandidate(
                id = id,
                facing = facing,
                focal = focal,
                isLogicalMultiCamera = CameraTuning.isLogicalMultiCamera(characteristics),
                sensorArea = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            )
        }

        if (backCandidates.isEmpty()) {
            return CameraSelection(ids.firstOrNull() ?: "0", 1f)
        }

        val logicalPrimary = backCandidates.firstOrNull { it.isLogicalMultiCamera }
        val widePhysical = backCandidates.minByOrNull { candidate ->
            kotlin.math.abs(candidate.focal - 5f) + if (candidate.isLogicalMultiCamera) 0.7f else 0f
        } ?: backCandidates.first()
        val ultraPhysical = backCandidates.minByOrNull { it.focal }
        val telePhysical = backCandidates.maxByOrNull { it.focal }

        val hasDedicatedTele = telePhysical != null && ultraPhysical != null &&
            telePhysical.id != widePhysical.id &&
            telePhysical.focal >= widePhysical.focal * 1.8f

        val hasDedicatedUltra = ultraPhysical != null &&
            ultraPhysical.id != widePhysical.id &&
            ultraPhysical.focal <= widePhysical.focal * 0.75f

        return when (lens) {
            "0.5x" -> {
                if (hasDedicatedUltra && ultraPhysical != null) {
                    CameraSelection(ultraPhysical.id, 0.5f)
                } else {
                    // Si no hay ultra-wide física, mantenemos lente principal a 1×.
                    // (La UI mostrará 0.5× pero no se puede zoom-out por debajo del HW.)
                    CameraSelection(logicalPrimary?.id ?: widePhysical.id, 1f)
                }
            }

            "3x" -> {
                if (hasDedicatedTele && telePhysical != null) {
                    Log.d(TAG, "Usando tele física para 3x: ${telePhysical.id}")
                    CameraSelection(telePhysical.id, 3f)
                } else {
                    // FIX clave: si no hay tele física, hacemos 3x digital sobre principal
                    // dejando opticalBaseZoom=1f para que applyCommon haga el crop 3x.
                    Log.d(TAG, "Tele física no disponible; usando 3x digital sobre principal")
                    CameraSelection(logicalPrimary?.id ?: widePhysical.id, 1f)
                }
            }

            else -> CameraSelection(logicalPrimary?.id ?: widePhysical.id, 1f)
        }
    }
}
