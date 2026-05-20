package com.rodyto.lenspro.capture

import com.rodyto.lenspro.VideoResolution

/**
 * VideoBitrateCalculator — Réplica afinada de la fórmula Samsung One UI.
 *
 * Bitrate(bps) = area × fps × bitsPerPixelPerFrame × hdrBoost
 *
 * Topes empíricos (ajustados para evitar lag en encoders mid-range):
 *   HD30:   6  Mb/s  /  HD60:  10 Mb/s
 *   FHD30: 14  Mb/s  /  FHD60: 22 Mb/s
 *   4K30:  42  Mb/s  /  4K60:  64 Mb/s
 */
object VideoBitrateCalculator {

    enum class Codec { H264, HEVC }

    fun compute(
        width: Int,
        height: Int,
        fps: Int,
        codec: Codec = Codec.H264,
        hdr: Boolean = false
    ): Int {
        val area = (width.toLong() * height.toLong()).coerceAtLeast(1)
        val bitsPerPixelPerFrame = when (codec) {
            Codec.H264 -> 0.085   // antes 0.10 → bajado 15% (encoder se asfixiaba en mid-range)
            Codec.HEVC -> 0.052   // antes 0.06
        }
        val raw = area.toDouble() * fps.toDouble() * bitsPerPixelPerFrame
        val boosted = if (hdr) raw * 1.25 else raw
        val clamped = boosted.toLong().coerceIn(2_000_000L, 80_000_000L)
        return clamped.toInt()
    }

    fun preset(res: VideoResolution, fps: Int, codec: Codec = Codec.H264): Int {
        val key = "${res.label}-$fps-${codec.name}"
        return PRESETS[key] ?: compute(res.width, res.height, fps, codec)
    }

    /**
     * NUEVO: bitrate adaptativo. Si el dispositivo declara una capacidad de encoder
     * por debajo del preset, usa el más bajo. (Llamarlo opcional desde el VM.)
     */
    fun safePreset(res: VideoResolution, fps: Int, codec: Codec, encoderMaxBps: Int): Int {
        val target = preset(res, fps, codec)
        return if (encoderMaxBps in 1_000_000..target) encoderMaxBps else target
    }

    private val PRESETS: Map<String, Int> = mapOf(
        // === H264 (ajustados a la baja para mid-range) ===
        "HD-30-H264"  to  6_000_000,
        "HD-60-H264"  to 10_000_000,
        "FHD-30-H264" to 14_000_000,
        "FHD-60-H264" to 22_000_000,
        "4K-30-H264"  to 42_000_000,
        "4K-60-H264"  to 64_000_000,
        // === HEVC ===
        "HD-30-HEVC"  to  4_000_000,
        "HD-60-HEVC"  to  6_500_000,
        "FHD-30-HEVC" to  9_000_000,
        "FHD-60-HEVC" to 14_000_000,
        "4K-30-HEVC"  to 25_000_000,
        "4K-60-HEVC"  to 40_000_000
    )
}
