package com.rodyto.lenspro

import android.app.Application
import android.hardware.camera2.CaptureRequest
import android.util.Log

/* ================================================================
 *  Rodyto Lens Pro · CameraControlViewModelOps.kt · v3.8 Pro
 *
 *  NOVEDADES v3.8 (sobre v3.7):
 *   • setLens(LensInfo) — API pública para cambiar de lente desde la
 *     UI. Persiste el StateFlow `_currentLens` y dispara un hook
 *     `onLensSwitchRequested(lens)` que la clase final override-ea
 *     para cerrar la sesión actual y reabrir con el nuevo cameraId.
 *     Esto cierra el bug B-03 (lens taps no hacían nada).
 *
 *   • takePhoto() ahora notifica a la UI vía bumpShutterBlinkKey()
 *     para que ShutterBlinkOverlay reproduzca su animación (fix B-05).
 *     La lógica HAL real sigue siendo un hook que la clase final
 *     puede sobrescribir; el feedback visual ya queda conectado.
 *
 *  CORRECCIONES v3.7 preservadas:
 *   • applyManualSettings() ejecuta todo en backgroundHandler.
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
        Log.d(TAG, "takePhoto: solicitud emitida (placeholder)")

        // FIX v3.8 (bug B-05): notificar a la UI para que ShutterBlinkOverlay
        // reaccione INMEDIATAMENTE al disparo, independientemente de cuándo
        // termine la captura HAL real. Esto da el feedback visual instantáneo
        // que el usuario espera y desacopla el FX de la pipeline de captura.
        bumpShutterBlinkKey()

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

    /* ─── LENS SWITCH (v3.8 — fix B-03) ───────────────────────── */

    /**
     * API pública que la UI invoca cuando el usuario pulsa una lente
     * en LensSelectorRow. La clase final (CameraControlViewModel)
     * override-ea `onLensSwitchRequested(lens)` para cerrar la sesión
     * actual y reabrir con el cameraId correspondiente.
     *
     * Aquí actualizamos el StateFlow inmediatamente para que la UI
     * refleje la selección, y disparamos el hook para que la subclase
     * haga el trabajo HAL pesado.
     */
    open fun setLens(lens: LensInfo?) {
        if (lens == null) return
        // Reflejar la lente en el StateFlow ANTES del switch HAL para que
        // los Composables (LensBubblePro selected=...) respondan al instante.
        setCurrentLensInternal(lens)
        // Hook para la subclase final. Se ejecuta async vía Core.
        onLensSwitchRequested(lens)
    }

    /**
     * Hook template-method. La clase final lo sobreescribe para invocar
     * safeCloseCamera() + openCamera() con el nuevo cameraId. Aquí en
     * Ops queda como no-op para que la pila compile aislada.
     */
    protected open fun onLensSwitchRequested(lens: LensInfo) {
        Log.d(TAG, "onLensSwitchRequested (base no-op): lens=${lens.label}")
    }

    /* ─── SWITCH FRONT/BACK ────────────────────────────────────── */
    open fun switchCamera() {
        closeCamera()
        _isFrontCamera.value = !_isFrontCamera.value
    }

    /* ─── APLICACIÓN AL REPEATING PREVIEW ─────────────────────── */

    /**
     * Re-emite el repeating preview con los settings manuales actuales.
     * Es la API pública que invoca `CameraPreview.kt` en `LaunchedEffect(cameraMode)`.
     */
    open fun applyRepeatingPreview() {
        applyManualSettings()
    }

    /**
     * FIX v3.7: toda la lógica se posta al backgroundHandler.
     *
     * Patrón: capturar referencias locales en el hilo llamante (UI)
     * para el null-check, luego ejecutar en background.
     * Esto garantiza:
     *  1. No se accede a Camera2 desde el hilo UI.
     *  2. Si cameraDevice o captureSession son null al momento de
     *     la llamada, retornamos sin postear → sin NPE en background.
     */
    protected fun applyManualSettings() {
        // Captura atómica de referencias (campos @Volatile en State v3.7)
        val builder = previewRequestBuilder ?: return
        val session = captureSession    ?: return
        val handler = backgroundHandler ?: return

        // FIX B-07 v3.7: toda la ejecución en backgroundHandler
        handler.post {
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
                session.setRepeatingRequest(builder.build(), null, handler)
            } catch (e: Throwable) {
                Log.e(TAG, "applyManualSettings falló", e)
            }
        }
    }
}
