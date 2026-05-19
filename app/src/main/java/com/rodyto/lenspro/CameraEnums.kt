package com.rodyto.lenspro

import android.hardware.camera2.CameraCharacteristics
import android.util.Range
import android.util.Size

/* ================================================================
 *  Rodyto Lens Pro · CameraEnums.kt · v3.6 Pro (refactor)
 *
 *  Archivo 1 de la división del antiguo CameraControlViewModel.
 *  Contiene TODAS las definiciones de datos, enums y constantes
 *  que el resto del proyecto referencia. La unificación de tipos
 *  evita la dispersión de declaraciones a lo largo del módulo.
 *
 *  IMPORTANTE: package = com.rodyto.lenspro (igual que el namespace
 *  declarado en app/build.gradle). NO usar com.rodyto.rodyto_lens_pro.
 * ================================================================ */

// ─── Modos de cámara ─────────────────────────────────────────────
enum class CameraMode {
    PHOTO, VIDEO, PRO_PHOTO, PRO_VIDEO
}

enum class FocusMode {
    AUTO, MANUAL, MACRO, CONTINUOUS
}

enum class WhiteBalanceMode {
    AUTO, INCANDESCENT, FLUORESCENT, DAYLIGHT, CLOUDY
}

// ─── Flash (consumido por ActionChipBar, SettingsRepository) ─────
enum class FlashMode(val label: String) {
    OFF("Apagado"),
    AUTO("Automático"),
    ON("Encendido")
}

// ─── Resolución de vídeo (consumido por CameraTuning, VideoBitrateCalculator, SettingsRepository) ─
enum class VideoResolution(val label: String, val width: Int, val height: Int) {
    HD ("HD",  1280, 720),
    FHD("FHD", 1920, 1080),
    UHD("4K",  3840, 2160)
}

// ─── FPS (consumido por SettingsRepository) ──────────────────────
enum class VideoFps(val value: Int) {
    FPS30(30),
    FPS60(60)
}

// ─── Aspect ratios del preview (consumido por SettingsRepository) ─
enum class PreviewAspect(val ratio: Float) {
    RATIO_3_4 (3f / 4f),
    RATIO_9_16(9f / 16f),
    RATIO_1_1 (1f),
    RATIO_FULL(0f)  // 0f = el preview ocupa toda la pantalla disponible
}

// ─── Estado de la sesión Camera2 (consumido por CameraPreview) ───
enum class CameraSessionState {
    IDLE,       // sin sesión
    OPENING,    // abriendo CameraDevice / creando CaptureSession
    PREVIEWING, // repeating preview activo
    CAPTURING,  // captura still en curso
    RECORDING,  // grabación de vídeo en curso
    CLOSING,    // cerrando sesión
    ERROR       // sesión en estado de fallo
}

// ─── Estructuras de datos del HAL ────────────────────────────────
data class CameraHardwareInfo(
    val id: String,
    val facing: Int,
    val sensorOrientation: Int,
    val capabilities: IntArray,
    val physicalIds: Set<String> = emptySet()
)

data class LensInfo(
    val id: String,
    val label: String = id,        // "0.5x", "1x", "3x"…
    val focalLength: Float = 0f,
    val aperture: Float = 0f,
    val isPhysical: Boolean = false,
    val isOptical: Boolean = false
)

// ─── Estado agregado de UI (opcional, para subscriptores compactos) ─
data class CameraUiState(
    val isCameraReady: Boolean = false,
    val currentMode: CameraMode = CameraMode.PHOTO,
    val iso: Int = 100,
    val shutterSpeed: Long = 1_000_000L,
    val focusDistance: Float = 0f,
    val whiteBalance: WhiteBalanceMode = WhiteBalanceMode.AUTO,
    val zoomLevel: Float = 1f,
    val isRecording: Boolean = false,
    val availableLenses: List<LensInfo> = emptyList(),
    val currentLens: LensInfo? = null,
    val flashMode: Int = CameraCharacteristics.FLASH_MODE_OFF,
    val exposureCompensation: Int = 0,
    val evStep: Float = 0f,
    val evRange: Range<Int> = Range(0, 0)
)

// ─── Constantes globales ─────────────────────────────────────────
object CameraConstants {
    const val DEFAULT_PHOTO_BITRATE = 12_000_000   // JPEG (orientativo)
    const val MAX_ZOOM_DEFAULT      = 30f          // tope dial
    const val MIN_ZOOM_DEFAULT      = 0.5f
    const val PREVIEW_TARGET_FPS    = 30
    const val PREVIEW_COALESCE_MS   = 16L          // 1 frame @60Hz
    /** Tamaño de fallback si el HAL no provee SCALER_STREAM_CONFIGURATION_MAP */
    val FALLBACK_PREVIEW_SIZE = Size(1920, 1080)
}
