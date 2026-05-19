package com.rodyto.rodyto_lens_pro // Asegúrate de que este sea tu package real

import android.hardware.camera2.CameraCharacteristics
import android.util.Size

/**
 * Archivo 1: Definiciones de datos y estados constantes.
 * Este archivo contiene todas las estructuras de datos originales sin alteraciones.
 */

enum class CameraMode {
    PHOTO, VIDEO, PRO_PHOTO, PRO_VIDEO
}

enum class FocusMode {
    AUTO, MANUAL, MACRO, CONTINUOUS
}

enum class WhiteBalanceMode {
    AUTO, INCANDESCENT, FLUORESCENT, DAYLIGHT, CLOUDY
}

data class CameraHardwareInfo(
    val id: String,
    val facing: Int,
    val sensorOrientation: Int,
    val capabilities: IntArray,
    val physicalIds: Set<String> = emptySet()
)

data class LensInfo(
    val id: String,
    val focalLength: Float,
    val aperture: Float,
    val isPhysical: Boolean = false
)

data class CameraUiState(
    val isCameraReady: Boolean = false,
    val currentMode: CameraMode = CameraMode.PHOTO,
    val iso: Int = 100,
    val shutterSpeed: Long = 1_000_000L, // 1/1000s por defecto
    val focusDistance: Float = 0f,
    val whiteBalance: WhiteBalanceMode = WhiteBalanceMode.AUTO,
    val zoomLevel: Float = 1f,
    val isRecording: Boolean = false,
    val availableLenses: List<LensInfo> = emptyList(),
    val currentLens: LensInfo? = null,
    val flashMode: Int = CameraCharacteristics.FLASH_MODE_OFF,
    val exposureCompensation: Int = 0,
    val evStep: Float = 0f,
    val evRange: android.util.Range<Int> = android.util.Range(0, 0)
)

// Constantes de configuración
object CameraConstants {
    const val VIDEO_BITRATE = 10_000_000 // 10Mbps
    const val MAX_ZOOM_DEFAULT = 10f
    const val PREVIEW_TARGET_FPS = 30
}
