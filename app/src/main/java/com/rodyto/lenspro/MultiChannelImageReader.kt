package com.rodyto.lenspro

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.media.ImageReader
import android.util.Log
import android.util.Size
import android.view.Surface

/**
 * MultiChannelImageReader — punto 8 del informe (buffer multicanal YUV/JPEG/RAW).
 *
 * Mantiene hasta 3 ImageReader en paralelo:
 *   • JPEG  → resultado final (capture STILL).
 *   • YUV   → análisis on-the-fly (histograma + AE feedback).
 *   • RAW   → opcional (sensor SENSOR_INFO_COLOR_FILTER_ARRANGEMENT).
 *
 * Idempotente: si una llamada try-acquire un YUV mientras no hay listener, libera el frame.
 * Cada Reader corre en el cameraHandler global → no roba CPU al UI thread.
 */
class MultiChannelImageReader {

    companion object { private const val TAG = "MultiChannelImageReader" }

    var jpeg: ImageReader? = null; private set
    var yuv: ImageReader? = null; private set
    var raw: ImageReader? = null; private set

    fun targetSurfaces(): List<Surface> =
        listOfNotNull(jpeg?.surface, yuv?.surface, raw?.surface)

    fun release() {
        runCatching { jpeg?.close() }
        runCatching { yuv?.close() }
        runCatching { raw?.close() }
        jpeg = null; yuv = null; raw = null
    }

    /**
     * Construye los readers a partir de las características.
     * @param wantRaw activa el reader RAW si está soportado.
     * @param wantYuv activa el reader YUV (histograma).
     */
    fun configure(
        characteristics: CameraCharacteristics,
        wantRaw: Boolean,
        wantYuv: Boolean
    ) {
        release()
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        // ── JPEG (siempre) ──
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
        val largestJpeg = jpegSizes?.maxByOrNull { it.width.toLong() * it.height }
        if (largestJpeg != null) {
            jpeg = ImageReader.newInstance(largestJpeg.width, largestJpeg.height, ImageFormat.JPEG, 2)
        }

        // ── YUV (histograma) — pequeño, no necesita ser fullsize ──
        if (wantYuv) {
            val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
            val targetYuv = yuvSizes?.firstOrNull { it.width <= 640 }
                ?: yuvSizes?.minByOrNull { it.width.toLong() * it.height }
            if (targetYuv != null) {
                yuv = ImageReader.newInstance(targetYuv.width, targetYuv.height,
                    ImageFormat.YUV_420_888, 3)
            }
        }

        // ── RAW (opcional) ──
        if (wantRaw) {
            try {
                val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
                val biggestRaw = rawSizes?.maxByOrNull { it.width.toLong() * it.height }
                if (biggestRaw != null) {
                    raw = ImageReader.newInstance(biggestRaw.width, biggestRaw.height,
                        ImageFormat.RAW_SENSOR, 2)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "RAW no soportado en este dispositivo", e)
            }
        }
    }

    fun supportsRaw(characteristics: CameraCharacteristics?): Boolean {
        val caps = characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return false
        return caps.any { it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW }
    }
}
