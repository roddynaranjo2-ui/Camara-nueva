package com.rodyto.lenspro

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.Surface
import android.hardware.camera2.CaptureRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rodyto.lenspro.camera.CameraSessionController
import com.rodyto.lenspro.settings.SettingsBridge
import com.rodyto.lenspro.settings.SettingsRepository
import com.rodyto.lenspro.ui.CameraUiStateHolder
import com.rodyto.lenspro.tuning.CameraTuning
import com.rodyto.lenspro.CameraConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * CameraControlViewModel — Orquestador principal usando COMPOSICIÓN.
 * Refactor estructural de la Fase 3.
 *
 * v4.4 — FIX CRÍTICO PANTALLA NEGRA:
 *  • discoverLenses() detecta las cámaras reales del dispositivo vía
 *    CameraManager y construye LensInfo con el cameraId correcto.
 *  • El ViewModel se inicializa con la lente trasera principal (id "0"
 *    como fallback seguro, id real si el HAL lo informa).
 *  • startCameraSession() ya no puede recibir id="" porque el id siempre
 *    proviene de discoverLenses().
 *  • isCameraIdValid() evita pasar IDs vacíos o inválidos al HAL.
 *
 * v4.3 — Corregidas referencias de paquetes y SettingsRepository.
 */
class CameraControlViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CameraVM"
        const val MAX_DIGITAL_ZOOM = 30f
    }

    private val stateHolder = CameraUiStateHolder()
    private val sessionController = CameraSessionController(application, stateHolder)

    // Repositorio de ajustes
    private val settingsRepository = SettingsRepository(application)
    private val settingsBridge = SettingsBridge(settingsRepository, this, viewModelScope)

    // ─── Estados desde stateHolder ───────────────────────────────
    val uiState: StateFlow<CameraUiState> = stateHolder.uiState
    val sessionState: StateFlow<CameraSessionState> = stateHolder.sessionState
    val cameraMode: StateFlow<CameraMode> = stateHolder.cameraMode
    val isRecording: StateFlow<Boolean> = stateHolder.isRecording
    val zoomLevel: StateFlow<Float> = stateHolder.zoomLevel
    val currentLens: StateFlow<LensInfo?> = stateHolder.currentLens
    val activeCountdown: StateFlow<Int> = stateHolder.activeCountdown
    val shutterBlinkKey: StateFlow<Int> = stateHolder.shutterBlinkKey

    // ─── Estados adicionales ─────────────────────────────────────
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
    private val _histogramBins = MutableStateFlow<IntArray?>(null)
    val histogramBins: StateFlow<IntArray?> = _histogramBins

    val isFlashSupported = MutableStateFlow(true)

    // FIX v4.4: Listas descubiertas de lentes reales
    private val _availableBackLenses = MutableStateFlow<List<LensInfo>>(emptyList())
    val availableBackLenses: StateFlow<List<LensInfo>> = _availableBackLenses

    private val _availableFrontLenses = MutableStateFlow<List<LensInfo>>(emptyList())
    val availableFrontLenses: StateFlow<List<LensInfo>> = _availableFrontLenses

    init {
        settingsBridge.wire()
        // FIX v4.4: Descubrir cámaras reales del dispositivo en background
        viewModelScope.launch(Dispatchers.IO) {
            discoverLenses(application)
        }
    }

    /* ─── FIX v4.4: Descubrimiento de cámaras reales ────────────── */

    /**
     * Consulta el CameraManager para obtener los IDs reales de cada cámara.
     * Construye LensInfo con id real, facing y focal length del HAL.
     * Inicializa currentLens con la primera cámara trasera.
     */
    private fun discoverLenses(context: Context) {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = manager.cameraIdList

            val backLenses = mutableListOf<LensInfo>()
            val frontLenses = mutableListOf<LensInfo>()

            for (id in ids) {
                try {
                    val chars = manager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                    val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    val focalLength = focalLengths?.firstOrNull() ?: 0f
                    val aperture = apertures?.firstOrNull() ?: 0f

                    val label = buildLensLabel(id, focalLength, facing, ids)
                    val lens = LensInfo(
                        id = id,
                        label = label,
                        focalLength = focalLength,
                        aperture = aperture,
                        isPhysical = false,
                        isOptical = focalLength > 0f
                    )

                    when (facing) {
                        CameraCharacteristics.LENS_FACING_BACK -> backLenses.add(lens)
                        CameraCharacteristics.LENS_FACING_FRONT -> frontLenses.add(lens)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error leyendo cámara id=$id", e)
                }
            }

            // Ordenar por focal length ascendente (gran angular primero)
            backLenses.sortBy { it.focalLength }
            frontLenses.sortBy { it.focalLength }

            _availableBackLenses.value = backLenses
            _availableFrontLenses.value = frontLenses

            Log.d(TAG, "Cámaras descubiertas: back=${backLenses.map { "${it.id}(${it.label})" }}" +
                       " front=${frontLenses.map { it.id }}")

            // Inicializar con la principal trasera (gran angular o id="0" como fallback)
            val initialLens = backLenses.find { it.id == "0" }
                ?: backLenses.firstOrNull()
                ?: LensInfo(id = "0", label = "1x")   // fallback absoluto

            if (stateHolder.currentLens.value == null) {
                stateHolder.setCurrentLens(initialLens)
            }

            // Verificar si el flash está disponible en la cámara principal
            try {
                val chars = manager.getCameraCharacteristics(initialLens.id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                isFlashSupported.value = hasFlash
            } catch (_: Exception) {}

        } catch (e: Exception) {
            Log.e(TAG, "Error descubriendo cámaras, usando fallback id=0", e)
            // Fallback seguro: siempre inicializar con algo válido
            if (stateHolder.currentLens.value == null) {
                stateHolder.setCurrentLens(LensInfo(id = "0", label = "1x"))
            }
        }
    }

    /**
     * Genera un label legible (.5x / 1x / 3x) basado en la focal length
     * relativa a la cámara principal (focal length más corta entre las traseras).
     */
    private fun buildLensLabel(
        id: String,
        focalLength: Float,
        facing: Int,
        allIds: Array<String>
    ): String {
        if (focalLength <= 0f) {
            return when (id) {
                "0" -> "1x"
                "1" -> if (facing == CameraCharacteristics.LENS_FACING_FRONT) "1x" else "2x"
                "2" -> "3x"
                else -> "${id}x"
            }
        }
        return when {
            focalLength < 2.5f  -> ".5x"
            focalLength < 4.5f  -> "1x"
            focalLength < 8f    -> "2x"
            focalLength < 15f   -> "3x"
            else                -> "5x"
        }
    }

    /* ─── FIX v4.4: Validación de ID antes de abrir HAL ─────────── */

    /**
     * Retorna true solo si el cameraId es un string no vacío que
     * está en la lista de IDs conocidos del CameraManager.
     * Evita pasar "" o IDs inválidos al HAL → pantalla negra.
     */
    private fun isCameraIdValid(cameraId: String): Boolean {
        if (cameraId.isBlank()) return false
        return try {
            val manager = getApplication<Application>()
                .getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.cameraIdList.contains(cameraId)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo verificar cameraId=$cameraId", e)
            true
        }
    }

    /* ─── ACCIONES DE CÁMARA ─────────────────────────────────────── */

    fun startCameraSession(context: Context, surface: Surface, lens: LensInfo?) {
        // FIX v4.4: Resolver el ID real. Si el lens tiene id vacío,
        // buscar en las listas descubiertas o usar "0" como fallback seguro.
        val rawId = lens?.id ?: ""
        val resolvedId = when {
            rawId.isNotBlank() && isCameraIdValid(rawId) -> rawId
            else -> {
                val discovered = if (isFrontCamera.value)
                    _availableFrontLenses.value
                else
                    _availableBackLenses.value
                val fallback = discovered.firstOrNull()?.id ?: "0"
                Log.w(TAG, "LensInfo.id inválido ('$rawId'), usando fallback: $fallback")
                fallback
            }
        }
        Log.d(TAG, "startCameraSession → cameraId=$resolvedId")
        sessionController.openCamera(resolvedId, surface)
    }

    fun closeCamera() {
        sessionController.closeCamera()
    }

    fun takePhoto() {
        sessionController.takePhoto {
            stateHolder.bumpShutterBlink()
        }
    }

    fun toggleRecording() {
        stateHolder.setRecording(!isRecording.value)
    }

    fun switchCamera() {
        isFrontCamera.value = !isFrontCamera.value
    }

    fun setLens(lens: LensInfo?) {
        stateHolder.setCurrentLens(lens)
    }

    fun setCameraMode(mode: CameraMode) {
        stateHolder.setCameraMode(mode)
    }

    fun applyRepeatingPreview() {
        val session = sessionController.captureSession ?: return
        val builder = sessionController.previewRequestBuilder ?: return

        try {
            val iso = manualIso.value
            val shutter = manualShutterNs.value

            if (iso > 0 && shutter != null && shutter > 0L) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            session.setRepeatingRequest(builder.build(), null, sessionController.backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando parámetros de preview", e)
        }
    }

    fun isCameraRunning(): Boolean {
        return sessionState.value == CameraSessionState.PREVIEWING ||
               sessionState.value == CameraSessionState.RECORDING ||
               sessionState.value == CameraSessionState.CAPTURING
    }

    fun notifyPreviewSize(width: Int, height: Int) {
        // Placeholder: el AutoFitSurfaceView gestiona su propio layout
    }

    fun getOptimalPreviewSize(): Pair<Int, Int> {
        val size = CameraTuning.pickOptimalPreviewSize(null, 1f / previewAspectRatio.value)
        return size.width to size.height
    }

    fun getZoomMinValue(): Float = CameraConstants.MIN_ZOOM_DEFAULT
    fun getZoomMaxValue(): Float = CameraConstants.MAX_ZOOM_DEFAULT

    fun setZoomContinuous(zoom: Float) {
        stateHolder.setZoomLevel(zoom)
    }

    fun publishHistogramBins(bins: IntArray) {
        _histogramBins.value = bins
    }

    fun setFlashMode(mode: FlashMode) { flashMode.value = mode }
    fun setTimerSeconds(seconds: Int) { timerSeconds.value = seconds }
    fun setGridEnabled(enabled: Boolean) { gridEnabled.value = enabled }
    fun setHapticsEnabled(enabled: Boolean) { hapticsEnabled.value = enabled }
    fun setSoundEnabled(enabled: Boolean) { soundEnabled.value = enabled }

    override fun onCleared() {
        super.onCleared()
        sessionController.release()
    }
}
