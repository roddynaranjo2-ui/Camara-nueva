package com.rodyto.lenspro.ui

import com.rodyto.lenspro.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * CameraUiStateHolder — Centraliza el estado de la UI para Compose.
 * Parte del refactor de composición de la Fase 3.
 */
class CameraUiStateHolder {
    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _sessionState = MutableStateFlow(CameraSessionState.IDLE)
    val sessionState: StateFlow<CameraSessionState> = _sessionState.asStateFlow()

    private val _cameraMode = MutableStateFlow(CameraMode.PHOTO)
    val cameraMode: StateFlow<CameraMode> = _cameraMode.asStateFlow()

    private val _zoomLevel = MutableStateFlow(1f)
    val zoomLevel: StateFlow<Float> = _zoomLevel.asStateFlow()

    private val _currentLens = MutableStateFlow<LensInfo?>(null)
    val currentLens: StateFlow<LensInfo?> = _currentLens.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _activeCountdown = MutableStateFlow(0)
    val activeCountdown: StateFlow<Int> = _activeCountdown.asStateFlow()

    private val _shutterBlinkKey = MutableStateFlow(0)
    val shutterBlinkKey: StateFlow<Int> = _shutterBlinkKey.asStateFlow()

    fun updateUiState(reducer: (CameraUiState) -> CameraUiState) {
        _uiState.update(reducer)
    }

    fun setSessionState(state: CameraSessionState) {
        _sessionState.value = state
        updateUiState { it.copy(isCameraReady = state == CameraSessionState.PREVIEWING) }
    }

    fun setCameraMode(mode: CameraMode) {
        _cameraMode.value = mode
        updateUiState { it.copy(currentMode = mode) }
    }

    fun setZoomLevel(level: Float) {
        _zoomLevel.value = level
    }

    fun setCurrentLens(lens: LensInfo?) {
        _currentLens.value = lens
        updateUiState { it.copy(currentLens = lens) }
    }

    fun setRecording(recording: Boolean) {
        _isRecording.value = recording
        updateUiState { it.copy(isRecording = recording) }
    }

    fun setCountdown(value: Int) {
        _activeCountdown.value = value
    }

    fun bumpShutterBlink() {
        _shutterBlinkKey.update { c -> if (c >= Int.MAX_VALUE - 1) 1 else c + 1 }
    }
}
