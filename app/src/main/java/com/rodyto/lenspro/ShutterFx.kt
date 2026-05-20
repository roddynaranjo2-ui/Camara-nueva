package com.rodyto.lenspro

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/**
 * ShutterFx v2.1 — Motor de sonido sintetizado para feedback de captura.
 *
 * FIX A10: usábamos `File.createTempFile + deleteOnExit` + `pool.load(path)`.
 * SoundPool.load es ASÍNCRONO: si el GC corre `deleteOnExit` antes de que
 * SoundPool termine de leer el archivo → sonido vacío. Ahora:
 *   1. Creamos el WAV temporal en context.cacheDir (BUG-C1).
 *   2. Cargamos vía path en SoundPool.
 *   3. Registramos OnLoadCompleteListener.
 *   4. SOLO cuando SoundPool confirma "loaded", borramos el archivo.
 *   5. Si carga falla, lo borramos también para no dejar basura.
 */
class ShutterFx(private val context: Context) {

    companion object {
        private const val TAG = "ShutterFx"
    }

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    // FIX A10: mapeo sound_id → archivo temporal asociado, para limpiarlo solo
    // tras el evento onLoadComplete.
    private val pendingTempFiles = ConcurrentHashMap<Int, File>()

    init {
        pool.setOnLoadCompleteListener { sp, sampleId, status ->
            // Borrar el WAV temporal ahora que SoundPool lo tiene en memoria
            val f = pendingTempFiles.remove(sampleId)
            try { f?.delete() } catch (_: Throwable) {}
            if (status != 0) {
                Log.w(TAG, "SoundPool load failed for sample $sampleId with status $status")
            }
        }
    }

    private val shutterId: Int = generateClickPcm(durationMs = 35, frequencyHz = 1800.0, decay = 0.92)
    private val videoStartId: Int = generateChirpPcm(durationMs = 120, startHz = 600.0, endHz = 1400.0)
    private val videoStopId: Int = generateChirpPcm(durationMs = 120, startHz = 1400.0, endHz = 600.0)
    private val focusId: Int = generateClickPcm(durationMs = 18, frequencyHz = 2400.0, decay = 0.85)

    fun shutter()    { safePlay(shutterId, 1.0f, 1.0f) }
    fun videoStart() { safePlay(videoStartId, 0.95f, 1.0f) }
    fun videoStop()  { safePlay(videoStopId, 0.95f, 1.0f) }
    fun focusTick()  { safePlay(focusId, 0.6f, 1.0f) }

    fun release() {
        try { pool.release() } catch (_: Throwable) {}
        // Limpieza extra de cualquier residuo
        pendingTempFiles.values.forEach { runCatching { it.delete() } }
        pendingTempFiles.clear()
    }

    private fun safePlay(id: Int, vol: Float, rate: Float) {
        if (id == 0) return
        try { pool.play(id, vol, vol, 1, 0, rate) } catch (_: Throwable) {}
    }

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
                amp *= decay.pow(1.0 / 64.0)
            }
            loadPcmAsWav(pcm, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating click PCM", e)
            0
        }
    }

    private fun generateChirpPcm(durationMs: Int, startHz: Double, endHz: Double): Int {
        return try {
            val sampleRate = 44_100
            val samples = (sampleRate * durationMs / 1000).coerceAtLeast(64)
            val pcm = ShortArray(samples)
            for (i in 0 until samples) {
                val t = i.toDouble() / sampleRate
                val freq = startHz + (endHz - startHz) * (i.toDouble() / samples)
                val env = Math.sin(Math.PI * (i.toDouble() / samples))
                val v = Math.sin(2.0 * Math.PI * freq * t) * env * Short.MAX_VALUE.toDouble() * 0.6
                pcm[i] = v.toInt().coerceIn(-32_767, 32_767).toShort()
            }
            loadPcmAsWav(pcm, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating chirp PCM", e)
            0
        }
    }

    /**
     * Empaqueta el PCM en formato WAV (RIFF) y lo carga en SoundPool.
     * BUG-C1: Usamos context.cacheDir para garantizar escritura en todos los dispositivos.
     */
    private fun loadPcmAsWav(pcm: ShortArray, sampleRate: Int): Int {
        val byteCount = pcm.size * 2
        val totalSize = 36 + byteCount
        val header = ByteArray(44)
        header[0]='R'.code.toByte(); header[1]='I'.code.toByte(); header[2]='F'.code.toByte(); header[3]='F'.code.toByte()
        writeInt(header, 4, totalSize)
        header[8]='W'.code.toByte(); header[9]='A'.code.toByte(); header[10]='V'.code.toByte(); header[11]='E'.code.toByte()
        header[12]='f'.code.toByte(); header[13]='m'.code.toByte(); header[14]='t'.code.toByte(); header[15]=' '.code.toByte()
        writeInt(header, 16, 16)
        writeShort(header, 20, 1)
        writeShort(header, 22, 1)
        writeInt(header, 24, sampleRate)
        writeInt(header, 28, sampleRate * 2)
        writeShort(header, 32, 2)
        writeShort(header, 34, 16)
        header[36]='d'.code.toByte(); header[37]='a'.code.toByte(); header[38]='t'.code.toByte(); header[39]='a'.code.toByte()
        writeInt(header, 40, byteCount)

        val data = ByteArray(byteCount)
        for (i in pcm.indices) {
            val s = pcm[i].toInt()
            data[i * 2]     = (s and 0xFF).toByte()
            data[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }

        val tmp = try {
            File.createTempFile("lp_fx_", ".wav", context.cacheDir).also {
                it.deleteOnExit()
            }
        } catch (e: IOException) {
            Log.e(TAG, "No se pudo crear WAV temporal en cacheDir", e)
            return 0
        }

        try {
            FileOutputStream(tmp).use { os ->
                os.write(header); os.write(data); os.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error escribiendo WAV temporal", e)
            runCatching { tmp.delete() }
            return 0
        }

        val soundId = pool.load(tmp.absolutePath, 1)
        if (soundId > 0) {
            pendingTempFiles[soundId] = tmp
        } else {
            runCatching { tmp.delete() }
        }
        return soundId
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
}
