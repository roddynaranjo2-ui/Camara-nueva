package com.rodyto.lenspro

import android.hardware.camera2.CameraCharacteristics
import android.util.Range
import android.util.Size

/* ================================================================
 *  Rodyto Lens Pro · CameraEnums.kt · v3.8 Pro
 *
 *  NOVEDADES v3.8 (sobre v3.6):
 *   • CameraHardwareInfo: equals/hashCode/toString manuales que
 *     comparan capabilities por contenido (no por referencia).
 *     Esto elimina el warning "Arrays in 'CameraHardwareInfo' should
 *     be considered for content-based equality" y previene
 *     re-emisiones espurias de StateFlow cuando el HAL devuelve la
 *     misma info pero en arrays distintos.
 *
 *  Resto v3.6:
 *   • Enums consolidados (CameraMode, FocusMode, WhiteBalanceMode,
 *     FlashMode, VideoResolution, VideoFps, PreviewAspect,
 *     CameraSessionState).
 *   • Estructuras de datos del HAL.
 *   • Constantes globales en CameraConstants.
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
/**
 * CameraHardwareInfo — v3.8: equals/hashCode manuales para tratar
 * IntArray con semántica de contenido. Si se dejara como `data class`
 * pura, dos instancias con el mismo cameraId pero arrays diferentes
 * (devueltos por el HAL en consultas separadas) serían "distintas" y
 * forzarían re-emisiones de StateFlow.
 */
class CameraHardwareInfo(
    val id: String,
    val facing: Int,
    val sensorOrientation: Int,
    val capabilities: IntArray,
    val physicalIds: Set<String> = emptySet()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CameraHardwareInfo) return false
        return id == other.id &&
                facing == other.facing &&
                sensorOrientation == other.sensorOrientation &&
                capabilities.contentEquals(other.capabilities) &&
                physicalIds == other.physicalIds
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + facing
        result = 31 * result + sensorOrientation
        result = 31 * result + capabilities.contentHashCode()
        result = 31 * result + physicalIds.hashCode()
        return result
    }

    override fun toString(): String =
        "CameraHardwareInfo(id='$id', facing=$facing, orientation=$sensorOrientation, " +
            "capabilities=${capabilities.contentToString()}, physicalIds=$physicalIds)"
}

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
