package com.rodyto.lenspro

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.Log

/**
 * CameraTuning
 *
 * Perfil de Image-Quality optimizado para Qualcomm Snapdragon 888 (S21 FE):
 *   - Noise reduction: HIGH_QUALITY en FOTO, FAST en VIDEO
 *   - Edge sharpening: HIGH_QUALITY en FOTO, FAST en VIDEO
 *   - Tonemap:         HIGH_QUALITY siempre (el ISP del 888 lo absorbe sin lag)
 *   - JPEG quality:    97 (igual que Samsung en modo Pro)
 *   - AE antibanding:  AUTO
 *   - VSTAB:           PREVIEW para video estándar
 *
 * Además expone helpers para HEVC/H264 con detección dinámica.
 */
object CameraTuning {

    private const val TAG = "CameraTuning"

    fun applyImageQuality(b: CaptureRequest.Builder, mode: String) {
        try {
            if (mode == "VIDEO") {
                b.set(CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                b.set(CaptureRequest.EDGE_MODE,
                    CaptureRequest.EDGE_MODE_FAST)
            } else {
                b.set(CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                b.set(CaptureRequest.EDGE_MODE,
                    CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            }
            b.set(CaptureRequest.TONEMAP_MODE,
                CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
            b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
            // VSTAB de Camera2 estándar (complementa al OIS samsung)
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (mode == "VIDEO")
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        } catch (e: Throwable) {
            Log.w(TAG, "applyImageQuality fallback", e)
        }
    }

    fun applyJpegQuality(b: CaptureRequest.Builder) {
        try { b.set(CaptureRequest.JPEG_QUALITY, 97) } catch (_: Throwable) {}
    }

    fun supportsHevcEncoder(): Boolean {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) }
            }
        } catch (_: Throwable) { false }
    }

    /** Codec preferido considerando soporte real del dispositivo. */
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

    /**
     * Detecta si el sensor activo soporta `LOGICAL_MULTI_CAMERA` (zoom continuo)
     * — pista útil para el Snapdragon 888 (Galaxy S21 FE tiene la cámara
     * trasera principal como lógica multi-cámara).
     */
    fun isLogicalMultiCamera(ch: CameraCharacteristics?): Boolean {
        ch ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        return caps.any {
            it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
        }
    }
}
