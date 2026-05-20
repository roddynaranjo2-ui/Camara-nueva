package com.rodyto.lenspro

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/* ================================================================
 *  Rodyto Lens Pro · CameraControlViewModelState.kt · v4.0 Premium
 *
 *  v4.0 — Cambios respecto a v3.8:
 *   • _flashMode StateFlow (OFF/AUTO/ON) — publicado a la UI.
 *   • _timerSeconds StateFlow para el timer del shutter.
 *   • _gridEnabled, _hapticsEnabled, _soundEnabled reflejos sincronizados.
 *   • Helpers setFlashModeInternal / setTimerInternal etc.
 * ================================================================ */
open class CameraControlViewModelState(application: Application) : AndroidViewModel(application) {

    /* ── Persistencia ligera ─────────────────────────────────────── */
    protected val sharedPreferences =
        application.getSharedPreferences("rodyto_lens_settings", Context.MODE_PRIVATE)

    /* ── Estado agregado opcional ────────────────────────────────── */
    protected val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    /* ── Sesión / estado del HAL ─────────────────────────────────── */
    protected val _sessionState = MutableStateFlow(CameraSessionState.IDLE)
    val sessionState: StateFlow<CameraSessionState> = _sessionState.asStateFlow()

    /* ── Modo (FOTO / VIDEO / PRO_*) ─────────────────────────────── */
    protected val _cameraMode = MutableStateFlow(CameraMode.PHOTO)
    val cameraMode: StateFlow<CameraMode> = _cameraMode.asStateFlow()

    /* ── Aspect ratio del preview (1f, 0.75f, etc.) ─────────────── */
    protected val _previewAspectRatio = MutableStateFlow(3f / 4f)
    val previewAspectRatio: StateFlow<Float> = _previewAspectRatio.asStateFlow()

    /* ── Zoom (continuo, no enum) ────────────────────────────────── */
    protected val _zoomLevel = MutableStateFlow(1f)
    val zoomLevel: StateFlow<Float> = _zoomLevel.asStateFlow()

    /* ── Lente seleccionada / disponibles ────────────────────────── */
    protected val _currentLens = MutableStateFlow<LensInfo?>(null)
    val currentLens: StateFlow<LensInfo?> = _currentLens.asStateFlow()

    protected val _availableLenses = MutableStateFlow<List<LensInfo>>(emptyList())
    val availableLenses: StateFlow<List<LensInfo>> = _availableLenses.asStateFlow()

    /* ── Frente / trasera ────────────────────────────────────────── */
    protected val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    /* ── Control manual (Pro) ────────────────────────────────────── */
    val manualIso        = MutableStateFlow(sharedPreferences.getInt ("pref_iso",        0))
    val manualShutter    = MutableStateFlow(sharedPreferences.getLong("pref_shutter",    0L))
    val manualShutterNs  = MutableStateFlow<Long?>(
        sharedPreferences.getLong("pref_shutter_ns", 0L).takeIf { it > 0L }
    )
    val manualFocus      = MutableStateFlow(sharedPreferences.getFloat("pref_focus",     0f))
    val manualWB         = MutableStateFlow(sharedPreferences.getInt ("pref_wb",         0))

    /* ── v4.0 · Flash mode (OFF/AUTO/ON) ─────────────────────────── */
    protected val _flashMode = MutableStateFlow(FlashMode.OFF)
    val flashMode: StateFlow<FlashMode> = _flashMode.asStateFlow()

    /* ── v4.0 · Timer (0/3/10 segundos) ──────────────────────────── */
    protected val _timerSeconds = MutableStateFlow(0)
    val timerSeconds: StateFlow<Int> = _timerSeconds.asStateFlow()

    /* ── v4.0 · Countdown activo ─────────────────────────────────── */
    protected val _activeCountdown = MutableStateFlow(0)
    val activeCountdown: StateFlow<Int> = _activeCountdown.asStateFlow()

    /* ── v4.0 · Overlays funcionales ─────────────────────────────── */
    protected val _gridEnabled = MutableStateFlow(false)
    val gridEnabled: StateFlow<Boolean> = _gridEnabled.asStateFlow()

    protected val _hapticsEnabled = MutableStateFlow(true)
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    protected val _soundEnabled = MutableStateFlow(true)
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    /* ── Flags híbridos (v3.5+) ──────────────────────────────────── */
    val useCameraXAnalysis  = MutableStateFlow(false)
    val forceTelePhysicalId = MutableStateFlow(false)
    val activePhysicalTeleId = MutableStateFlow<String?>(null)

    /* ── Histograma publicado por CameraXBridge ──────────────────── */
    protected val _histogramBins = MutableStateFlow<IntArray?>(null)
    val histogramBins: StateFlow<IntArray?> = _histogramBins.asStateFlow()

    /* ── Recording / record state ────────────────────────────────── */
    protected val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /* ── EV (Exposición) ─────────────────────────────────────────── */
    protected val _exposureCompensation = MutableStateFlow(0)
    val exposureCompensation: StateFlow<Int> = _exposureCompensation.asStateFlow()

    /* ── Soportes de hardware (detectados al abrir) ──────────────── */
    val isFlashSupported = MutableStateFlow(false)
    val isRawSupported   = MutableStateFlow(false)

    /* ── Shutter blink trigger ───────────────────────────────────── */
    protected val _shutterBlinkKey = MutableStateFlow(0)
    val shutterBlinkKey: StateFlow<Int> = _shutterBlinkKey.asStateFlow()

    /* ── Variables protegidas ────────────────────────────────────── */
    @Volatile protected var cameraDevice: CameraDevice? = null
    @Volatile protected var captureSession: CameraCaptureSession? = null
    @Volatile protected var previewRequestBuilder: CaptureRequest.Builder? = null
    @Volatile protected var currentCharacteristics: android.hardware.camera2.CameraCharacteristics? = null

    protected val availablePhysicalCameras = mutableListOf<String>()

    @Volatile protected var optimalPreviewSize: Size = CameraConstants.FALLBACK_PREVIEW_SIZE

    /* ── Helpers comunes ─────────────────────────────────────────── */
    protected fun updateUiState(reducer: (CameraUiState) -> CameraUiState) {
        _uiState.update(reducer)
    }

    protected fun saveSetting(key: String, value: Any) {
        with(sharedPreferences.edit()) {
            when (value) {
                is Int     -> putInt    (key, value)
                is Long    -> putLong   (key, value)
                is Float   -> putFloat  (key, value)
                is Boolean -> putBoolean(key, value)
                is String  -> putString (key, value)
            }
            apply()
        }
    }

    fun publishHistogramBins(bins: IntArray) {
        _histogramBins.value = bins
    }

    /* ── Setters internos para subclases ─────────────────────────── */

    protected fun setCurrentLensInternal(lens: LensInfo?) {
        _currentLens.value = lens
        updateUiState { it.copy(currentLens = lens) }
    }

    protected fun bumpShutterBlinkKey() {
        _shutterBlinkKey.update { c -> if (c >= Int.MAX_VALUE - 1) 1 else c + 1 }
    }

    /* ── v4.0 API pública ────────────────────────────────────────── */

    fun setFlashMode(mode: FlashMode) { _flashMode.value = mode }
    fun setTimerSeconds(s: Int)       { _timerSeconds.value = s.coerceIn(0, 10) }
    fun setGridEnabled(v: Boolean)    { _gridEnabled.value = v }
    fun setHapticsEnabled(v: Boolean) { _hapticsEnabled.value = v }
    fun setSoundEnabled(v: Boolean)   { _soundEnabled.value = v }

    protected fun publishCountdown(value: Int) { _activeCountdown.value = value }
}
