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
 *  Rodyto Lens Pro · CameraControlViewModelState.kt · v3.8 Pro
 *
 *  NOVEDADES v3.8 (sobre v3.7):
 *   • _shutterBlinkKey: StateFlow<Int> incremental que la UI observa
 *     para disparar ShutterBlinkOverlay (fix bug B-05). El contador se
 *     incrementa cada vez que se ejecuta `takePhoto()` con éxito.
 *   • helper `bumpShutterBlinkKey()` interno — accesible desde Ops para
 *     publicar el blink sin exponer `_shutterBlinkKey` mutable fuera del VM.
 *   • helper `setCurrentLensInternal(LensInfo?)` que las subclases pueden
 *     llamar para reflejar la lente activa (fix bug B-03).
 *
 *  CORRECCIONES v3.7 preservadas:
 *   • cameraDevice, captureSession, previewRequestBuilder con @Volatile.
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
    val manualIso        = MutableStateFlow(sharedPreferences.getInt ("pref_iso",        0))      // 0 = AUTO
    val manualShutter    = MutableStateFlow(sharedPreferences.getLong("pref_shutter",    0L))
    /** Shutter en nanosegundos (Long?) — null = AUTO. Lo consume CameraXBridge. */
    val manualShutterNs  = MutableStateFlow<Long?>(
        sharedPreferences.getLong("pref_shutter_ns", 0L).takeIf { it > 0L }
    )
    val manualFocus      = MutableStateFlow(sharedPreferences.getFloat("pref_focus",     0f))
    val manualWB         = MutableStateFlow(sharedPreferences.getInt ("pref_wb",         0))      // 0 = AUTO

    /* ── Flags híbridos (v3.5+) ──────────────────────────────────── */
    /** Si true → activa el bridge CameraX ImageAnalysis para histograma. */
    val useCameraXAnalysis  = MutableStateFlow(false)
    /** Si true → fuerza la apertura del physical-id "52" en lente tele. */
    val forceTelePhysicalId = MutableStateFlow(false)
    /** ID físico activo (ej. "52" en S21 FE) — null = lente lógica estándar. */
    val activePhysicalTeleId = MutableStateFlow<String?>(null)

    /* ── Histograma publicado por CameraXBridge → consumido en MainActivity ─ */
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

    /* ── v3.8: Shutter blink trigger (fix bug B-05) ─────────────── */
    /**
     * Contador monotónico incremental. La UI observa este flow con
     * collectAsStateWithLifecycle y se lo pasa como `triggerKey` a
     * `ShutterBlinkOverlay`. Cada vez que cambia, el overlay reproduce
     * la animación de oscurecimiento. Empieza en 0 → primera captura = 1.
     */
    protected val _shutterBlinkKey = MutableStateFlow(0)
    val shutterBlinkKey: StateFlow<Int> = _shutterBlinkKey.asStateFlow()

    /* ── Variables protegidas (las usan Core / Ops) ──────────────── */
    // FIX v3.7: @Volatile garantiza visibilidad entre backgroundHandler y UI thread.
    @Volatile protected var cameraDevice: CameraDevice? = null
    @Volatile protected var captureSession: CameraCaptureSession? = null
    @Volatile protected var previewRequestBuilder: CaptureRequest.Builder? = null

    protected val availablePhysicalCameras = mutableListOf<String>()

    /** Tamaño óptimo del preview cacheado (lo calcula Core al abrir). */
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

    /** API pública usada por CameraXBridge para publicar bins YUV→Histograma. */
    fun publishHistogramBins(bins: IntArray) {
        _histogramBins.value = bins
    }

    /* ── v3.8 helpers internos para subclases Ops/final ─────────── */

    /**
     * fix B-03: setter interno para reflejar la lente activa tras un
     * cambio de cámara. Lo invoca CameraControlViewModelOps.setLens()
     * para mantener el StateFlow `currentLens` sincronizado con la
     * sesión Camera2 realmente abierta.
     */
    protected fun setCurrentLensInternal(lens: LensInfo?) {
        _currentLens.value = lens
        updateUiState { it.copy(currentLens = lens) }
    }

    /**
     * fix B-05: incrementa el contador de blink. Idempotente y thread-safe
     * (StateFlow.update es atómico). Lo invocan takePhoto() y cualquier
     * pipeline de captura futuro para notificar a la UI.
     */
    protected fun bumpShutterBlinkKey() {
        _shutterBlinkKey.update { current -> if (current >= Int.MAX_VALUE - 1) 1 else current + 1 }
    }
}
