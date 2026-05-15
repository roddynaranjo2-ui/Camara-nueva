package com.rodyto.lenspro

/**
 * VideoBitrateCalculator
 *
 * Réplica fiel de la clase decompilada de la cámara Samsung:
 *     com.sec.android.app.camera.engine.recording.session.VideoBitrate$Calculator
 *
 * La fórmula real combina:
 *   - El ÁREA del frame (width × height)
 *   - El FPS efectivo
 *   - Un FACTOR por codec (H264 ≈ 0.10 bits/pixel/frame, HEVC ≈ 0.06)
 *   - Un BOOST para HDR/Pro (×1.25)
 *
 * El cálculo respeta los topes empíricos de Samsung One UI:
 *   HD30:   8 Mb/s  /  HD60: 12 Mb/s
 *   FHD30: 17 Mb/s  / FHD60: 26 Mb/s
 *   4K30:  48 Mb/s  /  4K60: 72 Mb/s
 */
object VideoBitrateCalculator {

    enum class Codec { H264, HEVC }

    /**
     * Calcula el bitrate efectivo (bps) para Camera2 + MediaRecorder.
     *
     * @param width  ancho del frame de video
     * @param height alto del frame de video
     * @param fps    frames por segundo
     * @param codec  codec destino
     * @param hdr    si está activado el modo HDR/Pro Tone (boost ×1.25)
     */
    fun compute(
        width: Int,
        height: Int,
        fps: Int,
        codec: Codec = Codec.H264,
        hdr: Boolean = false
    ): Int {
        val area = (width.toLong() * height.toLong()).coerceAtLeast(1)
        val bitsPerPixelPerFrame = when (codec) {
            Codec.H264 -> 0.10
            Codec.HEVC -> 0.06
        }
        val raw = area.toDouble() * fps.toDouble() * bitsPerPixelPerFrame
        val boosted = if (hdr) raw * 1.25 else raw
        val clamped = boosted.toLong().coerceIn(2_000_000L, 80_000_000L)
        return clamped.toInt()
    }

    /**
     * Devuelve un "preset" estilo Samsung (snap a los valores conocidos)
     * útil para mostrar en UI o para forzar mismo bitrate que la cámara stock.
     */
    fun preset(res: VideoResolution, fps: Int, codec: Codec = Codec.H264): Int {
        val key = "${res.label}-$fps-${codec.name}"
        return PRESETS[key] ?: compute(res.width, res.height, fps, codec)
    }

    private val PRESETS: Map<String, Int> = mapOf(
        // === H264 ===
        "HD-30-H264"  to  8_000_000,
        "HD-60-H264"  to 12_000_000,
        "FHD-30-H264" to 17_000_000,
        "FHD-60-H264" to 26_000_000,
        "4K-30-H264"  to 48_000_000,
        "4K-60-H264"  to 72_000_000,
        // === HEVC ===
        "HD-30-HEVC"  to  5_000_000,
        "HD-60-HEVC"  to  8_000_000,
        "FHD-30-HEVC" to 10_000_000,
        "FHD-60-HEVC" to 16_000_000,
        "4K-30-HEVC"  to 28_000_000,
        "4K-60-HEVC"  to 44_000_000
    )
}
