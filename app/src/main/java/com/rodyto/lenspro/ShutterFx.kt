package com.rodyto.lenspro

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool

/**
 * ShutterFx — Motor de sonido sintetizado para feedback de captura.
 *
 * Implementación 100 % en código (sin .ogg/.mp3 en res/raw) para que el APK
 * siga compilando aunque la UI no se modifique. Genera "clicks" cortos vía
 * SoundPool y un tono ultra-breve en PCM in-memory. En caso de fallo no
 * lanza excepción: el método se vuelve un no-op para no bloquear la UI.
 *
 * Métodos invocados desde MainActivity.kt:
 *   - shutter()      → click foto
 *   - videoStart()   → tono ascendente corto (inicio grabación)
 *   - videoStop()    → tono descendente corto (fin grabación)
 *   - focusTick()    → micro-tick (tap-to-focus)
 *   - release()      → libera el SoundPool al onDispose
 *
 * Los PCM se generan en el constructor — coste único (~3 ms) y luego
 * cero locks ni IO durante la captura.
 */
class ShutterFx {

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val shutterId: Int = generateClickPcm(durationMs = 35, frequencyHz = 1800.0, decay = 0.92)
    private val videoStartId: Int = generateChirpPcm(durationMs = 120, startHz = 600.0, endHz = 1400.0)
    private val videoStopId: Int = generateChirpPcm(durationMs = 120, startHz = 1400.0, endHz = 600.0)
    private val focusId: Int = generateClickPcm(durationMs = 18, frequencyHz = 2400.0, decay = 0.85)

    fun shutter()    { safePlay(shutterId, 1.0f, 1.0f) }
    fun videoStart() { safePlay(videoStartId, 0.95f, 1.0f) }
    fun videoStop()  { safePlay(videoStopId, 0.95f, 1.0f) }
    fun focusTick()  { safePlay(focusId, 0.6f, 1.0f) }

    fun release() {
        try { pool.release() } catch (_: Throwable) { /* swallow */ }
    }

    private fun safePlay(id: Int, vol: Float, rate: Float) {
        if (id == 0) return
        try {
            pool.play(id, vol, vol, 1, 0, rate)
        } catch (_: Throwable) {
            // No interrumpir la captura por audio
        }
    }

    // ------------------------------------------------------------
    //  PCM in-memory helpers
    // ------------------------------------------------------------

    /**
     * Genera un PCM 16-bit, 44.1 kHz mono con onda senoidal y envolvente
     * exponencial. Devuelve el ID de SoundPool (>0) o 0 si falla.
     */
    private fun generateClickPcm(durationMs: Int, frequencyHz: Double, decay: Double): Int {
        return try {
            val sampleRate = 44_100
            val samples = (sampleRate * durationMs / 1000).coerceAtLeast(64)
            val pcm = ShortArray(samples)
            var amp = 1.0
            for (i in 0 until samples) {
                val t = i.toDouble() / sampleRate
                val v = Math.sin(2.0 * Math.PI * frequencyHz * t) * amp * Short.MAX_VALUE.toDouble() * 0.6
                pcm[i] = v.toInt().coerceIn(-32_767, 32_767).toShort()
                amp *= decay.pow(1.0 / 64.0) // suave decay
            }
            loadPcmAsWav(pcm, sampleRate)
        } catch (_: Throwable) { 0 }
    }

    /**
     * Genera un "chirp" PCM (frecuencia lineal de start→end).
     */
    private fun generateChirpPcm(durationMs: Int, startHz: Double, endHz: Double): Int {
        return try {
            val sampleRate = 44_100
            val samples = (sampleRate * durationMs / 1000).coerceAtLeast(64)
            val pcm = ShortArray(samples)
            for (i in 0 until samples) {
                val t = i.toDouble() / sampleRate
                val freq = startHz + (endHz - startHz) * (i.toDouble() / samples)
                val env = Math.sin(Math.PI * (i.toDouble() / samples)) // ventana Hann
                val v = Math.sin(2.0 * Math.PI * freq * t) * env * Short.MAX_VALUE.toDouble() * 0.6
                pcm[i] = v.toInt().coerceIn(-32_767, 32_767).toShort()
            }
            loadPcmAsWav(pcm, sampleRate)
        } catch (_: Throwable) { 0 }
    }

    /**
     * Empaqueta el PCM en formato WAV (RIFF) y lo carga en SoundPool
     * mediante un archivo temporal en cache (única vía pública soportada).
     */
    private fun loadPcmAsWav(pcm: ShortArray, sampleRate: Int): Int {
        val byteCount = pcm.size * 2
        val totalSize = 36 + byteCount
        val header = ByteArray(44)
        // RIFF
        header[0]='R'.code.toByte(); header[1]='I'.code.toByte(); header[2]='F'.code.toByte(); header[3]='F'.code.toByte()
        writeInt(header, 4, totalSize)
        header[8]='W'.code.toByte(); header[9]='A'.code.toByte(); header[10]='V'.code.toByte(); header[11]='E'.code.toByte()
        // fmt
        header[12]='f'.code.toByte(); header[13]='m'.code.toByte(); header[14]='t'.code.toByte(); header[15]=' '.code.toByte()
        writeInt(header, 16, 16)         // PCM chunk size
        writeShort(header, 20, 1)        // PCM
        writeShort(header, 22, 1)        // mono
        writeInt(header, 24, sampleRate)
        writeInt(header, 28, sampleRate * 2) // byte rate
        writeShort(header, 32, 2)        // block align
        writeShort(header, 34, 16)       // bits
        // data
        header[36]='d'.code.toByte(); header[37]='a'.code.toByte(); header[38]='t'.code.toByte(); header[39]='a'.code.toByte()
        writeInt(header, 40, byteCount)

        val data = ByteArray(byteCount)
        for (i in pcm.indices) {
            val s = pcm[i].toInt()
            data[i * 2]     = (s and 0xFF).toByte()
            data[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }

        val tmp = java.io.File.createTempFile("lp_fx_", ".wav")
        tmp.deleteOnExit()
        java.io.FileOutputStream(tmp).use { os ->
            os.write(header); os.write(data); os.flush()
        }
        return pool.load(tmp.absolutePath, 1)
    }

    private fun writeInt(buf: ByteArray, off: Int, v: Int) {
        buf[off]     = (v        and 0xFF).toByte()
        buf[off + 1] = ((v shr  8) and 0xFF).toByte()
        buf[off + 2] = ((v shr 16) and 0xFF).toByte()
        buf[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun writeShort(buf: ByteArray, off: Int, v: Int) {
        buf[off]     = (v        and 0xFF).toByte()
        buf[off + 1] = ((v shr  8) and 0xFF).toByte()
    }

    private fun Double.pow(p: Double): Double = Math.pow(this, p)
}
