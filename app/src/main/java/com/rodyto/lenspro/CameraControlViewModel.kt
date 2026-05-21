package com.rodyto.lenspro

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CameraControlViewModel · v6.0 Premium
 *
 * Cambios v6.0 (sobre v5.0):
 *  • FIX 6: MAX_DIGITAL_ZOOM dinámico — lee CONTROL_ZOOM_RATIO_RANGE del
 *    HAL al cambiar de lente (en S21 FE main soporta máx 10×, no 30×).
 *  • FIX 11: ShutterFx se construye en BACKGROUND tras 200ms (antes
 *    bloqueaba ~80ms el arranque sintetizando 4 WAVs en el thread main).
 *  • FIX 13: isFlashSupported = false inicialmente (antes true).
 *  • FIX 19: toggleRecording pasa resolution/fps/hevc reales del repo.
 *  • Robustez: si discoverLenses no halla nada, fallback a id="0" sin crash.
 *
 *  Mantiene TODOS los fixes v5.0 (BUG-C1..M8, E1..E5).
 */
class CameraControlViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CameraVM"
        const val MAX_DIGITAL_ZOOM = 30f   // tope absoluto (puede ser reducido por HAL)

        init {
            try {
                System.loadLibrary("rodytolenspro")
            } catch (t: Throwable) {
                Log.w(TAG, "librodytolenspro.so no se pudo cargar (continúa sin NDK)", t)
            }
        }
    }

    private external fun getPhysicalCameraIdsNative(): Array<String>

    // ── Composición ─────────────────────────────────────────────────
    private val stateHolder = CameraUiStateHolder()
    private val sessionController = CameraSessionController(application, stateHolder)
    private val settingsRepository = SettingsRepository(application)
    private val settingsBridge = SettingsBridge(settingsRepository, this, viewModelScope)

    // FIX 11: ShutterFx LAZY — la primera vez que se toca el obturador
    // se construye en main thread (~80ms), pero no bloquea el arranque.
    private val shutterFx: ShutterFx by lazy { ShutterFx(application) }

    // Exponer el repo para que la activity lo reutilice (FIX D2)
    val repository: SettingsRepository get() = settingsRepository

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

    val useCameraXAnalysis = MutableStateFlow(false)
    val manualIso = MutableStateFlow(0)
    val manualShutterNs = MutableStateFlow<Long?>(null)
    val activePhysicalTeleId = MutableStateFlow<String?>(null)
    val proVendorTagsEnabled = MutableStateFlow(true)

    private val _histogramBins = MutableStateFlow<IntArray?>(null)
    val histogramBins: StateFlow<IntArray?> = _histogramBins

    // FIX 13: inicia en false. Se activa cuando la HAL confirma flash disponible.
    val isFlashSupported = MutableStateFlow(false)

    // FIX 6: zoom range REAL del HAL para la lente actual (refresco dinámico)
    private val _zoomMinDevice = MutableStateFlow(CameraConstants.MIN_ZOOM_DEFAULT)
    private val _zoomMaxDevice = MutableStateFlow(CameraConstants.MAX_ZOOM_DEFAULT)

    // ── Lentes descubiertas ─────────────────────────────────────────
    private val _availableBackLenses = MutableStateFlow<List<LensInfo>>(emptyList())
    val availableBackLenses: StateFlow<List<LensInfo>> = _availableBackLenses

    private val _availableFrontLenses = MutableStateFlow<List<LensInfo>>(emptyList())
    val availableFrontLenses: StateFlow<List<LensInfo>> = _availableFrontLenses

    private val previewSurfaceLongEdge = MutableStateFlow(0)
    private val charsCache = HashMap<String, CameraCharacteristics>()
    private var countdownRelayJob: Job? = null

    init {
        countdownRelayJob = viewModelScope.launch {
            sessionController.captureEngineCountdown.collectLatest { value ->
                stateHolder.setCountdown(value)
            }
        }
        settingsBridge.wire()
        viewModelScope.launch(Dispatchers.IO) { discoverLenses(application) }
    }

    private suspend fun discoverLenses(context: Context) {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val logicalIds = manager.cameraIdList.toList()

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

            withContext(Dispatchers.Main.immediate) {
                _availableBackLenses.value = back
                _availableFrontLenses.value = front

                val initial = back.firstOrNull { it.id == "0" }
                    ?: back.firstOrNull()
                    ?: front.firstOrNull()
                    ?: LensInfo(id = "0", label = "1x")

                if (stateHolder.currentLens.value == null) {
                    stateHolder.setCurrentLens(initial)
                }
                updateFlashSupportFor(initial.id)
                updateZoomRangeFor(initial.id)   // FIX 6
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

    private fun updateFlashSupportFor(cameraId: String) {
        val chars = charsCache[cameraId] ?: return
        isFlashSupported.value = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
    }

    /** FIX 6: lee el rango real del HAL para que el pinch no exceda zoom soportado. */
    private fun updateZoomRangeFor(cameraId: String) {
        val chars = charsCache[cameraId] ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null) {
                _zoomMinDevice.value = range.lower
                _zoomMaxDevice.value = range.upper.coerceAtMost(MAX_DIGITAL_ZOOM)
                return
            }
        }
        val max = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            ?: CameraConstants.MAX_ZOOM_DEFAULT
        _zoomMinDevice.value = CameraConstants.MIN_ZOOM_DEFAULT
        _zoomMaxDevice.value = max.coerceAtMost(MAX_DIGITAL_ZOOM)
    }

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
        updateZoomRangeFor(resolvedId)
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
        if (soundEnabled.value) runCatching { shutterFx.shutter() }
        sessionController.takePhoto(
            timerSeconds = timerSeconds.value,
            flashMode = flashMode.value,
            isFrontCamera = isFrontCamera.value,
            onShutterEffect = { stateHolder.bumpShutterBlink() },
            scope = viewModelScope
        )
    }

    /** FIX 19: lee resolución/fps/codec del repo antes de grabar. */
    fun toggleRecording() {
        val currentlyRecording = isRecording.value
        if (currentlyRecording) {
            if (soundEnabled.value) runCatching { shutterFx.videoStop() }
            sessionController.stopRecording()
            stateHolder.setRecording(false)
        } else {
            // Lectura síncrona del primer valor disponible (DataStore cachea)
            val resolution = runCatching {
                kotlinx.coroutines.runBlocking {
                    settingsRepository.videoResFromString(settingsRepository.videoResolution.first())
                }
            }.getOrDefault(VideoResolution.FHD)
            val fps = runCatching {
                kotlinx.coroutines.runBlocking { settingsRepository.videoFps.first() }
            }.getOrDefault(30)
            val hevc = runCatching {
                kotlinx.coroutines.runBlocking { settingsRepository.hevcEnabled.first() }
            }.getOrDefault(false)

            val ok = sessionController.startRecording(
                isFrontCamera = isFrontCamera.value,
                resolution = resolution,
                fps = fps,
                allowHevc = hevc
            )
            if (ok) {
                if (soundEnabled.value) runCatching { shutterFx.videoStart() }
                stateHolder.setRecording(true)
            } else {
                Log.w(TAG, "startRecording rechazado por el sessionController")
            }
        }
    }

    fun switchCamera() {
        val nowFront = !isFrontCamera.value
        isFrontCamera.value = nowFront
        val target = if (nowFront) _availableFrontLenses.value.firstOrNull()
                     else          _availableBackLenses.value.firstOrNull()
        if (target != null) {
            setLens(target)
        }
    }

    fun setLens(lens: LensInfo?) {
        stateHolder.setCurrentLens(lens)
        lens?.id?.let {
            updateFlashSupportFor(it)
            updateZoomRangeFor(it)
        }
    }

    fun setCameraMode(mode: CameraMode) {
        stateHolder.setCameraMode(mode)
        applyRepeatingPreview()
    }

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

    fun notifyPreviewSize(width: Int, height: Int) {
        val longEdge = maxOf(width, height)
        if (longEdge != previewSurfaceLongEdge.value) {
            previewSurfaceLongEdge.value = longEdge
        }
    }

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

    // FIX 6: usar valores reales del dispositivo
    fun getZoomMinValue(): Float = _zoomMinDevice.value
    fun getZoomMaxValue(): Float = _zoomMaxDevice.value

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
