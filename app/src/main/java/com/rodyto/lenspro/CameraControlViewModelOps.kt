package com.rodyto.lenspro

import android.app.Application
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

/* ================================================================
 *  Rodyto Lens Pro · CameraControlViewModelOps.kt · v4.0 Premium
 *
 *  v4.0 — Cambios:
 *   • takePhoto() AHORA REAL — delega a CameraCaptureEngine vía
 *     viewModelScope. JPEG real con flash + timer respetados.
 *   • setZoomContinuous() OVERRIDEN para aplicar SCALER_CROP_REGION
 *     al previewRequestBuilder en tiempo real → zoom visible.
 *   • switchCamera() arreglado (fix B1): re-abre sesión inmediatamente
 *     tras flippear el flag de facing.
 *   • applyFlashToBuilder() helper centralizado.
 * ================================================================ */
open class CameraControlViewModelOps(application: Application) :
    CameraControlViewModelCore(application) {

    companion object { private const val TAG = "CameraVMOps" }

    /** Engine de captura — instanciado en la clase final con context. */
    @Volatile protected var captureEngine: CameraCaptureEngine? = null

    /* ─── ACCIONES DE DISPARO ─────────────────────────────────── */

    open fun takePhoto() {
        val session = captureSession ?: run {
            Log.w(TAG, "takePhoto: ignorado (sesión nula)"); return
        }
        val builder = previewRequestBuilder ?: run {
            Log.w(TAG, "takePhoto: ignorado (preview builder nulo)"); return
        }
        val engine = captureEngine ?: run {
            Log.w(TAG, "takePhoto: ignorado (engine nulo)"); return
        }
        _sessionState.value = CameraSessionState.CAPTURING

        viewModelScope.launch(Dispatchers.Default) {
            engine.flashMode = _flashMode.value
            engine.captureStill(
                session = session,
                previewBuilder = builder,
                characteristics = currentCharacteristics,
                timerSeconds = _timerSeconds.value,
                onShutterEffect = {
                    bumpShutterBlinkKey()
                    publishCountdown(0)
                }
            )
            // El countdown se publica internamente vía engine.countdown,
            // pero también lo replicamos al StateFlow del VM
            // (subscripción independiente está en CameraControlViewModel.kt)
            _sessionState.value = CameraSessionState.PREVIEWING
        }

        // Replicar countdown del engine → StateFlow del VM
        viewModelScope.launch {
            captureEngine?.countdown?.collect { v -> publishCountdown(v) }
        }
    }

    open fun toggleRecording() {
        if (_isRecording.value) stopVideo() else startVideo()
    }

    protected open fun startVideo() {
        if (cameraDevice == null) { Log.w(TAG, "startVideo: sin cámara"); return }
        _isRecording.value = true
        _sessionState.value = CameraSessionState.RECORDING
        updateUiState { it.copy(isRecording = true) }
        // Torch ON si flashMode = ON durante vídeo
        if (_flashMode.value == FlashMode.ON) {
            captureEngine?.setTorch(true, previewRequestBuilder, captureSession)
        }
    }

    protected open fun stopVideo() {
        _isRecording.value = false
        _sessionState.value = CameraSessionState.PREVIEWING
        updateUiState { it.copy(isRecording = false) }
        captureEngine?.setTorch(false, previewRequestBuilder, captureSession)
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

    /* ─── ZOOM real con SCALER_CROP_REGION ───────────────────── */
    override fun setZoomContinuous(z: Float) {
        super.setZoomContinuous(z)
        val ratio = z.coerceIn(getZoomMinValue(), getZoomMaxValue())
        val chars = currentCharacteristics ?: return
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        val handler = backgroundHandler ?: return

        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val centerX = active.width() / 2
        val centerY = active.height() / 2
        val effectiveZoom = ratio.coerceAtLeast(1f)
        val newW = (active.width() / effectiveZoom).toInt()
        val newH = (active.height() / effectiveZoom).toInt()
        val cropRect = Rect(
            centerX - newW / 2,
            centerY - newH / 2,
            centerX + newW / 2,
            centerY + newH / 2
        )
        handler.post {
            try {
                builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
                session.setRepeatingRequest(builder.build(), null, handler)
            } catch (t: Throwable) {
                Log.w(TAG, "setZoom crop region falló", t)
            }
        }
    }

    /* ─── MODO ─────────────────────────────────────────────────── */
    fun setCameraMode(mode: CameraMode) {
        _cameraMode.value = mode
        updateUiState { it.copy(currentMode = mode) }
    }

    fun setPreviewAspectRatio(ratio: Float) {
        _previewAspectRatio.value = ratio
    }

    /* ─── LENS SWITCH ────────────────────────────────────────── */
    open fun setLens(lens: LensInfo?) {
        if (lens == null) return
        setCurrentLensInternal(lens)
        onLensSwitchRequested(lens)
    }

    protected open fun onLensSwitchRequested(lens: LensInfo) {
        Log.d(TAG, "onLensSwitchRequested (base no-op): lens=${lens.label}")
    }

    /* ─── SWITCH FRONT/BACK — FIX B1 ─────────────────────────── */
    open fun switchCamera() {
        // El override en la clase final reabrirá. Aquí solo flippamos.
        _isFrontCamera.value = !_isFrontCamera.value
        onFrontBackSwitchRequested()
    }

    /** Hook para la clase final: reapertura inmediata. */
    protected open fun onFrontBackSwitchRequested() {
        Log.d(TAG, "onFrontBackSwitchRequested (base): front=${_isFrontCamera.value}")
    }

    /* ─── APLICACIÓN AL REPEATING PREVIEW ─────────────────────── */
    open fun applyRepeatingPreview() {
        applyManualSettings()
    }

    protected fun applyManualSettings() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession    ?: return
        val handler = backgroundHandler ?: return

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
                // Flash AUTO sobre preview no fuerza disparo — solo prepara.
                if (_flashMode.value == FlashMode.AUTO) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                }
                session.setRepeatingRequest(builder.build(), null, handler)
            } catch (e: Throwable) {
                Log.e(TAG, "applyManualSettings falló", e)
            }
        }
    }
}
