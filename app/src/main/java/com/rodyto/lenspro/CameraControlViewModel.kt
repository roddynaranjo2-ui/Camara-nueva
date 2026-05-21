package com.rodyto.lenspro

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rodyto.lenspro.camera.CameraSessionController
import com.rodyto.lenspro.settings.SettingsBridge
import com.rodyto.lenspro.settings.SettingsRepository
import com.rodyto.lenspro.tuning.CameraTuning
import com.rodyto.lenspro.ui.CameraUiStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CameraControlViewModel · v5.0 Premium
 *
 * Cambios v5.0 (sobre v4.4):
 *  • FIX BUG-C3: System.loadLibrary("rodytolenspro") + external fun
 *    getPhysicalCameraIdsNative() — el binding JNI ahora ESTÁ activo.
 *    Los ids físicos del NDK se mergean con el cameraIdList lógico para
 *    descubrir teleobjetivos ocultos en multi-camera HALs.
 *  • FIX BUG-C2: countdown del CaptureEngine se redirige al stateHolder
 *    vía coroutine compartida (mientras la VM viva).
 *  • FIX BUG-M1: grabación REAL vía VideoRecordingController (MediaRecorder).
 *  • FIX BUG-M2: ShutterFx instanciado y disparado en cada takePhoto.
 *  • FIX BUG-M8: pickOptimalPreviewSize ahora recibe CameraCharacteristics
 *    reales del cameraId actual.
 *  • FIX BUG-E1: setZoomContinuous aplica CONTROL_ZOOM_RATIO (API 30+) o
 *    SCALER_CROP_REGION como fallback. El pinch zoomea de verdad.
 *  • FIX BUG-E2: switchCamera ahora selecciona una lente frontal válida y
 *    fuerza setLens(...) para que CameraPreview restablezca la sesión.
 *  • FIX BUG-E4: isFlashSupported se RECALCULA al cambiar de cámara.
 *  • FIX BUG-E5: applyRepeatingPreview aplica AF_MODE CONTINUOUS_*.
 *  • FIX BUG-B4: las asignaciones de StateFlow vuelven a Main.
 */
class CameraControlViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CameraVM"
        const val MAX_DIGITAL_ZOOM = 30f

        // ── BUG-C3: carga del .so producido por CMakeLists (libname=rodytolenspro) ──
        init {
            try {
                System.loadLibrary("rodytolenspro")
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudo cargar libdotr rodytolenspro.so (continuará sin NDK)", t)
            }
        }
    }

    // ── BUG-C3: declaración del binding JNI. Si la librería no carga, la
    //   llamada lanza UnsatisfiedLinkError que capturamos en discoverLenses().
    private external fun getPhysicalCameraIdsNative(): Array<String>

    // ── Composición ─────────────────────────────────────────────────
    private val stateHolder = CameraUiStateHolder()
    private val sessionController = CameraSessionController(application, stateHolder)
    private val settingsRepository = SettingsRepository(application)
    private val settingsBridge = SettingsBridge(settingsRepository, this, viewModelScope)
    private val shutterFx by lazy { ShutterFx(application) }   // BUG-M2

    // ── Estados desde stateHolder ───────────────────────────────────
    val uiState: StateFlow<CameraUiState> = stateHolder.uiState
    val sessionState: StateFlow<CameraSessionState> = stateHolder.sessionState
    val cameraMode: StateFlow<CameraMode> = stateHolder.cameraMode
    val isRecording: StateFlow<Boolean> = stateHolder.isRecording
    val zoomLevel: StateFlow<Float> = stateHolder.zoomLevel
    val currentLens: StateFlow<LensInfo?> = stateHolder.currentLens
    val activeCountdown: StateFlow<Int> = stateHolder.activeCountdown
    val shutterBlinkKey: StateFlow<Int> = stateHolder.shutterBlinkKey

    // ── Estados de ajustes (sincronizados por SettingsBridge) ───────
    val flashMode = MutableStateFlow(FlashMode.OFF)
    val timerSeconds = MutableStateFlow(0)
    val gridEnabled = MutableStateFlow(false)
    val hapticsEnabled = MutableStateFlow(true)
    val soundEnabled = MutableStateFlow(true)
    val isFrontCamera = MutableStateFlow(false)
    val previewAspectRatio = MutableStateFlow(3f / 4f)

    // Pro / CameraX Bridge
    val useCameraXAnalysis = MutableStateFlow(false)
    val manualIso = MutableStateFlow(0)
    val manualShutterNs = MutableStateFlow<Long?>(null)
    val activePhysicalTeleId = MutableStateFlow<String?>(null)
    val proVendorTagsEnabled = MutableStateFlow(true)

    private val _histogramBins = MutableStateFlow<IntArray?>(null)
    val histogramBins: StateFlow<IntArray?> = _histogramBins

    val isFlashSupported = MutableStateFlow(true)

    // ── Lentes descubiertas ─────────────────────────────────────────
    private val _availableBackLenses = MutableStateFlow<List<LensInfo>>(emptyList())
    val availableBackLenses: StateFlow<List<LensInfo>> = _availableBackLenses

    private val _availableFrontLenses = MutableStateFlow<List<LensInfo>>(emptyList())
    val availableFrontLenses: StateFlow<List<LensInfo>> = _availableFrontLenses

    // Tamaño del SurfaceView (BUG-K3 / BUG-M8)
    private val previewSurfaceLongEdge = MutableStateFlow(0)

    // Lookup de characteristics (cacheado por cameraId)
    private val charsCache = HashMap<String, CameraCharacteristics>()

    // Job que retransmite el countdown del CaptureEngine al stateHolder (BUG-C2)
    private var countdownRelayJob: Job? = null

    init {
        // BUG-C2: redirigir countdown del motor al StateHolder
        countdownRelayJob = viewModelScope.launch {
            sessionController.captureEngineCountdown.collectLatest { value ->
                stateHolder.setCountdown(value)
            }
        }
        settingsBridge.wire()
        viewModelScope.launch(Dispatchers.IO) { discoverLenses(application) }
    }

    /* ────────────────────────────────────────────────────────────────
     *  Descubrimiento de lentes (HAL lógico + NDK físico mergeados)
     * ──────────────────────────────────────────────────────────────── */
    private suspend fun discoverLenses(context: Context) {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val logicalIds = manager.cameraIdList.toList()

            // BUG-C3: ids físicos NDK (Samsung S21 FE / Pixel 7 / etc.)
            val physicalIds: List<String> = try {
                getPhysicalCameraIdsNative().toList()
            } catch (_: UnsatisfiedLinkError) { emptyList() }
              catch (t: Throwable) { Log.w(TAG, "getPhysicalCameraIdsNative falló", t); emptyList() }

            val allIds = (logicalIds + physicalIds).distinct()

            val back = mutableListOf<LensInfo>()
            val front = mutableListOf<LensInfo>()

            for (id in allIds) {
                try {
                    val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
                    charsCache[id] = chars
                    val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                    val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val aps    = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    val focal  = focals?.firstOrNull() ?: 0f
                    val aper   = aps?.firstOrNull() ?: 0f
                    val isPhysical = id in physicalIds && id !in logicalIds
                    val lens = LensInfo(
                        id = id,
                        label = buildLensLabel(id, focal, facing),
                        focalLength = focal,
                        aperture = aper,
                        isPhysical = isPhysical,
                        isOptical = focal > 0f
                    )
                    when (facing) {
                        CameraCharacteristics.LENS_FACING_BACK  -> back.add(lens)
                        CameraCharacteristics.LENS_FACING_FRONT -> front.add(lens)
                    }
                } catch (e: Exception) { Log.w(TAG, "Cámara $id descartada", e) }
            }

            back.sortBy  { it.focalLength }
            front.sortBy { it.focalLength }

            // BUG-B4: asignaciones de StateFlow en Main
            withContext(Dispatchers.Main.immediate) {
                _availableBackLenses.value = back
                _availableFrontLenses.value = front

                val initial = back.firstOrNull { it.id == "0" }
                    ?: back.firstOrNull()
                    ?: LensInfo(id = "0", label = "1x")

                if (stateHolder.currentLens.value == null) {
                    stateHolder.setCurrentLens(initial)
                }
                updateFlashSupportFor(initial.id)
                Log.d(TAG, "Lentes: back=${back.map { "${it.id}/${it.label}" }} front=${front.map { it.id }}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "discoverLenses falló — fallback id=0", t)
            withContext(Dispatchers.Main.immediate) {
                if (stateHolder.currentLens.value == null) {
                    stateHolder.setCurrentLens(LensInfo(id = "0", label = "1x"))
                }
            }
        }
    }

    private fun buildLensLabel(id: String, focal: Float, facing: Int): String {
        if (focal <= 0f) return when (id) {
            "0"  -> "1x"
            "1"  -> if (facing == CameraCharacteristics.LENS_FACING_FRONT) "1x" else "2x"
            "2"  -> "3x"
            else -> "${id}x"
        }
        return when {
            focal < 2.5f -> ".5x"
            focal < 4.5f -> "1x"
            focal < 8f   -> "2x"
            focal < 15f  -> "3x"
            else         -> "5x"
        }
    }

    /** BUG-E4: actualiza flashSupported cuando cambia la lente activa. */
    private fun updateFlashSupportFor(cameraId: String) {
        val chars = charsCache[cameraId] ?: return
        isFlashSupported.value = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
    }

    /* ────────────────────────────────────────────────────────────────
     *  ACCIONES de cámara
     * ──────────────────────────────────────────────────────────────── */
    fun startCameraSession(context: Context, surface: Surface, lens: LensInfo?) {
        val rawId = lens?.id ?: ""
        val resolvedId = when {
            rawId.isNotBlank() && isCameraIdValid(rawId) -> rawId
            else -> {
                val list = if (isFrontCamera.value) _availableFrontLenses.value else _availableBackLenses.value
                list.firstOrNull()?.id ?: "0"
            }
        }
        Log.d(TAG, "startCameraSession → cameraId=$resolvedId")
        val chars = charsCache[resolvedId]
        updateFlashSupportFor(resolvedId)
        sessionController.openCamera(
            cameraId = resolvedId,
            previewSurface = surface,
            characteristics = chars,
            previewAspect = previewAspectRatio.value,
            longEdgeHint = previewSurfaceLongEdge.value,
            proVendorTagsEnabled = proVendorTagsEnabled.value,
            cameraMode = cameraMode.value
        )
    }

    private fun isCameraIdValid(cameraId: String): Boolean {
        if (cameraId.isBlank()) return false
        return try {
            val m = getApplication<Application>().getSystemService(Context.CAMERA_SERVICE) as CameraManager
            m.cameraIdList.contains(cameraId)
        } catch (_: Exception) { true }
    }

    fun closeCamera() { sessionController.closeCamera() }

    fun takePhoto() {
        // BUG-M2: feedback de obturador real
        if (soundEnabled.value) runCatching { shutterFx.shutter() }
        sessionController.takePhoto(
            timerSeconds = timerSeconds.value,
            flashMode = flashMode.value,
            isFrontCamera = isFrontCamera.value,
            onShutterEffect = { stateHolder.bumpShutterBlink() },
            scope = viewModelScope                      // BUG-C1
        )
    }

    /** BUG-M1: alterna grabación REAL. */
    fun toggleRecording() {
        val currentlyRecording = isRecording.value
        if (currentlyRecording) {
            if (soundEnabled.value) runCatching { shutterFx.videoStop() }
            sessionController.stopRecording()
            stateHolder.setRecording(false)
        } else {
            val ok = sessionController.startRecording(
                isFrontCamera = isFrontCamera.value
            )
            if (ok) {
                if (soundEnabled.value) runCatching { shutterFx.videoStart() }
                stateHolder.setRecording(true)
            } else {
                Log.w(TAG, "startRecording rechazado por el sessionController")
            }
        }
    }

    /** BUG-E2: al voltear cámara, selecciona lente válida del set correspondiente. */
    fun switchCamera() {
        val nowFront = !isFrontCamera.value
        isFrontCamera.value = nowFront
        val target = if (nowFront) _availableFrontLenses.value.firstOrNull()
                     else          _availableBackLenses.value.firstOrNull()
        if (target != null) {
            setLens(target)        // dispara LaunchedEffect en CameraPreview
        }
    }

    fun setLens(lens: LensInfo?) {
        stateHolder.setCurrentLens(lens)
        lens?.id?.let { updateFlashSupportFor(it) }
    }

    fun setCameraMode(mode: CameraMode) {
        stateHolder.setCameraMode(mode)
        // Re-aplicar el preview con AE/AF acordes al nuevo modo
        applyRepeatingPreview()
    }

    /** BUG-E5: refuerza AF y aplica vendor tags Samsung si procede. */
    fun applyRepeatingPreview() {
        sessionController.applyManualParams(
            manualIso = manualIso.value,
            manualShutterNs = manualShutterNs.value,
            cameraMode = cameraMode.value,
            isRecording = isRecording.value,
            proVendorTagsEnabled = proVendorTagsEnabled.value
        )
    }

    fun isCameraRunning(): Boolean = sessionState.value in setOf(
        CameraSessionState.PREVIEWING,
        CameraSessionState.RECORDING,
        CameraSessionState.CAPTURING
    )

    /** BUG-K3: ahora SÍ tiene uso — almacena el long-edge para pickOptimalPreviewSize. */
    fun notifyPreviewSize(width: Int, height: Int) {
        val longEdge = maxOf(width, height)
        if (longEdge != previewSurfaceLongEdge.value) {
            previewSurfaceLongEdge.value = longEdge
        }
    }

    /** BUG-M8: ahora usa characteristics reales del cameraId actual. */
    fun getOptimalPreviewSize(): Pair<Int, Int> {
        val id = currentLens.value?.id
        val chars = id?.let { charsCache[it] }
        val targetRatioForTuning = 1f / previewAspectRatio.value
        val size = CameraTuning.pickOptimalPreviewSize(
            characteristics = chars,
            targetRatio = targetRatioForTuning,
            displayLongEdgePx = previewSurfaceLongEdge.value
        )
        return size.width to size.height
    }

    fun getZoomMinValue(): Float = CameraConstants.MIN_ZOOM_DEFAULT
    fun getZoomMaxValue(): Float = CameraConstants.MAX_ZOOM_DEFAULT

    /** BUG-E1: el zoom se APLICA al CaptureRequest, no solo se guarda. */
    fun setZoomContinuous(zoom: Float) {
        val clamped = zoom.coerceIn(getZoomMinValue(), getZoomMaxValue())
        stateHolder.setZoomLevel(clamped)
        val id = currentLens.value?.id ?: return
        val chars = charsCache[id] ?: return
        sessionController.applyZoom(clamped, chars)
    }

    fun publishHistogramBins(bins: IntArray) { _histogramBins.value = bins }

    fun setFlashMode(mode: FlashMode)         { flashMode.value = mode }
    fun setTimerSeconds(seconds: Int)         { timerSeconds.value = seconds }
    fun setGridEnabled(enabled: Boolean)      { gridEnabled.value = enabled }
    fun setHapticsEnabled(enabled: Boolean)   { hapticsEnabled.value = enabled }
    fun setSoundEnabled(enabled: Boolean)     { soundEnabled.value = enabled }
    fun setProVendorTags(enabled: Boolean)    { proVendorTagsEnabled.value = enabled }

    override fun onCleared() {
        super.onCleared()
        countdownRelayJob?.cancel()
        sessionController.release()
        runCatching { shutterFx.release() }
    }
}
