package com.rodyto.lenspro

import android.app.Application
import android.hardware.camera2.CaptureRequest
import android.util.Log

/* ================================================================
 *  Rodyto Lens Pro · CameraControlViewModelOps.kt · v3.6 Pro
 *
 *  Archivo 4 de la división. Lógica de usuario / disparos:
 *    • takePhoto / toggleRecording
 *    • Controles manuales (ISO, shutter, focus, WB)
 *    • Modo de cámara, switch front/back
 *    • Aplicación de manual settings al repeating preview
 *
 *  CORRECCIONES respecto al esqueleto v3.5:
 *    • applyManualSettings() ahora envía la request con el HANDLER
 *      DE FONDO (FIX B-07: antes se ejecutaba en hilo de UI).
 *    • setIso/setShutter ya NO mutan el valor sumando ciegamente
 *      al estado previo desde la UI (eso era responsabilidad del
 *      slider). Aquí solo se asigna y se aplica.
 *    • toggleRecording delega en startVideo/stopVideo y actualiza
 *      sessionState a RECORDING.
 *    • Todas las acciones requieren cámara abierta — early-return
 *      con log silencioso si no.
 * ================================================================ */
open class CameraControlViewModelOps(application: Application) :
    CameraControlViewModelCore(application) {

    companion object { private const val TAG = "CameraVMOps" }

    /* ─── ACCIONES DE DISPARO ─────────────────────────────────── */

    open fun takePhoto() {
        if (cameraDevice == null || captureSession == null) {
            Log.w(TAG, "takePhoto: ignorado (cámara no lista)")
            return
        }
        _sessionState.value = CameraSessionState.CAPTURING
        // La clase final reemplaza este método para construir el
        // CaptureRequest STILL_CAPTURE con vendor tags + ImageReader.
        Log.d(TAG, "takePhoto: solicitud emitida (placeholder)")
        // Restaurar preview cuando el caller confirma la captura.
        _sessionState.value = CameraSessionState.PREVIEWING
    }

    open fun toggleRecording() {
        if (_isRecording.value) stopVideo() else startVideo()
    }

    protected open fun startVideo() {
        if (cameraDevice == null) {
            Log.w(TAG, "startVideo: ignorado (cámara no lista)")
            return
        }
        _isRecording.value = true
        _sessionState.value = CameraSessionState.RECORDING
        updateUiState { it.copy(isRecording = true) }
    }

    protected open fun stopVideo() {
        _isRecording.value = false
        _sessionState.value = CameraSessionState.PREVIEWING
        updateUiState { it.copy(isRecording = false) }
    }

    /* ─── CONTROLES MANUALES ──────────────────────────────────── */

    fun setIso(value: Int) {
        manualIso.value = value.coerceAtLeast(0)
        saveSetting("pref_iso", manualIso.value)
        applyManualSettings()
    }

    fun setShutterSpeed(valueNs: Long) {
        manualShutter.value = valueNs.coerceAtLeast(0L)
        manualShutterNs.value = if (valueNs > 0L) valueNs else null
        saveSetting("pref_shutter", manualShutter.value)
        saveSetting("pref_shutter_ns", valueNs)
        applyManualSettings()
    }

    fun setFocusDistance(value: Float) {
        manualFocus.value = value.coerceAtLeast(0f)
        saveSetting("pref_focus", manualFocus.value)
        applyManualSettings()
    }

    fun setExposureCompensation(ev: Int) {
        _exposureCompensation.value = ev
        applyManualSettings()
    }

    /* ─── ZOOM (sobrescribe el helper de Core con persistencia) ── */
    override fun setZoomContinuous(z: Float) {
        super.setZoomContinuous(z)
        // La clase final podrá llamar a SamsungVendorTags.applyZoomRatio()
        // sobre la sesión activa.
    }

    /* ─── MODO ─────────────────────────────────────────────────── */
    fun setCameraMode(mode: CameraMode) {
        _cameraMode.value = mode
        updateUiState { it.copy(currentMode = mode) }
    }

    fun setPreviewAspectRatio(ratio: Float) {
        _previewAspectRatio.value = ratio
    }

    /* ─── SWITCH FRONT/BACK ────────────────────────────────────── */
    open fun switchCamera() {
        closeCamera()
        _isFrontCamera.value = !_isFrontCamera.value
        // La clase final llama a openCamera() con el ID correcto.
    }

    /* ─── APLICACIÓN AL REPEATING PREVIEW ─────────────────────── */

    /**
     * Re-emite el repeating preview con los settings manuales actuales.
     * Es la API pública que invoca `CameraPreview.kt` en `LaunchedEffect(cameraMode)`.
     */
    open fun applyRepeatingPreview() {
        applyManualSettings()
    }

    protected fun applyManualSettings() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            if (manualIso.value > 0 && (manualShutterNs.value ?: 0L) > 0L) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso.value)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualShutterNs.value)
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            if (manualFocus.value > 0f) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocus.value)
            }
            builder.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                _exposureCompensation.value
            )
            // FIX B-07: enviar con backgroundHandler, NO en el hilo de UI.
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Throwable) {
            Log.e(TAG, "applyManualSettings falló", e)
        }
    }
}
