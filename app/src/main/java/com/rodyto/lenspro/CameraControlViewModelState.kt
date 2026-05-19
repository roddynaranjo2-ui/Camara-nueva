package com.rodyto.rodyto_lens_pro

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Archivo 2: Gestión de Estados y Persistencia.
 * Mantiene la integridad de los datos reactivos de Rodyto Lens Pro.
 */

open class CameraControlViewModelState(application: Application) : AndroidViewModel(application) {

    protected val sharedPreferences = application.getSharedPreferences("rodyto_lens_settings", Context.MODE_PRIVATE)

    // Estado principal de la UI
    protected val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    // Estados de Control Manual (Pro)
    val manualIso = MutableStateFlow(sharedPreferences.getInt("pref_iso", 100))
    val manualShutter = MutableStateFlow(sharedPreferences.getLong("pref_shutter", 1_000_000L))
    val manualFocus = MutableStateFlow(sharedPreferences.getFloat("pref_focus", 0f))
    val manualWB = MutableStateFlow(sharedPreferences.getInt("pref_wb", 0))

    // Estados de Hardware
    protected var cameraDevice: android.hardware.camera2.CameraDevice? = null
    protected var captureSession: android.hardware.camera2.CameraCaptureSession? = null
    protected var previewRequestBuilder: android.hardware.camera2.CaptureRequest.Builder? = null
    
    // Lista de sensores físicos detectados (ID 52, etc)
    protected var availablePhysicalCameras = mutableListOf<String>()
    
    // Flags de control
    var isFlashSupported = MutableStateFlow(false)
    var isRawSupported = MutableStateFlow(false)

    // Función para actualizar el estado de forma segura sin borrar datos
    protected fun updateUiState(reducer: (CameraUiState) -> CameraUiState) {
        _uiState.update(reducer)
    }

    // Persistencia de ajustes
    protected fun saveSetting(key: String, value: Any) {
        with(sharedPreferences.edit()) {
            when (value) {
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
            }
            apply()
        }
    }
}
