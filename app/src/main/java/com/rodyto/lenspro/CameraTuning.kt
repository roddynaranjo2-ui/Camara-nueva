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
 * CameraTuning v2.2 — perfil de Image-Quality + Codec helpers + FPS hardening
 *  + orientación EXIF dinámica + selección óptima de tamaño de preview.
 *
 * Cambios v2.2:
 *  • Nuevo helper pickOptimalPreviewSize(characteristics, targetRatio): selecciona
 *    el mejor Size de preview compatible con SurfaceHolder/SurfaceTexture acorde al
 *    aspect ratio solicitado (sin estirar). Usa el StreamConfigurationMap y filtra
 *    por área razonable (≤ 1920×1080) para evitar buffers gigantes en preview.
 *  • Nueva utilidad MAX_PREVIEW_AREA para acotar resoluciones de preview.
 */
object CameraTuning {

    private const val TAG = "CameraTuning"

    /** Área máxima recomendada para previews (1080p) — buffers > 1080p degradan FPS. */
    private const val MAX_PREVIEW_AREA: Long = 1920L * 1080L

    /** Tolerancia para considerar dos aspect ratios "iguales" (porcentaje). */
    private const val ASPECT_TOLERANCE = 0.04f

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

    /**
     * Backward-compat: si no se pasa rotation, asume 0 (mantengo firma por compatibilidad).
     * Recomendado: usar applyJpegOrientationDynamic().
     */
    fun applyJpegOrientation(b: CaptureRequest.Builder, sensorOrientation: Int, isFront: Boolean) {
        applyJpegOrientationDynamic(b, sensorOrientation, isFront, 0)
    }

    /**
     * FIX A8: orientación EXIF DINÁMICA — usa la rotación real del display.
     * En CameraControlViewModel guardamos `deviceDisplayRotation` y MainActivity
     * lo alimenta vía `notifyDeviceRotation()`.
     *
     * Tabla de display rotations (Android):
     *   Surface.ROTATION_0   → 0°
     *   Surface.ROTATION_90  → 90°
     *   Surface.ROTATION_180 → 180°
     *   Surface.ROTATION_270 → 270°
     */
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
        ranges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it }
        ranges.filter { it.upper == fps }.maxByOrNull { it.lower }?.let { return it }
        ranges.filter { it.lower <= fps && it.upper >= fps }
            .minByOrNull { it.upper - it.lower }?.let { return it }
        ranges.filter { it.upper >= fps }
            .minByOrNull { kotlin.math.abs(it.upper - fps) }?.let { return it }
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
        val targetArea = target.width.toLong() * target.height
        return sizes
            .filter { it.width.toLong() * it.height <= targetArea }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.first()
    }

    /**
     * Selecciona el Size de preview óptimo según el aspect ratio solicitado.
     *
     * @param characteristics CameraCharacteristics de la cámara abierta. Si es null
     *                        devuelve un fallback razonable.
     * @param targetRatio     Aspect ratio LANDSCAPE (width/height del SENSOR).
     *                        Ej. 3f/4f para retrato 3:4 → en sensor landscape se mapea a 4:3.
     *                        Internamente se normaliza para que sea siempre ≥ 1.
     *
     * Estrategia:
     *  1) Obtiene los outputSizes para SurfaceHolder (la preview va a un SurfaceView).
     *  2) Filtra por área ≤ MAX_PREVIEW_AREA (1080p) — evita buffers gigantes que
     *     destrozan el FPS y el consumo térmico.
     *  3) Busca el Size cuyo aspect (w/h, normalizado landscape) más se acerque al
     *     target dentro de la tolerancia.
     *  4) Entre los candidatos, escoge el de mayor área (mejor calidad de preview).
     *  5) Fallback: el de mayor área dentro del límite si no hay match exacto.
     */
    fun pickOptimalPreviewSize(
        characteristics: CameraCharacteristics?,
        targetRatio: Float
    ): Size {
        // Fallback inicial razonable (1920x1080 landscape)
        val fallback = Size(1920, 1080)

        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return fallback

        // Normalizar: targetRatio puede venir como 3f/4f (portrait). Para sensor (landscape)
        // siempre queremos el ratio en formato landscape (>= 1f).
        val targetLandscape = if (targetRatio < 1f) 1f / targetRatio else targetRatio
        if (targetLandscape <= 0f || !targetLandscape.isFinite()) return fallback

        // Reunir candidatos: SurfaceHolder (preview directo) y SurfaceTexture (compat).
        val holderSizes: Array<Size> = try {
            map.getOutputSizes(SurfaceHolder::class.java) ?: emptyArray()
        } catch (_: Throwable) { emptyArray() }

        val textureSizes: Array<Size> = try {
            map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        } catch (_: Throwable) { emptyArray() }

        val all: List<Size> = (holderSizes.toList() + textureSizes.toList())
            .distinct()
            .filter { it.width.toLong() * it.height.toLong() <= MAX_PREVIEW_AREA }

        if (all.isEmpty()) {
            Log.w(TAG, "pickOptimalPreviewSize: sin candidatos válidos → fallback $fallback")
            return fallback
        }

        // Filtrar por aspect ratio cercano al objetivo (tolerancia)
        val matched = all.filter {
            val r = it.width.toFloat() / it.height.toFloat()
            abs(r - targetLandscape) / targetLandscape <= ASPECT_TOLERANCE
        }

        val pool = if (matched.isNotEmpty()) matched else all

        // Mayor área dentro del pool elegido
        val chosen = pool.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: fallback

        Log.d(TAG, "pickOptimalPreviewSize: target=$targetRatio (landscape=$targetLandscape) → $chosen")
        return chosen
    }
}
