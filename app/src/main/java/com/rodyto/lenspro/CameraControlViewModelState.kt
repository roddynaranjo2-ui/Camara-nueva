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
 *  Rodyto Lens Pro · CameraControlViewModelState.kt · v3.6 Pro
 *
 *  Archivo 2 de la división. Base de la jerarquía:
 *      State  →  Core  →  Ops  →  CameraControlViewModel (final)
 *
 *  Aquí viven SOLO:
 *    • StateFlows reactivos consumidos por la UI Compose.
 *    • Persistencia ligera vía SharedPreferences (la app además
 *      cuenta con SettingsRepository basado en DataStore para los
 *      ajustes que sobreviven a la reinstalación).
 *    • Variables protegidas del HAL (cameraDevice, captureSession,
 *      previewRequestBuilder) que las subclases Core/Ops usan.
 *
 *  Reglas de oro:
 *    • NUNCA acceder a HAL desde aquí — eso es responsabilidad de Core.
 *    • Mantener todos los miembros como `protected` u `open` para que
 *      las subclases puedan completarlos.
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

    /* ── Variables protegidas (las usan Core / Ops) ──────────────── */
    protected var cameraDevice: CameraDevice? = null
    protected var captureSession: CameraCaptureSession? = null
    protected var previewRequestBuilder: CaptureRequest.Builder? = null
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
}
