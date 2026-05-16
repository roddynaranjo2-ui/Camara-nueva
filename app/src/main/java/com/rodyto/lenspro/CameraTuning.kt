package com.rodyto.lenspro

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.util.Log

/**
 * CameraTuning — Perfil de Image-Quality + Codec helpers.
 *
 *  - Noise reduction: HIGH_QUALITY en FOTO, FAST en VIDEO
 *  - Edge sharpening: HIGH_QUALITY en FOTO, FAST en VIDEO
 *  - Tonemap:         HIGH_QUALITY en FOTO, FAST en VIDEO (evita lag 4K/60)
 *  - JPEG quality:    97 (Samsung Pro mode)
 *  - VSTAB:           PREVIEW para video estándar, solo si el HAL lo expone.
 */
object CameraTuning {

    private const val TAG = "CameraTuning"

    /**
     * @param supportsVstab Si el HAL realmente expone VIDEO_STABILIZATION_MODE_ON.
     * Cuando es false, NO se fuerza VSTAB porque algunos HALs lo aceptan pero
     * silenciosamente caen el FPS a la mitad o menos (causa de lag 6 fps).
     */
    fun applyImageQuality(b: CaptureRequest.Builder, mode: String, supportsVstab: Boolean = false) {
        try {
            if (mode == "VIDEO") {
                b.set(CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                b.set(CaptureRequest.EDGE_MODE,
                    CaptureRequest.EDGE_MODE_FAST)
                // FIX: en video, TONEMAP_FAST evita drops de FPS en 4K/60
                b.set(CaptureRequest.TONEMAP_MODE,
                    CaptureRequest.TONEMAP_MODE_FAST)
            } else {
                b.set(CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                b.set(CaptureRequest.EDGE_MODE,
                    CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                b.set(CaptureRequest.TONEMAP_MODE,
                    CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
            }
            b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)

            // FIX VIDEO LAG: VSTAB solo si está realmente soportado.
            // Si no está soportado, lo dejamos OFF para que la cámara mantenga FPS.
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

    /**
     * Orientación EXIF de la foto. El device se asume portrait (la activity
     * está locked a portrait).
     */
    fun applyJpegOrientation(b: CaptureRequest.Builder, sensorOrientation: Int, isFront: Boolean) {
        try {
            val deviceRotation = 0 // portrait fijo
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

    /** Profile para setVideoEncodingProfileLevel(). */
    fun preferredProfile(allowHevc: Boolean): Int {
        return if (allowHevc && supportsHevcEncoder()) {
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        } else {
            // High elimina lag del Baseline en algunos Samsung
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        }
    }

    /** Level adecuado según resolución/fps. */
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
}
