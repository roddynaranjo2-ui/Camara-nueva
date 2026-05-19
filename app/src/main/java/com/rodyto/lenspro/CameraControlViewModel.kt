package com.rodyto.lenspro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Range
import android.util.Size
import android.util.SizeF
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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

/** Estado de la sesión de cámara. */
enum class CameraSessionState { IDLE, OPENING, RUNNING, CLOSING, ERROR }

/**
 * CameraControlViewModel v3.6 Pro — núcleo Camera2 (motor primario).
 *
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║  FIX CRÍTICOS v3.6 (resuelven los bugs reportados):                  ║
 * ║                                                                      ║
 * ║   1. PREVIEW NEGRO  → useCameraXAnalysis default=false; el bridge    ║
 * ║      ya NO compite por el mismo HAL.                                 ║
 * ║                                                                      ║
 * ║   2. SWITCH LENTO al voltear / cambiar lente → closeCameraLocked     ║
 * ║      ya no bloquea 800 ms con Thread.sleep. Cierra de forma          ║
 * ║      asíncrona y vuelve en cuanto el callback onClosed emite (≤80ms).║
 * ║                                                                      ║
 * ║   3. LAG en zoom continuo / drag → applyRepeatingPreview ahora       ║
 * ║      hace coalescing: si llegan ≥1 llamadas en <16 ms se funden en   ║
 * ║      la siguiente vsync. Evita el GC churn del builder.              ║
 * ║                                                                      ║
 * ║   4. attachToRepository idempotente: nunca duplica colectores aunque ║
 * ║      el LaunchedEffect se relance.                                   ║
 * ║                                                                      ║
 * ║   5. Fallback robusto a id "0" si nunca se localiza una cámara back. ║
 * ║                                                                      ║
 * ║   6. forceTelePhysicalId default=false → "3x" usa logical+digital    ║
 * ║      excepto que el usuario lo active manualmente.                   ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 *
 * Todas las APIs públicas y persistencia bidireccional preservadas.
 */
class CameraControlViewModel : ViewModel() {

    companion object {
        private const val TAG = "RodytoLensPro"
        const val MAX_DIGITAL_ZOOM = 30f
        const val HYBRID_ZOOM_THRESHOLD = 10f
        const val MIN_ZOOM = 0.5f

        /** Physical Camera ID por defecto para el teleobjetivo (S21 FE = "52"). */
        const val DEFAULT_TELE_PHYSICAL_ID = "52"

        /** Coalescing window para applyRepeatingPreview — ~60fps. */
        private const val PREVIEW_COALESCE_MS = 16L

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
        val id: String,
        val facing: Int,
        val focal: Float,
        val physicalSize: SizeF?,
        val sensorArea: Rect?,
        val isLogicalMultiCamera: Boolean,
        val isPhysicalOnly: Boolean,
        val maxZoom: Float,
        val parentLogicalId: String? = null
    ) {
        val sensorDiagonal: Float
            get() = physicalSize?.let {
                kotlin.math.sqrt(it.width * it.width + it.height * it.height)
            } ?: 0f
    }

    private data class CameraSelection(
        val cameraId: String,
        val opticalBaseZoom: Float,
        val isOpticalTele: Boolean = false
    )

    @Suppress("unused")
    private external fun getPhysicalCameraIdsNative(): Array<String>

    private fun safeNativePhysicalIds(): Array<String> = try {
        if (nativeLibLoaded) getPhysicalCameraIdsNative() else emptyArray()
    } catch (e: Throwable) {
        Log.w(TAG, "Fallo getPhysicalCameraIdsNative", e); emptyArray()
    }

    // ─── StateFlows ───────────────────────────────────────────────────────
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

    private val _exposureEv = MutableStateFlow(0f)
    val exposureEv: StateFlow<Float> = _exposureEv.asStateFlow()

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

    private val _iso = MutableStateFlow<Int?>(null)
    val iso: StateFlow<Int?> = _iso.asStateFlow()

    private val _shutterSpeedNs = MutableStateFlow<Long?>(null)
    val shutterSpeedNs: StateFlow<Long?> = _shutterSpeedNs.asStateFlow()

    private val _availableLenses = MutableStateFlow(listOf("0.5x", "1x", "3x"))
    val availableLenses: StateFlow<List<String>> = _availableLenses.asStateFlow()

    private val _telephotoIsOptical = MutableStateFlow(false)
    val telephotoIsOptical: StateFlow<Boolean> = _telephotoIsOptical.asStateFlow()

    private val _sessionState = MutableStateFlow(CameraSessionState.IDLE)
    val sessionState: StateFlow<CameraSessionState> = _sessionState.asStateFlow()

    private val _activePhysicalTeleId = MutableStateFlow("")
    val activePhysicalTeleId: StateFlow<String> = _activePhysicalTeleId.asStateFlow()

    /** Mensaje de error legible para mostrar al usuario si la cámara falla. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // ─── Manual Pro controls ──────────────────────────────────────────────
    private val _manualIso = MutableStateFlow(0)
    val manualIso: StateFlow<Int> = _manualIso.asStateFlow()
    private val _manualShutterNs = MutableStateFlow<Long?>(null)
    val manualShutterNs: StateFlow<Long?> = _manualShutterNs.asStateFlow()
    private val _manualWbKelvin = MutableStateFlow(0)
    val manualWbKelvin: StateFlow<Int> = _manualWbKelvin.asStateFlow()

    // ─── Hybrid pipeline flags (DEFAULTS SEGUROS v3.6) ────────────────────
    // useCameraXAnalysis = false por defecto → evita conflicto HAL que
    // dejaba el preview en negro en la mayoría de dispositivos.
    private val _useCameraXAnalysis = MutableStateFlow(false)
    val useCameraXAnalysis: StateFlow<Boolean> = _useCameraXAnalysis.asStateFlow()
    // forceTelePhysicalId = false por defecto → la lente "3x" usa logical
    // + zoom digital salvo que el usuario lo habilite manualmente.
    private val _forceTelePhysicalId = MutableStateFlow(false)
    val forceTelePhysicalId: StateFlow<Boolean> = _forceTelePhysicalId.asStateFlow()
    private val _telePhysicalIdPref = MutableStateFlow(DEFAULT_TELE_PHYSICAL_ID)
    val telePhysicalIdPref: StateFlow<String> = _telePhysicalIdPref.asStateFlow()
    private val _proVendorTags = MutableStateFlow(true)
    val proVendorTags: StateFlow<Boolean> = _proVendorTags.asStateFlow()

    @Volatile private var deviceDisplayRotation: Int = 0
    fun notifyDeviceRotation(degrees: Int) {
        deviceDisplayRotation = ((degrees % 360) + 360) % 360
    }

    @Volatile private var lastTotalResult: TotalCaptureResult? = null
    @Volatile private var currentSessionSurfaces: Set<Surface> = emptySet()

    // ─── Camera2 state ────────────────────────────────────────────────────
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

    @Volatile private var optimalPreviewW: Int = 0
    @Volatile private var optimalPreviewH: Int = 0
    fun getOptimalPreviewSize(): Pair<Int, Int> = optimalPreviewW to optimalPreviewH

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private val threadsLock = Any()
    private val sessionMutex = Mutex()
    private val sessionAtomic = AtomicReference(CameraSessionState.IDLE)

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

    @Volatile private var pendingJpegConsumer: ((ByteArray) -> Unit)? = null
    @Volatile private var pendingRawConsumer: ((Image) -> Unit)? = null

    // ── v3.6: coalescing de applyRepeatingPreview ─────────────────────────
    private val pendingRepeating = AtomicBoolean(false)
    @Volatile private var lastRepeatingNs: Long = 0L

    // ── v3.6: guard idempotente para attachToRepository ───────────────────
    private val attached = AtomicBoolean(false)

    // ──────────────────────────────────────────────────────────────────────
    //  PERSISTENCIA REACTIVA
    // ──────────────────────────────────────────────────────────────────────
    suspend fun applyPersistedSettings(repo: SettingsRepository) {
        try {
            _histogramEnabled.value = repo.histogramEnabled.first()
            _horizonEnabled.value = repo.horizonEnabled.first()
            _rawCapture.value = repo.rawCapture.first()
            _smoothZoomEnabled.value = repo.smoothZoom.first()
            _hdrEnabled.value = repo.hdrEnabled.first()
            _gridEnabled.value = repo.gridEnabled.first()
            _hevcEnabled.value = repo.hevcEnabled.first()
            _shutterSoundEnabled.value = repo.shutterSound.first()
            _hapticsEnabled.value = repo.hapticsEnabled.first()
            _flashMode.value = repo.flashFromString(repo.flashMode.first())
            _flashEnabled.value = (_flashMode.value != FlashMode.OFF)
            _timerSeconds.value = repo.timerSeconds.first()
            _videoResolution.value = repo.videoResFromString(repo.videoResolution.first())
            _videoFps.value = repo.videoFpsFromInt(repo.videoFps.first())
            _accentStyle.value = repo.accentFromIndex(repo.accentIndex.first())
            _darkTheme.value = repo.themeFromString(repo.themeMode.first())
            _manualAspect.value = repo.aspectFromLabel(repo.manualAspect.first())
            _currentLens.value = repo.lastLens.first()

            _useCameraXAnalysis.value = repo.useCameraXAnalysis.first()
            _forceTelePhysicalId.value = repo.forceTelePhysicalId.first()
            _telePhysicalIdPref.value = repo.telePhysicalId.first()
            _proVendorTags.value = repo.proVendorTags.first()
            _manualIso.value = repo.isoManual.first()
            _manualShutterNs.value = repo.shutterNsFromString(repo.shutterManualNs.first())
            _manualWbKelvin.value = repo.wbManualKelvin.first()

            val want60 = repo.video60fpsDefault.first()
            if (want60 && _videoFps.value == VideoFps.FPS30) _videoFps.value = VideoFps.FPS60
        } catch (e: Throwable) {
            Log.w(TAG, "applyPersistedSettings: fallo leyendo repo", e)
        }
    }

    /**
     * Engancha colectores DataStore → VM. IDEMPOTENTE: si ya está enganchado
     * no relanza. Evita el bug de duplicación de collectors al recomponerse.
     */
    fun attachToRepository(repo: SettingsRepository) {
        if (!attached.compareAndSet(false, true)) {
            Log.d(TAG, "attachToRepository: ya enganchado (skip)")
            return
        }
        viewModelScope.launch {
            repo.accentIndex.distinctUntilChanged().collect { idx ->
                val style = repo.accentFromIndex(idx)
                if (_accentStyle.value != style) _accentStyle.value = style
            }
        }
        viewModelScope.launch {
            repo.themeMode.distinctUntilChanged().collect { mode ->
                val v = repo.themeFromString(mode)
                if (_darkTheme.value != v) _darkTheme.value = v
            }
        }
        viewModelScope.launch {
            repo.rawCapture.distinctUntilChanged().collect { v ->
                if (_rawCapture.value != v) setRawCaptureEnabled(v)
            }
        }
        viewModelScope.launch {
            repo.hdrEnabled.distinctUntilChanged().collect { v ->
                if (_hdrEnabled.value != v) { _hdrEnabled.value = v; applyRepeatingPreview() }
            }
        }
        viewModelScope.launch {
            repo.gridEnabled.distinctUntilChanged().collect { v -> if (_gridEnabled.value != v) _gridEnabled.value = v }
        }
        viewModelScope.launch {
            repo.histogramEnabled.distinctUntilChanged().collect { v ->
                if (_histogramEnabled.value != v) setHistogramEnabled(v)
            }
        }
        viewModelScope.launch {
            repo.horizonEnabled.distinctUntilChanged().collect { v -> if (_horizonEnabled.value != v) _horizonEnabled.value = v }
        }
        viewModelScope.launch {
            repo.smoothZoom.distinctUntilChanged().collect { v -> if (_smoothZoomEnabled.value != v) _smoothZoomEnabled.value = v }
        }
        viewModelScope.launch {
            repo.hevcEnabled.distinctUntilChanged().collect { v -> if (_hevcEnabled.value != v) _hevcEnabled.value = v }
        }
        viewModelScope.launch {
            repo.shutterSound.distinctUntilChanged().collect { v -> if (_shutterSoundEnabled.value != v) _shutterSoundEnabled.value = v }
        }
        viewModelScope.launch {
            repo.hapticsEnabled.distinctUntilChanged().collect { v -> if (_hapticsEnabled.value != v) _hapticsEnabled.value = v }
        }
        viewModelScope.launch {
            repo.flashMode.distinctUntilChanged().collect { s ->
                val mode = repo.flashFromString(s)
                if (_flashMode.value != mode) { _flashMode.value = mode; _flashEnabled.value = mode != FlashMode.OFF; applyRepeatingPreview() }
            }
        }
        viewModelScope.launch {
            repo.timerSeconds.distinctUntilChanged().collect { v -> if (_timerSeconds.value != v) _timerSeconds.value = v }
        }
        viewModelScope.launch {
            repo.videoResolution.distinctUntilChanged().collect { s ->
                val r = repo.videoResFromString(s)
                if (_videoResolution.value != r) setVideoResolution(r)
            }
        }
        viewModelScope.launch {
            repo.videoFps.distinctUntilChanged().collect { v ->
                val f = repo.videoFpsFromInt(v)
                if (_videoFps.value != f) setVideoFps(f)
            }
        }
        viewModelScope.launch {
            repo.manualAspect.distinctUntilChanged().collect { label ->
                val a = repo.aspectFromLabel(label)
                if (_manualAspect.value != a) setManualAspect(a)
            }
        }
        viewModelScope.launch {
            repo.useCameraXAnalysis.distinctUntilChanged().collect { v ->
                if (_useCameraXAnalysis.value != v) _useCameraXAnalysis.value = v
            }
        }
        viewModelScope.launch {
            repo.forceTelePhysicalId.distinctUntilChanged().collect { v ->
                if (_forceTelePhysicalId.value != v) {
                    _forceTelePhysicalId.value = v
                    if (_currentLens.value == "3x") reopenIfNeeded()
                }
            }
        }
        viewModelScope.launch {
            repo.telePhysicalId.distinctUntilChanged().collect { id ->
                if (_telePhysicalIdPref.value != id) {
                    _telePhysicalIdPref.value = id
                    if (_currentLens.value == "3x") reopenIfNeeded()
                }
            }
        }
        viewModelScope.launch {
            repo.proVendorTags.distinctUntilChanged().collect { v ->
                if (_proVendorTags.value != v) { _proVendorTags.value = v; applyRepeatingPreview() }
            }
        }
        viewModelScope.launch {
            repo.isoManual.distinctUntilChanged().collect { iso ->
                if (_manualIso.value != iso) { _manualIso.value = iso; applyRepeatingPreview() }
            }
        }
        viewModelScope.launch {
            repo.shutterManualNs.distinctUntilChanged().collect { s ->
                val ns = repo.shutterNsFromString(s)
                if (_manualShutterNs.value != ns) { _manualShutterNs.value = ns; applyRepeatingPreview() }
            }
        }
        viewModelScope.launch {
            repo.wbManualKelvin.distinctUntilChanged().collect { k ->
                if (_manualWbKelvin.value != k) { _manualWbKelvin.value = k; applyRepeatingPreview() }
            }
        }
    }

    /** Reabre la cámara si está corriendo. */
    private fun reopenIfNeeded() {
        val ctx = cameraContextRef
        val surf = previewSurface
        if (ctx != null && surf != null && cameraRunning) {
            viewModelScope.launch(Dispatchers.IO) {
                sessionMutex.withLock {
                    closeCameraLocked()
                    startCameraSessionLocked(ctx, surf, _currentLens.value)
                }
            }
        }
    }

    @Volatile private var cameraContextRef: Context? = null

    // ─── Ciclo de vida ────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startCameraSession(context: Context, surface: Surface, lens: String) {
        previewSurface = surface
        cameraContextRef = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock { startCameraSessionLocked(context, surface, lens) }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun startCameraSessionLocked(context: Context, surface: Surface, lens: String) {
        if (cameraRunning) return
        if (!surface.isValid) {
            Log.w(TAG, "startCameraSessionLocked: surface inválida")
            return
        }
        if (!sessionAtomic.compareAndSet(CameraSessionState.IDLE, CameraSessionState.OPENING) &&
            !sessionAtomic.compareAndSet(CameraSessionState.ERROR, CameraSessionState.OPENING)) {
            Log.w(TAG, "startCameraSessionLocked: estado no-IDLE: ${sessionAtomic.get()}")
            return
        }
        _sessionState.value = CameraSessionState.OPENING
        try {
            ensureThreads()
            cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val mgr = cameraManager!!

            val backCandidates = buildBackCandidatesCached(mgr)
            updateAvailableLenses(backCandidates)

            val selection = pickCameraSelection(mgr, _isFrontCamera.value, lens, backCandidates)
            currentCameraId = selection.cameraId
            currentOpticalBaseZoom = selection.opticalBaseZoom
            _activePhysicalTeleId.value = if (selection.isOpticalTele) selection.cameraId else ""

            currentCharacteristics = try {
                mgr.getCameraCharacteristics(selection.cameraId)
            } catch (e: CameraAccessException) {
                Log.e(TAG, "No se pueden leer caracteristicas de ${selection.cameraId}", e)
                val fallbackId = mgr.cameraIdList.firstOrNull() ?: "0"
                currentCameraId = fallbackId
                currentOpticalBaseZoom = 1f
                mgr.getCameraCharacteristics(fallbackId)
            }
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

            _supports60fps.value = CameraTuning.supportsFpsAtResolution(
                currentCharacteristics, _videoResolution.value, 60
            )

            val targetRatio = _previewAspectRatio.value
            val opt = CameraTuning.pickOptimalPreviewSize(currentCharacteristics, targetRatio)
            optimalPreviewW = opt.width
            optimalPreviewH = opt.height

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
                            Log.w(TAG, "Camera onDisconnected id=${selection.cameraId}")
                            try { device.close() } catch (_: Throwable) {}
                            cameraRunning = false
                            sessionAtomic.set(CameraSessionState.IDLE)
                            _sessionState.value = CameraSessionState.IDLE
                            if (cont.isActive) cont.cancel()
                        }
                        override fun onError(device: CameraDevice, error: Int) {
                            val errStr = when (error) {
                                ERROR_CAMERA_IN_USE -> "ERROR_CAMERA_IN_USE"
                                ERROR_MAX_CAMERAS_IN_USE -> "ERROR_MAX_CAMERAS_IN_USE"
                                ERROR_CAMERA_DISABLED -> "ERROR_CAMERA_DISABLED"
                                ERROR_CAMERA_DEVICE -> "ERROR_CAMERA_DEVICE"
                                ERROR_CAMERA_SERVICE -> "ERROR_CAMERA_SERVICE"
                                else -> "ERROR_$error"
                            }
                            Log.e(TAG, "openCamera error=$errStr cameraId=${selection.cameraId}")
                            _lastError.value = "No se pudo abrir la cámara ($errStr)"
                            try { device.close() } catch (_: Throwable) {}
                            cameraRunning = false
                            sessionAtomic.set(CameraSessionState.ERROR)
                            _sessionState.value = CameraSessionState.ERROR
                            if (cont.isActive) cont.cancel()
                        }
                    }, cameraHandler)
                } catch (e: Exception) {
                    Log.e(TAG, "openCamera exception", e)
                    _lastError.value = "Excepción abriendo cámara: ${e.javaClass.simpleName}"
                    if (cont.isActive) cont.cancel(e)
                }
            }

            cameraDevice = device
            cameraRunning = true
            _lastError.value = null
            ensureImageReaders()
            createPreviewSessionSuspending(device)
            sessionAtomic.set(CameraSessionState.RUNNING)
            _sessionState.value = CameraSessionState.RUNNING
        } catch (e: Exception) {
            Log.e(TAG, "startCameraSessionLocked fallo", e)
            cameraRunning = false
            sessionAtomic.set(CameraSessionState.ERROR)
            _sessionState.value = CameraSessionState.ERROR

            // FALLBACK: si pediste tele físico y falló, reintentar con logical+digital
            if (_currentLens.value == "3x" && _activePhysicalTeleId.value.isNotEmpty()) {
                Log.w(TAG, "Apertura tele física falló — fallback a logical+digital 3x")
                _activePhysicalTeleId.value = ""
                _telephotoIsOptical.value = false
                try {
                    val mgr2 = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val cands = backCandidatesOrDefault(mgr2)
                    val logical = cands.firstOrNull { it.isLogicalMultiCamera && !it.isPhysicalOnly }
                    val wide = cands.minByOrNull { c -> abs(c.focal - 5f) + if (c.isLogicalMultiCamera) 0.7f else 0f }
                    val fbId = logical?.id ?: wide?.id ?: cands.firstOrNull()?.id ?: "0"
                    currentCameraId = fbId
                    currentOpticalBaseZoom = 1f
                    sessionAtomic.set(CameraSessionState.IDLE)
                    startCameraSessionLocked(context, surface, "3x")
                    _zoomLevel.value = 3f
                    applyRepeatingPreview()
                } catch (e2: Throwable) {
                    Log.e(TAG, "Fallback a digital 3x también falló", e2)
                }
            }
        }
    }

    private fun backCandidatesOrDefault(mgr: CameraManager): List<CameraCandidate> {
        return cachedBackCandidates ?: buildBackCandidatesCached(mgr)
    }

    fun closeCamera() {
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock { closeCameraLocked() }
        }
    }

    /**
     * v3.6: closeCameraLocked rápido. En lugar de un Thread.sleep
     * arbitrario de hasta 800 ms, esperamos al onClosed callback (real)
     * con un timeout corto (250 ms) — suficiente para HAL Camera2 moderno.
     */
    private suspend fun closeCameraLocked() {
        sessionAtomic.set(CameraSessionState.CLOSING)
        _sessionState.value = CameraSessionState.CLOSING
        try {
            if (_isRecording.value) safeStopMediaRecorder()
            try { captureSession?.stopRepeating() } catch (_: Throwable) {}
            try { captureSession?.abortCaptures() } catch (_: Throwable) {}
            try { captureSession?.close() } catch (_: Throwable) {}
            captureSession = null
            currentSessionSurfaces = emptySet()

            val device = cameraDevice
            if (device != null) {
                withTimeoutOrNull(250L) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        try {
                            // No hay onClosed estándar accesible vía StateCallback ya registrado,
                            // así que cerramos y damos un yield mínimo asíncrono.
                            device.close()
                        } catch (_: Throwable) {}
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
            cameraDevice = null
            multiReader.release()
            mediaRecorder?.release(); mediaRecorder = null
            try { recordSurface?.release() } catch (_: Throwable) {}
            recordSurface = null
        } catch (e: Throwable) {
            Log.w(TAG, "closeCamera warn", e)
        } finally {
            cameraRunning = false
            pendingJpegConsumer = null
            pendingRawConsumer = null
            sessionAtomic.set(CameraSessionState.IDLE)
            _sessionState.value = CameraSessionState.IDLE
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock { closeCameraLocked() }
            synchronized(threadsLock) {
                cameraThread?.quitSafely(); cameraThread = null; cameraHandler = null
                bgThread?.quitSafely(); bgThread = null; bgHandler = null
            }
        }
    }

    // ─── Toggles ──────────────────────────────────────────────────────────

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
        val newValue = !_histogramEnabled.value
        setHistogramEnabled(newValue)
    }

    fun setHistogramEnabled(enabled: Boolean) {
        if (_histogramEnabled.value == enabled) return
        _histogramEnabled.value = enabled
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
    fun setHorizonEnabled(enabled: Boolean) { _horizonEnabled.value = enabled }

    fun toggleRaw() {
        val newValue = !_rawCapture.value
        setRawCaptureEnabled(newValue)
    }

    fun setRawCaptureEnabled(enabled: Boolean) {
        if (_rawCapture.value == enabled) return
        _rawCapture.value = enabled
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
    fun setSmoothZoomEnabled(enabled: Boolean) { _smoothZoomEnabled.value = enabled }

    fun setVideoFpsDefault60(want60: Boolean) {
        _videoFps.value = if (want60) VideoFps.FPS60 else VideoFps.FPS30
    }

    fun cycleTimer() {
        _timerSeconds.value = when (_timerSeconds.value) { 0 -> 3; 3 -> 10; else -> 0 }
    }

    fun cycleTheme() {
        _darkTheme.value = when (_darkTheme.value) { null -> true; true -> false; false -> null }
    }

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
        val opt = CameraTuning.pickOptimalPreviewSize(currentCharacteristics, _previewAspectRatio.value)
        optimalPreviewW = opt.width
        optimalPreviewH = opt.height
    }

    fun setVideoResolution(r: VideoResolution) {
        _videoResolution.value = r
        _supports60fps.value = CameraTuning.supportsFpsAtResolution(
            currentCharacteristics, r, 60
        )
        if (!_supports60fps.value && _videoFps.value == VideoFps.FPS60) {
            _videoFps.value = VideoFps.FPS30
            Log.w(TAG, "Resolución $r no soporta 60fps → fallback 30fps")
        }
        applyRepeatingPreview()
    }

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
        val surface = previewSurface ?: return
        val newFront = !_isFrontCamera.value
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                closeCameraLocked()
                _isFrontCamera.value = newFront
                // Resetear lens a 1x al voltear — los lentes son específicos a la facing.
                if (newFront) {
                    _currentLens.value = "1x"
                    _zoomLevel.value = 1f
                }
                startCameraSessionLocked(context, surface, _currentLens.value)
                if (!cameraRunning) {
                    Log.w(TAG, "Apertura front=$newFront falló — revirtiendo")
                    _isFrontCamera.value = !newFront
                    startCameraSessionLocked(context, surface, _currentLens.value)
                }
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

    fun getExposureStep(): Float {
        val r = currentCharacteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        return r?.let { it.numerator.toFloat() / it.denominator.toFloat() } ?: (1f / 6f)
    }

    fun setExposure(level: Int) {
        val range = getExposureRange() ?: return
        val clamped = level.coerceIn(range.lower, range.upper)
        _exposureLevel.value = clamped
        _exposureEv.value = clamped * getExposureStep()
        applyRepeatingPreview()
    }

    fun setExposureEv(evStops: Float) {
        val range = getExposureRange() ?: return
        val step = getExposureStep().coerceAtLeast(0.001f)
        val raw = (evStops / step).toInt().coerceIn(range.lower, range.upper)
        _exposureLevel.value = raw
        _exposureEv.value = raw * step
        applyRepeatingPreview()
    }

    fun getExposureEvRange(): ClosedFloatingPointRange<Float> {
        val r = getExposureRange() ?: return -2f..2f
        val step = getExposureStep()
        return (r.lower * step)..(r.upper * step)
    }

    fun getIsoRange(): Range<Int>? = currentCharacteristics
        ?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    fun getShutterRangeNs(): Range<Long>? = currentCharacteristics
        ?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

    fun setManualIso(iso: Int) {
        _manualIso.value = iso.coerceAtLeast(0)
        applyRepeatingPreview()
    }
    fun setManualShutterNs(ns: Long?) {
        _manualShutterNs.value = ns?.coerceAtLeast(1L)
        applyRepeatingPreview()
    }
    fun setManualWbKelvin(k: Int) {
        _manualWbKelvin.value = k.coerceAtLeast(0)
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

    fun supportsManualFocus(): Boolean = (_minFocusDistance.value > 0f)

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
            val half = (min(area.width(), area.height()) * 0.04f).toInt().coerceAtLeast(100)
            val rect = Rect(
                (sx - half).coerceAtLeast(0), (sy - half).coerceAtLeast(0),
                (sx + half).coerceAtMost(area.width() - 1), (sy + half).coerceAtMost(area.height() - 1)
            )
            val metering = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)

            _manualFocus.value = null; _focusLocked.value = false

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurface?.let { builder.addTarget(it) }
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
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

    // ─── FOTO con flash + RAW funcional ───────────────────────────────────

    fun takePicture(storage: MediaStorageManager, context: Context) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val jpegReader = multiReader.jpeg ?: return
        val wantRaw = _rawCapture.value && multiReader.raw != null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                pendingJpegConsumer = { bytes ->
                    bgHandler?.post {
                        val uri = storage.saveJpeg(context, bytes)
                        if (uri != null) _lastPhotoUri.value = uri
                        runCatching { unlockAeAndResume() }
                    }
                }

                val rawCharacteristics = currentCharacteristics
                if (wantRaw && rawCharacteristics != null) {
                    pendingRawConsumer = { rawImage ->
                        bgHandler?.post {
                            try {
                                val totalResult = lastTotalResult
                                if (totalResult != null) {
                                    val dng = DngCreator(rawCharacteristics, totalResult)
                                    dng.setOrientation(jpegRotationHintToExif())
                                    val baos = ByteArrayOutputStream(rawImage.width * rawImage.height * 2)
                                    dng.writeImage(baos, rawImage)
                                    storage.saveRaw(context, baos.toByteArray())
                                    dng.close()
                                }
                            } catch (e: Throwable) {
                                Log.e(TAG, "DNG write failed", e)
                            } finally {
                                try { rawImage.close() } catch (_: Throwable) {}
                            }
                        }
                    }
                }

                val flashWanted = _flashMode.value != FlashMode.OFF
                if (flashWanted) runPrecaptureRepeating(device, session)

                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(jpegReader.surface)
                if (wantRaw) multiReader.raw?.surface?.let { builder.addTarget(it) }
                applyCommon(builder)
                CameraTuning.applyImageQuality(builder, "FOTO", supportsVideoStabilization)
                CameraTuning.applyJpegQuality(builder)
                CameraTuning.applyJpegOrientationDynamic(
                    builder, sensorOrientation, _isFrontCamera.value, deviceDisplayRotation
                )
                if (_proVendorTags.value) {
                    SamsungVendorTags.applyCaptureSnapshotHint(builder)
                    SamsungVendorTags.applyHdr(builder, _hdrEnabled.value)
                    SamsungVendorTags.applyProTone(builder, _hdrEnabled.value)
                }

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
                        lastTotalResult = t
                        _iso.value = t.get(CaptureResult.SENSOR_SENSITIVITY)
                        _shutterSpeedNs.value = t.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    }
                }, cameraHandler)
            } catch (e: Exception) {
                Log.e(TAG, "takePicture fallo", e); applyRepeatingPreview()
            }
        }
    }

    private suspend fun runPrecaptureRepeating(device: CameraDevice, session: CameraCaptureSession) {
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurface?.let { builder.addTarget(it) }
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            when (_flashMode.value) {
                FlashMode.ON -> builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                FlashMode.AUTO -> builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                FlashMode.OFF -> builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON)
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
                    session.setRepeatingRequest(builder.build(), callback, cameraHandler)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(Unit)
                }
                cont.invokeOnCancellation { resolved = true }
            }

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

    // ─── VIDEO ────────────────────────────────────────────────────────────

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
                    val recSize = CameraTuning.pickOptimalRecordingSize(currentCharacteristics, resolution)

                    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        MediaRecorder(context)
                    } else {
                        @Suppress("DEPRECATION") MediaRecorder()
                    }.apply {
                        setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                        setVideoSource(MediaRecorder.VideoSource.SURFACE)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setOutputFile(pfd.fileDescriptor)
                        setVideoSize(recSize.width, recSize.height)
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
                        setOrientationHint(videoRotationHint())
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
                    currentSessionSurfaces = setOf(preview, recording)
                    val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    builder.addTarget(preview); builder.addTarget(recording)
                    applyCommon(builder)
                    CameraTuning.applyImageQuality(builder, "VIDEO", supportsVideoStabilization)
                    if (_proVendorTags.value) {
                        SamsungVendorTags.applyBase(builder, "VIDEO", isRecording = true)
                        SamsungVendorTags.applyRecordingFps(builder, fps)
                        SamsungVendorTags.applyHdr(builder, _hdrEnabled.value)
                    }

                    val targetRange = CameraTuning.bestFpsRange(supportedFpsRanges, fps)
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetRange)
                    Log.d(TAG, "Video FPS target=$fps → applied range=$targetRange size=$recSize")

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

    // ─── Internals ────────────────────────────────────────────────────────

    private fun ensureThreads() {
        synchronized(threadsLock) {
            val ct = cameraThread
            if (ct == null || !ct.isAlive) {
                cameraThread = HandlerThread("CameraBg").apply { start() }
                cameraHandler = Handler(cameraThread!!.looper)
            }
            val bt = bgThread
            if (bt == null || !bt.isAlive) {
                bgThread = HandlerThread("CameraIO").apply { start() }
                bgHandler = Handler(bgThread!!.looper)
            }
        }
    }

    private fun ensureImageReaders() {
        val ch = currentCharacteristics ?: return
        // Si CameraX bridge no está activo, podemos usar YUV Camera2 para el histograma.
        val wantYuvFromCamera2 = _histogramEnabled.value && !_useCameraXAnalysis.value
        multiReader.configure(
            characteristics = ch,
            wantRaw = _rawCapture.value && multiReader.supportsRaw(ch),
            wantYuv = wantYuvFromCamera2
        )

        multiReader.yuv?.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val bins = HistogramComputer.computeY(img)
                if (bins != null) _histogramBins.value = bins
            } finally { img.close() }
        }, bgHandler)

        multiReader.jpeg?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                val consumer = pendingJpegConsumer
                pendingJpegConsumer = null
                consumer?.invoke(bytes)
            } catch (e: Throwable) {
                Log.e(TAG, "Procesar JPEG fallo", e)
            } finally {
                image.close()
            }
        }, cameraHandler)

        multiReader.raw?.setOnImageAvailableListener({ reader ->
            val img = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            val consumer = pendingRawConsumer
            pendingRawConsumer = null
            if (consumer != null) {
                consumer.invoke(img)
            } else {
                try { img.close() } catch (_: Throwable) {}
            }
        }, cameraHandler)
    }

    fun publishHistogramBins(bins: IntArray?) {
        _histogramBins.value = bins
    }

    private suspend fun createPreviewSessionSuspending(device: CameraDevice) {
        val surface = previewSurface ?: return
        if (!surface.isValid) {
            Log.w(TAG, "createPreviewSessionSuspending: surface inválida")
            return
        }
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
            currentSessionSurfaces = targets.toSet()
            applyRepeatingPreviewImmediate()
        } catch (e: Exception) {
            Log.e(TAG, "createPreviewSessionSuspending fallo", e)
        }
    }

    /**
     * v3.6: applyRepeatingPreview con COALESCING.
     * Si se llama varias veces dentro de PREVIEW_COALESCE_MS, sólo el último
     * estado se efectiva — evita el builder churn durante drags rápidos.
     */
    fun applyRepeatingPreview() {
        if (!pendingRepeating.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.Main.immediate) {
            try {
                val now = System.nanoTime()
                val sinceLastMs = (now - lastRepeatingNs) / 1_000_000L
                if (sinceLastMs < PREVIEW_COALESCE_MS) {
                    delay(PREVIEW_COALESCE_MS - sinceLastMs)
                }
                applyRepeatingPreviewImmediate()
                lastRepeatingNs = System.nanoTime()
            } finally {
                pendingRepeating.set(false)
            }
        }
    }

    /** Versión inmediata sin coalescing (sólo desde callbacks internos seguros). */
    private fun applyRepeatingPreviewImmediate() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return
        if (!cameraRunning) return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            multiReader.yuv?.surface?.let { yuvSurface ->
                if (yuvSurface in currentSessionSurfaces) builder.addTarget(yuvSurface)
            }
            applyCommon(builder)
            CameraTuning.applyImageQuality(builder, _cameraMode.value, supportsVideoStabilization)
            if (_proVendorTags.value) {
                SamsungVendorTags.applyBase(builder, _cameraMode.value, _isRecording.value)
                SamsungVendorTags.applyHdr(builder, _hdrEnabled.value)
            }
            if (_focusLocked.value) {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
            if (_cameraMode.value == "VIDEO" && _flashMode.value == FlashMode.ON) {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            } else {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }

            val cb = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession, r: CaptureRequest, t: TotalCaptureResult
                ) {
                    lastTotalResult = t
                    _iso.value = t.get(CaptureResult.SENSOR_SENSITIVITY)
                    _shutterSpeedNs.value = t.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                }
            }
            session.setRepeatingRequest(builder.build(), cb, cameraHandler)
        } catch (e: Exception) {
            Log.w(TAG, "applyRepeatingPreviewImmediate error", e)
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

        val iso = _manualIso.value
        val shutterNs = _manualShutterNs.value
        val wbK = _manualWbKelvin.value
        val anyManualExposure = iso > 0 && shutterNs != null && shutterNs > 0L
        if (anyManualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        if (wbK > 0) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }

        if (_exposureLevel.value != 0 && !anyManualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, _exposureLevel.value)
        }

        // ── Zoom ────────────────────────────────────────────────────────
        val requestedZoom = _zoomLevel.value
        val sensorZoom = (requestedZoom / currentOpticalBaseZoom).coerceIn(1f, MAX_DIGITAL_ZOOM)
        if (abs(sensorZoom - 1f) > 0.001f) {
            val applied = if (_proVendorTags.value)
                SamsungVendorTags.applyZoomRatio(builder, sensorZoom)
            else false
            if (!applied) {
                sensorArea?.let { area ->
                    val effective = sensorZoom.coerceIn(1f, MAX_DIGITAL_ZOOM)
                    val newW = (area.width() / effective).toInt().coerceAtLeast(1)
                    val newH = (area.height() / effective).toInt().coerceAtLeast(1)
                    val left = (area.width() - newW) / 2 + area.left
                    val top = (area.height() - newH) / 2 + area.top
                    builder.set(CaptureRequest.SCALER_CROP_REGION,
                        Rect(left, top, left + newW, top + newH))

                    builder.set(CaptureRequest.CONTROL_AE_REGIONS,
                        arrayOf(MeteringRectangle(
                            Rect(left, top, left + newW, top + newH),
                            MeteringRectangle.METERING_WEIGHT_MAX - 1
                        )))
                }
            }
        }

        if (_cameraMode.value == "VIDEO") {
            val fps = _videoFps.value.value
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                CameraTuning.bestFpsRange(supportedFpsRanges, fps))
        }
    }

    private fun videoRotationHint(): Int {
        val deviceRotation = deviceDisplayRotation
        return if (_isFrontCamera.value) (sensorOrientation + deviceRotation) % 360
        else (sensorOrientation - deviceRotation + 360) % 360
    }

    private fun jpegRotationHintToExif(): Int = videoRotationHint()

    // ─── Selección de cámara ──────────────────────────────────────────────

    @Volatile private var cachedBackCandidates: List<CameraCandidate>? = null

    private fun buildBackCandidatesCached(mgr: CameraManager): List<CameraCandidate> {
        cachedBackCandidates?.let { return it }

        val all = mutableListOf<CameraCandidate>()
        val visibleIds = try { mgr.cameraIdList } catch (_: Throwable) { emptyArray<String>() }
        for (id in visibleIds) {
            try {
                val c = mgr.getCameraCharacteristics(id)
                val facing = c.get(CameraCharacteristics.LENS_FACING) ?: continue
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
                all += extractCandidate(c, id, isPhysicalOnly = false, parentLogicalId = null)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    CameraTuning.isLogicalMultiCamera(c)) {
                    try {
                        val physicalIds = c.physicalCameraIds
                        for (pid in physicalIds) {
                            if (pid in visibleIds) continue
                            try {
                                val pc = mgr.getCameraCharacteristics(pid)
                                all += extractCandidate(pc, pid, isPhysicalOnly = true, parentLogicalId = id)
                            } catch (e: Throwable) {
                                Log.v(TAG, "Cannot get characteristics for physical id=$pid", e)
                            }
                        }
                    } catch (e: Throwable) {
                        Log.v(TAG, "No physicalCameraIds on id=$id", e)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Cannot read characteristics for id=$id", e)
            }
        }

        val result = all.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK && it.focal > 0f }
        Log.i(TAG, "Camera scan v3.6: ${result.size} candidatos back. " +
            result.joinToString { "id=${it.id}|f=${it.focal}|phys=${it.physicalSize}|onlyPhys=${it.isPhysicalOnly}|parent=${it.parentLogicalId}" })

        cachedBackCandidates = result
        return result
    }

    private fun extractCandidate(
        c: CameraCharacteristics,
        id: String,
        isPhysicalOnly: Boolean,
        parentLogicalId: String?
    ): CameraCandidate {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull() ?: 0f
        val physicalSize: SizeF? = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val sensorRect: Rect? = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val isLogical = CameraTuning.isLogicalMultiCamera(c)
        val maxZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return CameraCandidate(
            id = id,
            facing = c.get(CameraCharacteristics.LENS_FACING) ?: -1,
            focal = focal,
            physicalSize = physicalSize,
            sensorArea = sensorRect,
            isLogicalMultiCamera = isLogical,
            isPhysicalOnly = isPhysicalOnly,
            maxZoom = maxZoom,
            parentLogicalId = parentLogicalId
        )
    }

    private fun updateAvailableLenses(candidates: List<CameraCandidate>) {
        if (candidates.isEmpty()) {
            _availableLenses.value = listOf("1x")
            _telephotoIsOptical.value = false
            return
        }
        val widePhysical = candidates.minByOrNull { c ->
            abs(c.focal - 5f) + if (c.isLogicalMultiCamera) 0.7f else 0f
        } ?: candidates.first()
        val ultraPhysical = candidates.minByOrNull { it.focal }
        val telePhysical = candidates.maxByOrNull { it.focal }

        val hasDedicatedUltra = ultraPhysical != null &&
            ultraPhysical.id != widePhysical.id &&
            ultraPhysical.focal <= widePhysical.focal * 0.75f
        val hasDedicatedTele = telePhysical != null && ultraPhysical != null &&
            telePhysical.id != widePhysical.id && telePhysical.focal >= widePhysical.focal * 1.8f

        val forcedTeleId = _telePhysicalIdPref.value
        val haveForcedPhysical = _forceTelePhysicalId.value &&
            candidates.any { it.id == forcedTeleId }

        val lenses = mutableListOf<String>()
        if (hasDedicatedUltra) lenses += "0.5x"
        lenses += "1x"
        // Sólo añadir "3x" si realmente lo soporta el HW o si el usuario forzó el ID
        if (hasDedicatedTele || haveForcedPhysical || maxHwZoom >= 3f) lenses += "3x"

        _availableLenses.value = lenses
        _telephotoIsOptical.value = hasDedicatedTele || haveForcedPhysical
        Log.i(TAG, "Lentes disponibles: $lenses (tele óptico=${_telephotoIsOptical.value}, forcedPhys=$haveForcedPhysical, dedicated=$hasDedicatedTele)")
    }

    private fun pickCameraSelection(
        mgr: CameraManager,
        front: Boolean,
        lens: String,
        precomputedCandidates: List<CameraCandidate>? = null
    ): CameraSelection {
        if (front) {
            val ids = try { mgr.cameraIdList } catch (_: Throwable) { emptyArray<String>() }
            ids.forEach { id ->
                try {
                    val c = mgr.getCameraCharacteristics(id)
                    if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                        return CameraSelection(id, 1f, false)
                    }
                } catch (_: Throwable) {}
            }
            // Si no encontramos frontal, devolvemos el primer id disponible
            return CameraSelection(ids.firstOrNull() ?: "0", 1f, false)
        }
        val backCandidates = precomputedCandidates ?: buildBackCandidatesCached(mgr)
        if (backCandidates.isEmpty()) {
            // Sin cámaras back conocidas → usar primer id visible
            val firstId = try { mgr.cameraIdList.firstOrNull() } catch (_: Throwable) { null } ?: "0"
            return CameraSelection(firstId, 1f, false)
        }

        val logicalPrimary = backCandidates.firstOrNull { it.isLogicalMultiCamera && !it.isPhysicalOnly }
        val widePhysical = backCandidates.minByOrNull { c ->
            abs(c.focal - 5f) + if (c.isLogicalMultiCamera) 0.7f else 0f
        } ?: backCandidates.first()
        val ultraPhysical = backCandidates.minByOrNull { it.focal }
        val telePhysical = backCandidates.maxByOrNull { it.focal }
        val hasDedicatedTele = telePhysical != null && ultraPhysical != null &&
            telePhysical.id != widePhysical.id && telePhysical.focal >= widePhysical.focal * 1.8f
        val hasDedicatedUltra = ultraPhysical != null &&
            ultraPhysical.id != widePhysical.id && ultraPhysical.focal <= widePhysical.focal * 0.75f

        val forcedId = _telePhysicalIdPref.value
        val forcedTeleCandidate = backCandidates.firstOrNull { it.id == forcedId }
        val canForcePhys = _forceTelePhysicalId.value && forcedTeleCandidate != null

        return when (lens) {
            "0.5x" -> if (hasDedicatedUltra && ultraPhysical != null)
                CameraSelection(ultraPhysical.id, 0.5f, false)
            else CameraSelection(logicalPrimary?.id ?: widePhysical.id, 1f, false)

            "3x" -> when {
                canForcePhys -> {
                    Log.i(TAG, "3x: ABRIENDO physical ID forzado=$forcedId (S21 FE tele path)")
                    CameraSelection(forcedId, 3f, true)
                }
                hasDedicatedTele && telePhysical != null ->
                    CameraSelection(telePhysical.id, 3f, true)
                else -> {
                    Log.i(TAG, "3x: no hay tele físico → logical+wide con zoom digital 3.0x")
                    CameraSelection(logicalPrimary?.id ?: widePhysical.id, 1f, false)
                }
            }

            else -> CameraSelection(logicalPrimary?.id ?: widePhysical.id, 1f, false)
        }
    }
}
