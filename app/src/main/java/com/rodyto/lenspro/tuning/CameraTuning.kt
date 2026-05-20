package com.rodyto.lenspro

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder
import kotlin.math.abs

/**
 * CameraTuning v4.1 — OPTIMIZADO
 *
 * Cambios v4.1:
 *  • BUG-C6: JPEG_QUALITY debe ser Byte, no Int.
 */
object CameraTuning {

    private const val TAG = "CameraTuning"

    private const val DEFAULT_MAX_PREVIEW_AREA: Long = 1920L * 1080L
    private const val ASPECT_TOLERANCE = 0.04f

    /** Rangos FPS con spread > este valor se penalizan. */
    private const val WIDE_FPS_RANGE_THRESHOLD = 15

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

    /**
     * BUG-C6: JPEG_QUALITY es de tipo Byte en el SDK de Android.
     * Pasar un Int puede causar fallos silenciosos o excepciones en algunos HALs.
     */
    fun applyJpegQuality(b: CaptureRequest.Builder, quality: Int = 97) {
        try {
            b.set(CaptureRequest.JPEG_QUALITY, quality.toByte())
        } catch (e: Throwable) {
            Log.w(TAG, "Error aplicando JPEG_QUALITY", e)
        }
    }

    fun applyJpegOrientation(b: CaptureRequest.Builder, sensorOrientation: Int, isFront: Boolean) {
        applyJpegOrientationDynamic(b, sensorOrientation, isFront, 0)
    }

    fun applyJpegOrientationDynamic(
        b: CaptureRequest.Builder,
        sensorOrientation: Int,
        isFront: Boolean,
        deviceRotationDeg: Int
    ) {
        try {
            val jpegOrientation = if (isFront) {
                (sensorOrientation + deviceRotationDeg) % 360
            } else {
                (sensorOrientation - deviceRotationDeg + 360) % 360
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

    fun bestFpsRange(ranges: Array<Range<Int>>, fps: Int): Range<Int> {
        if (ranges.isEmpty()) return Range(fps, fps)

        // 1) Exacto fijo
        ranges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it }

        // 2) upper==fps, menor spread
        ranges.filter { it.upper == fps }
            .minByOrNull { it.upper - it.lower }?.let { return it }

        // 3) contiene fps con spread ≤ umbral
        ranges.filter { it.lower <= fps && it.upper >= fps && (it.upper - it.lower) <= WIDE_FPS_RANGE_THRESHOLD }
            .minByOrNull { it.upper - it.lower }?.let { return it }

        // 4) contiene fps (cualquier spread)
        ranges.filter { it.lower <= fps && it.upper >= fps }
            .minByOrNull { it.upper - it.lower }?.let { return it }

        // 5) más cercano por upper
        ranges.filter { it.upper >= fps }
            .minByOrNull { abs(it.upper - fps) }?.let { return it }

        val fallback = ranges.maxByOrNull { it.upper } ?: Range(fps, fps)
        Log.w(TAG, "bestFpsRange: no fps=$fps disponible. Usando $fallback")
        return fallback
    }

    fun supportsFpsAtResolution(
        characteristics: CameraCharacteristics?,
        res: VideoResolution,
        fps: Int
    ): Boolean {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return false
        val durations = map.getOutputMinFrameDuration(MediaRecorder::class.java,
            Size(res.width, res.height))
        if (durations <= 0L) return false
        val maxFps = (1_000_000_000.0 / durations).toInt()
        return maxFps + 2 >= fps
    }

    fun pickOptimalRecordingSize(
        characteristics: CameraCharacteristics?,
        target: VideoResolution
    ): Size {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(target.width, target.height)
        val sizes = map.getOutputSizes(MediaRecorder::class.java) ?: return Size(target.width, target.height)
        sizes.firstOrNull { it.width == target.width && it.height == target.height }?.let { return it }

        val targetRatio = target.width.toFloat() / target.height.toFloat()
        val targetArea = target.width.toLong() * target.height
        val compatible = sizes.filter {
            val r = it.width.toFloat() / it.height.toFloat()
            abs(r - targetRatio) / targetRatio <= ASPECT_TOLERANCE &&
                it.width.toLong() * it.height <= targetArea
        }
        return compatible.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes
                .filter { it.width.toLong() * it.height <= targetArea }
                .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.first()
    }

    fun pickOptimalPreviewSize(
        characteristics: CameraCharacteristics?,
        targetRatio: Float,
        displayLongEdgePx: Int = 0
    ): Size {
        val fallback = Size(1920, 1080)

        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return fallback

        val targetLandscape = if (targetRatio < 1f) 1f / targetRatio else targetRatio
        if (targetLandscape <= 0f || !targetLandscape.isFinite()) return fallback

        val maxArea: Long = if (displayLongEdgePx > 200) {
            val displayShort = (displayLongEdgePx / targetLandscape).toInt().coerceAtLeast(720)
            val displayArea = displayLongEdgePx.toLong() * displayShort.toLong()
            minOf(DEFAULT_MAX_PREVIEW_AREA, (displayArea * 1.05).toLong())
        } else {
            DEFAULT_MAX_PREVIEW_AREA
        }

        val holderSizes: Array<Size> = try {
            map.getOutputSizes(SurfaceHolder::class.java) ?: emptyArray()
        } catch (_: Throwable) { emptyArray() }

        val textureSizes: Array<Size> = try {
            map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        } catch (_: Throwable) { emptyArray() }

        val all: List<Size> = (holderSizes.toList() + textureSizes.toList())
            .distinct()
            .filter { it.width.toLong() * it.height.toLong() <= maxArea }

        if (all.isEmpty()) {
            Log.w(TAG, "pickOptimalPreviewSize: sin candidatos → fallback $fallback")
            return fallback
        }

        val matched = all.filter {
            val r = it.width.toFloat() / it.height.toFloat()
            abs(r - targetLandscape) / targetLandscape <= ASPECT_TOLERANCE
        }
        val pool = if (matched.isNotEmpty()) matched else all

        val chosen = if (displayLongEdgePx > 200) {
            pool.minByOrNull { abs(it.width - displayLongEdgePx) }
                ?: pool.maxByOrNull { it.width.toLong() * it.height }
                ?: fallback
        } else {
            pool.maxByOrNull { it.width.toLong() * it.height } ?: fallback
        }

        Log.d(TAG, "pickOptimalPreviewSize: target=$targetRatio (landscape=$targetLandscape) " +
            "displayLong=$displayLongEdgePx → $chosen")
        return chosen
    }
}
