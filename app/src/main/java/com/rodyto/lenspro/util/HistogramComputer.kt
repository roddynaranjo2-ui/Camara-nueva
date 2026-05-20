package com.rodyto.lenspro.util

import android.media.Image
import java.nio.ByteBuffer

/**
 * HistogramComputer — punto 20 (Composición / Histograma en tiempo real).
 *
 * Calcula un histograma de 64 bins a partir del plano Y de un YUV_420_888,
 * muestreando 1 de cada N píxeles para mantener < 2 ms por frame en CPU.
 *
 * Patrón conceptual extraído de SimpleRawCamera (FrameView). Re-implementado
 * para Kotlin / Compose con back-pressure: si el cálculo previo aún no terminó,
 * se descarta el nuevo frame.
 */
object HistogramComputer {

    private const val BINS = 64
    private const val SAMPLE_STEP = 8 // 1 de cada 8 píxeles

    @Volatile private var inFlight = false

    /**
     * @return IntArray de tamaño 64 con conteos, o null si el cálculo anterior sigue activo.
     */
    fun computeY(image: Image): IntArray? {
        if (inFlight) return null
        inFlight = true
        return try {
            val plane = image.planes.getOrNull(0) ?: return null
            val buf: ByteBuffer = plane.buffer
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height
            val hist = IntArray(BINS)
            val pixelStride = plane.pixelStride

            var y = 0
            while (y < height) {
                var x = 0
                val base = y * rowStride
                while (x < width) {
                    val idx = base + x * pixelStride
                    if (idx < buf.limit()) {
                        val luma = buf.get(idx).toInt() and 0xFF
                        val bin = (luma * BINS) ushr 8
                        hist[bin]++
                    }
                    x += SAMPLE_STEP
                }
                y += SAMPLE_STEP
            }
            hist
        } catch (_: Throwable) {
            null
        } finally {
            inFlight = false
        }
    }
}
