package com.rodyto.lenspro

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.media.ImageReader
import android.util.Log
import android.util.Size
import android.view.Surface

/**
 * MultiChannelImageReader v2 — buffer multicanal YUV/JPEG/RAW.
 *
 * FIX N1: configure() ahora detecta si la configuración pedida es IDÉNTICA a
 * la actual y, si lo es, NO libera ni recrea los readers — evitando que un
 * jpegReader.surface vivo (con captura en vuelo) sea cerrado de golpe.
 *
 * Mantiene hasta 3 ImageReader en paralelo:
 *   • JPEG  → resultado final (capture STILL).
 *   • YUV   → análisis on-the-fly (histograma + AE feedback).
 *   • RAW   → opcional (sensor SENSOR_INFO_COLOR_FILTER_ARRANGEMENT).
 */
class MultiChannelImageReader {

    companion object { private const val TAG = "MultiChannelImageReader" }

    var jpeg: ImageReader? = null; private set
    var yuv: ImageReader? = null; private set
    var raw: ImageReader? = null; private set

    // Estado de la última config — para idempotencia
    private var lastCameraIdHash: Int = 0
    private var lastWantRaw: Boolean = false
    private var lastWantYuv: Boolean = false

    fun targetSurfaces(): List<Surface> =
        listOfNotNull(jpeg?.surface, yuv?.surface, raw?.surface)

    fun release() {
        runCatching { jpeg?.close() }
        runCatching { yuv?.close() }
        runCatching { raw?.close() }
        jpeg = null; yuv = null; raw = null
        lastCameraIdHash = 0; lastWantRaw = false; lastWantYuv = false
    }

    /**
     * Construye los readers a partir de las características.
     * @param wantRaw activa el reader RAW si está soportado.
     * @param wantYuv activa el reader YUV (histograma).
     *
     * Idempotente: si la combinación ya está activa para la misma cámara, no
     * se hace nada (evita race contra capturas en vuelo).
     */
    fun configure(
        characteristics: CameraCharacteristics,
        wantRaw: Boolean,
        wantYuv: Boolean
    ) {
        val idHash = characteristics.hashCode()
        // FIX N1: si misma cámara + mismos flags, no recrear
        if (idHash == lastCameraIdHash &&
            wantRaw == lastWantRaw &&
            wantYuv == lastWantYuv &&
            jpeg != null) {
            Log.d(TAG, "configure: skip (mismo estado)")
            return
        }
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

        lastCameraIdHash = idHash
        lastWantRaw = wantRaw
        lastWantYuv = wantYuv
    }

    fun supportsRaw(characteristics: CameraCharacteristics?): Boolean {
        val caps = characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return false
        return caps.any { it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW }
    }
}
