package com.rodyto.lenspro

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rodyto.lenspro.camera.CameraSessionController
import com.rodyto.lenspro.settings.SettingsBridge
import com.rodyto.lenspro.settings.SettingsRepository
import com.rodyto.lenspro.ui.CameraUiStateHolder
import kotlinx.coroutines.flow.StateFlow

/**
 * CameraControlViewModel — Orquestador principal usando COMPOSICIÓN.
 * Refactor estructural de la Fase 3.
 */
class CameraControlViewModel(application: Application) : AndroidViewModel(application) {

    private val stateHolder = CameraUiStateHolder()
    private val sessionController = CameraSessionController(application, stateHolder)
    
    // Repositorio de ajustes
    private val settingsRepository = SettingsRepository(application)
    private val settingsBridge = SettingsBridge(settingsRepository, this, viewModelScope)

    // Exponer estados desde el stateHolder
    val uiState: StateFlow<CameraUiState> = stateHolder.uiState
    val sessionState: StateFlow<CameraSessionState> = stateHolder.sessionState
    val cameraMode: StateFlow<CameraMode> = stateHolder.cameraMode
    val isRecording: StateFlow<Boolean> = stateHolder.isRecording
    val flashMode: StateFlow<FlashMode> = MutableStateFlow(FlashMode.OFF) // Placeholder
    val timerSeconds: StateFlow<Int> = MutableStateFlow(0) // Placeholder
    val gridEnabled: StateFlow<Boolean> = MutableStateFlow(false) // Placeholder
    val isFlashSupported: StateFlow<Boolean> = MutableStateFlow(false) // Placeholder

    init {
        settingsBridge.wire()
    }

    fun takePhoto() {
        // Delegar a PhotoCapturePipeline (a implementar)
    }

    fun toggleRecording() {
        // Delegar a VideoCapturePipeline (a implementar)
    }

    fun switchCamera() {
        // Delegar a sessionController
    }

    fun setLens(lens: LensInfo?) {
        stateHolder.setCurrentLens(lens)
    }

    fun setCameraMode(mode: CameraMode) {
        stateHolder.setCameraMode(mode)
    }

    // Setters para SettingsBridge
    fun setGridEnabled(enabled: Boolean) { /* update state */ }
    fun setSoundEnabled(enabled: Boolean) { /* update state */ }
    fun setHapticsEnabled(enabled: Boolean) { /* update state */ }

    override fun onCleared() {
        super.onCleared()
        sessionController.closeCamera()
        sessionController.stopBackgroundThread()
    }
}

// Placeholder para evitar errores de compilación inmediatos en el StateFlow
private fun <T> MutableStateFlow(value: T): StateFlow<T> = kotlinx.coroutines.flow.MutableStateFlow(value)
