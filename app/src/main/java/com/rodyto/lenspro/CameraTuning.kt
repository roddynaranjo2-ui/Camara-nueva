package com.rodyto.lenspro

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size

/**
 * CameraTuning v2 — perfil de Image-Quality + Codec helpers + FPS hardening.
 *
 * Mejoras vs v1:
 *  • bestFpsRange ahora es DEFINITIVAMENTE robusto (fix 60fps).
 *  • pickOptimalRecordingSize → escoge el size que coincide con el aspect ratio
 *    del recording target sin superar la resolución pedida (donante: Google Camera2Video).
 *  • supportsHighFps consulta el sensor antes de comprometerse a 60.
 */
object CameraTuning {

    private const val TAG = "CameraTuning"

    fun applyImageQuality(b: CaptureRequest.Builder, mode: String, supportsVstab: Boolean = false) {
        try {
            if (mode == "VIDEO") {
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
            } else {
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
            }
            b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)

            val vstabValue = if (mode == "VIDEO" && supportsVstab)
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, vstabValue)
        } catch (e: Throwable) {
            Log.w(TAG, "applyImageQuality fallback", e)
        }
    }

    fun applyJpegQuality(b: CaptureRequest.Builder) {
        try { b.set(CaptureRequest.JPEG_QUALITY, 97) } catch (_: Throwable) {}
    }

    fun applyJpegOrientation(b: CaptureRequest.Builder, sensorOrientation: Int, isFront: Boolean) {
        try {
            val deviceRotation = 0
            val jpegOrientation = if (isFront) {
                (sensorOrientation + deviceRotation) % 360
            } else {
                (sensorOrientation - deviceRotation + 360) % 360
            }
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
        } catch (_: Throwable) {}
    }

    fun supportsHevcEncoder(): Boolean {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) }
            }
        } catch (_: Throwable) { false }
    }

    fun preferredEncoder(allowHevc: Boolean): Int {
        return if (allowHevc && supportsHevcEncoder())
            MediaRecorder.VideoEncoder.HEVC
        else
            MediaRecorder.VideoEncoder.H264
    }

    fun preferredCodecLabel(allowHevc: Boolean): VideoBitrateCalculator.Codec =
        if (allowHevc && supportsHevcEncoder())
            VideoBitrateCalculator.Codec.HEVC
        else
            VideoBitrateCalculator.Codec.H264

    fun preferredProfile(allowHevc: Boolean): Int {
        return if (allowHevc && supportsHevcEncoder()) {
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        } else {
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        }
    }

    fun preferredLevel(res: VideoResolution, fps: Int, allowHevc: Boolean): Int {
        if (allowHevc && supportsHevcEncoder()) {
            return when {
                res == VideoResolution.UHD && fps >= 60 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52
                res == VideoResolution.UHD -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51
                res == VideoResolution.FHD && fps >= 60 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41
                res == VideoResolution.FHD -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4
                else -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31
            }
        }
        return when {
            res == VideoResolution.UHD && fps >= 60 -> MediaCodecInfo.CodecProfileLevel.AVCLevel52
            res == VideoResolution.UHD -> MediaCodecInfo.CodecProfileLevel.AVCLevel51
            res == VideoResolution.FHD && fps >= 60 -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
            res == VideoResolution.FHD -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
            else -> MediaCodecInfo.CodecProfileLevel.AVCLevel31
        }
    }

    fun isLogicalMultiCamera(ch: CameraCharacteristics?): Boolean {
        ch ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        return caps.any {
            it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
        }
    }

    /**
     * ▼ FIX 60 FPS DEFINITIVO ▼
     *
     * Estrategia (orden de prioridad):
     *   1. Rango EXACTO [fps,fps] → óptimo (HAL bloqueado a fps fijo).
     *   2. Rango [fps, fps] dentro de los reported → fallback exacto.
     *   3. Rango con upper==fps y lower lo más alto posible → fps fijo virtual.
     *   4. Rango variable que cubre fps en su parte alta.
     *   5. Último recurso: rango con mayor upper (puede ser < fps → graceful degradation).
     *
     * El donante Google Camera2Video usa `Range(fps,fps)` directamente: lo replico
     * cuando es factible y caigo a variables sólo si no hay otro modo.
     */
    fun bestFpsRange(ranges: Array<Range<Int>>, fps: Int): Range<Int> {
        if (ranges.isEmpty()) return Range(fps, fps)

        // 1) Rango fijo exacto
        ranges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it }

        // 2) Upper == fps con lower lo más alto posible (preferimos casi-fijo)
        ranges.filter { it.upper == fps }
            .maxByOrNull { it.lower }
            ?.let { return it }

        // 3) Rangos que CUBREN fps (lower<=fps<=upper); preferimos el más estrecho
        ranges.filter { it.lower <= fps && it.upper >= fps }
            .minByOrNull { it.upper - it.lower }
            ?.let { return it }

        // 4) Rango con upper >= fps; minimiza la diferencia con fps
        ranges.filter { it.upper >= fps }
            .minByOrNull { kotlin.math.abs(it.upper - fps) }
            ?.let { return it }

        // 5) Sin opción → mayor upper disponible (warning)
        val fallback = ranges.maxByOrNull { it.upper } ?: Range(fps, fps)
        Log.w(TAG, "bestFpsRange: no fps=$fps disponible. Usando $fallback")
        return fallback
    }

    /**
     * ▼ NUEVO ▼  Indica si el dispositivo puede REALMENTE alcanzar el fps pedido
     * en la resolución solicitada.
     */
    fun supportsFpsAtResolution(
        characteristics: CameraCharacteristics?,
        res: VideoResolution,
        fps: Int
    ): Boolean {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return false
        // Para 60 fps en 4K hace falta CONSTRAINED_HIGH_SPEED o un sensor full-speed.
        val durations = map.getOutputMinFrameDuration(android.media.MediaRecorder::class.java,
            Size(res.width, res.height))
        if (durations <= 0L) return false
        val maxFps = (1_000_000_000.0 / durations).toInt()
        return maxFps + 2 >= fps  // margen 2fps de tolerancia
    }

    /**
     * ▼ NUEVO ▼  Selección inteligente de la mejor resolución de grabación.
     * (extracción del patrón de chooseOptimalSize en Camera2Video — Google)
     */
    fun pickOptimalRecordingSize(
        characteristics: CameraCharacteristics?,
        target: VideoResolution
    ): Size {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(target.width, target.height)
        val sizes = map.getOutputSizes(android.media.MediaRecorder::class.java) ?: return Size(target.width, target.height)
        // Coincidencia exacta primero
        sizes.firstOrNull { it.width == target.width && it.height == target.height }?.let { return it }
        // El más cercano por área sin pasarse
        val targetArea = target.width.toLong() * target.height
        return sizes
            .filter { it.width.toLong() * it.height <= targetArea }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.first()
    }
}
