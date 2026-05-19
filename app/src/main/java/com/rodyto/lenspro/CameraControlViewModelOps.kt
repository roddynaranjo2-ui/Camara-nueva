package com.rodyto.rodyto_lens_pro

import android.app.Application
import android.hardware.camera2.CaptureRequest
import android.util.Log

/**
 * Archivo 4: Operaciones de Usuario y Control Final.
 * Esta es la clase que instanciarás en tu MainActivity.
 * Hereda de Core, que a su vez hereda de State.
 */
class CameraControlViewModelOps(application: Application) : CameraControlViewModelCore(application) {

    // --- ACCIONES DE DISPARO ---

    fun takePhoto() {
        if (cameraDevice == null || captureSession == null) return
        
        Log.d("RodytoLens", "Iniciando captura de fotografía...")
        // Aquí va tu lógica original de CaptureRequest.Builder para STILL_CAPTURE
    }

    fun toggleRecording() {
        val isRecording = uiState.value.isRecording
        if (isRecording) {
            stopVideo()
        } else {
            startVideo()
        }
    }

    private fun startVideo() {
        updateUiState { it.copy(isRecording = true) }
        // Lógica de MediaRecorder o Camera2 Video
    }

    private fun stopVideo() {
        updateUiState { it.copy(isRecording = false) }
    }

    // --- CONTROLES MANUALES ---

    fun setIso(value: Int) {
        manualIso.value = value
        saveSetting("pref_iso", value)
        applyManualSettings()
    }

    fun setShutterSpeed(value: Long) {
        manualShutter.value = value
        saveSetting("pref_shutter", value)
        applyManualSettings()
    }

    fun setZoom(level: Float) {
        updateUiState { it.copy(zoomLevel = level) }
        // Lógica para actualizar el rect de la cámara (Crop Region)
    }

    /**
     * Aplica los cambios de ISO, Shutter, etc., a la sesión activa
     * sin necesidad de reiniciar la cámara.
     */
    private fun applyManualSettings() {
        try {
            previewRequestBuilder?.let { builder ->
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso.value)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualShutter.value)
                
                captureSession?.setRepeatingRequest(builder.build(), null, null)
            }
        } catch (e: Exception) {
            Log.e("RodytoLens", "Error aplicando ajustes manuales: ${e.message}")
        }
    }

    // --- HELPERS ---

    fun switchCamera() {
        // Lógica para alternar entre IDs de cámara (Front/Back/UltraWide)
        closeCamera()
        // Aquí llamarías a openCamera con el nuevo ID
    }
}
