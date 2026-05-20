package com.rodyto.lenspro

import android.app.Application
import android.content.Context
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rodyto.lenspro.camera.CameraSessionController
import com.rodyto.lenspro.settings.SettingsBridge
import com.rodyto.lenspro.ui.CameraUiStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * CameraControlViewModel — Orquestador principal usando COMPOSICIÓN.
 * Refactor estructural de la Fase 3.
 * v4.3 — Corregidas referencias de paquetes y SettingsRepository.
 */
class CameraControlViewModel(application: Application) : AndroidViewModel(application) {

    private val stateHolder = CameraUiStateHolder()
    private val sessionController = CameraSessionController(application, stateHolder)
    
    // Repositorio de ajustes (está en el mismo paquete com.rodyto.lenspro)
    private val settingsRepository = SettingsRepository(application)
    private val settingsBridge = SettingsBridge(settingsRepository, this, viewModelScope)

    // Exponer estados desde el stateHolder
    val uiState: StateFlow<CameraUiState> = stateHolder.uiState
    val sessionState: StateFlow<CameraSessionState> = stateHolder.sessionState
    val cameraMode: StateFlow<CameraMode> = stateHolder.cameraMode
    val isRecording: StateFlow<Boolean> = stateHolder.isRecording
    val zoomLevel: StateFlow<Float> = stateHolder.zoomLevel
    val currentLens: StateFlow<LensInfo?> = stateHolder.currentLens
    val activeCountdown: StateFlow<Int> = stateHolder.activeCountdown
    val shutterBlinkKey: StateFlow<Int> = stateHolder.shutterBlinkKey

    // Estados adicionales (Bridges)
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

    val isFlashSupported = MutableStateFlow(true) // Placeholder

    init {
        settingsBridge.wire()
    }

    /* ─── ACCIONES DE CÁMARA ─────────────────────────────────── */

    fun startCameraSession(context: Context, surface: Surface, lens: LensInfo?) {
        sessionController.openCamera(lens?.id ?: "0")
    }

    fun closeCamera() {
        sessionController.closeCamera()
    }

    fun takePhoto() {
        stateHolder.bumpShutterBlink()
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
        // Aplicar parámetros actuales al HAL
    }

    fun isCameraRunning(): Boolean {
        return sessionState.value == CameraSessionState.PREVIEWING ||
               sessionState.value == CameraSessionState.RECORDING ||
               sessionState.value == CameraSessionState.CAPTURING
    }

    fun notifyPreviewSize(width: Int, height: Int) {
        // Notificar al controlador de sesión
    }

    fun getOptimalPreviewSize(): Pair<Int, Int> {
        return 1920 to 1080
    }

    fun getZoomMinValue(): Float = CameraConstants.MIN_ZOOM_DEFAULT
    fun getZoomMaxValue(): Float = CameraConstants.MAX_ZOOM_DEFAULT

    fun setZoomContinuous(zoom: Float) {
        stateHolder.setZoomLevel(zoom)
    }

    fun publishHistogramBins(bins: IntArray) {
        _histogramBins.value = bins
    }

    // Setters para SettingsBridge / UI
    fun setFlashMode(mode: FlashMode) { flashMode.value = mode }
    fun setTimerSeconds(seconds: Int) { timerSeconds.value = seconds }
    fun setGridEnabled(enabled: Boolean) { gridEnabled.value = enabled }
    fun setHapticsEnabled(enabled: Boolean) { hapticsEnabled.value = enabled }
    fun setSoundEnabled(enabled: Boolean) { soundEnabled.value = enabled }

    override fun onCleared() {
        super.onCleared()
        sessionController.closeCamera()
        sessionController.stopBackgroundThread()
    }

    companion object {
        const val MAX_DIGITAL_ZOOM = 30f
    }
}
