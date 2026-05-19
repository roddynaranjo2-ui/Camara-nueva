package com.rodyto.lenspro

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.media.ImageReader
import android.util.Log
import android.view.Surface

/**
 * MultiChannelImageReader v3.6 — OPTIMIZADO
 *
 * Cambio v3.6 (sobre v2):
 *  • lastCameraIdHash sustituido por lastCameraId: String.
 *    Justificación: characteristics.hashCode() NO es estable entre
 *    instancias devueltas por CameraManager.getCameraCharacteristics(id).
 *    Aunque sea el mismo cameraId lógico, dos objetos
 *    CameraCharacteristics distintos (p. ej. tras una reapertura) pueden
 *    devolver hashCodes diferentes → el guard idempotente fallaba en
 *    silencio y se recreaban los ImageReader durante capturas en vuelo.
 *  • Nuevo overload configure(cameraId, characteristics, …) que es el
 *    canónico. El antiguo configure(characteristics, …) se preserva
 *    como wrapper backward-compatible — pero internamente usa una
 *    pseudoId derivada del propio characteristics (no del hashCode).
 */
class MultiChannelImageReader {

    companion object { private const val TAG = "MultiChannelImageReader" }

    var jpeg: ImageReader? = null; private set
    var yuv: ImageReader? = null; private set
    var raw: ImageReader? = null; private set

    // Estado de la última config — clave estable por cameraId STRING
    private var lastCameraId: String = ""
    private var lastWantRaw: Boolean = false
    private var lastWantYuv: Boolean = false

    fun targetSurfaces(): List<Surface> =
        listOfNotNull(jpeg?.surface, yuv?.surface, raw?.surface)

    fun release() {
        runCatching { jpeg?.close() }
        runCatching { yuv?.close() }
        runCatching { raw?.close() }
        jpeg = null; yuv = null; raw = null
        lastCameraId = ""; lastWantRaw = false; lastWantYuv = false
    }

    /**
     * configure canónico v3.6 — recibe el cameraId String estable.
     * IDEMPOTENTE: si la combinación ya está activa, no se recrea nada.
     */
    fun configure(
        cameraId: String,
        characteristics: CameraCharacteristics,
        wantRaw: Boolean,
        wantYuv: Boolean
    ) {
        if (cameraId.isNotEmpty() &&
            cameraId == lastCameraId &&
            wantRaw == lastWantRaw &&
            wantYuv == lastWantYuv &&
            jpeg != null
        ) {
            Log.d(TAG, "configure: skip (cameraId=$cameraId, raw=$wantRaw, yuv=$wantYuv)")
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

        // ── YUV (histograma) — small ──
        if (wantYuv) {
            val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
            val targetYuv = yuvSizes?.firstOrNull { it.width <= 640 }
                ?: yuvSizes?.minByOrNull { it.width.toLong() * it.height }
            if (targetYuv != null) {
                yuv = ImageReader.newInstance(
                    targetYuv.width, targetYuv.height,
                    ImageFormat.YUV_420_888, 3
                )
            }
        }

        // ── RAW (opcional) ──
        if (wantRaw) {
            try {
                val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
                val biggestRaw = rawSizes?.maxByOrNull { it.width.toLong() * it.height }
                if (biggestRaw != null) {
                    raw = ImageReader.newInstance(
                        biggestRaw.width, biggestRaw.height,
                        ImageFormat.RAW_SENSOR, 2
                    )
                }
            } catch (e: Throwable) {
                Log.w(TAG, "RAW no soportado en este dispositivo", e)
            }
        }

        lastCameraId = cameraId
        lastWantRaw = wantRaw
        lastWantYuv = wantYuv
    }

    /**
     * Backward-compat: el VM antiguo llamaba sin cameraId. Generamos una
     * pseudo-id ESTABLE basada en métricas del propio characteristics
     * (sensor size + orientation + capabilities) — NO depende de hashCode.
     */
    fun configure(
        characteristics: CameraCharacteristics,
        wantRaw: Boolean,
        wantYuv: Boolean
    ) {
        val pseudoId = derivePseudoId(characteristics)
        configure(pseudoId, characteristics, wantRaw, wantYuv)
    }

    /**
     * Pseudo-id estable derivada de propiedades inmutables del sensor.
     * Útil cuando el caller no nos pasa el cameraId real.
     */
    private fun derivePseudoId(ch: CameraCharacteristics): String {
        val sensor = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val orient = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1
        val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: -1
        val sw = sensor?.width ?: 0f
        val sh = sensor?.height ?: 0f
        return "facing=$facing|orient=$orient|sw=${"%.2f".format(sw)}|sh=${"%.2f".format(sh)}"
    }

    fun supportsRaw(characteristics: CameraCharacteristics?): Boolean {
        val caps = characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return false
        return caps.any { it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW }
    }
}
