package com.rodyto.lenspro.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import com.rodyto.lenspro.VideoResolution
import com.rodyto.lenspro.capture.MediaStorageManager
import com.rodyto.lenspro.capture.VideoBitrateCalculator
import com.rodyto.lenspro.tuning.CameraTuning

/**
 * VideoRecordingController · v1.0
 *
 * FIX BUG-M1: grabación de vídeo real. Diseñado para acoplarse a una
 * CameraCaptureSession Camera2 existente — recibe los parámetros del HAL
 * y devuelve el Surface del MediaRecorder al SessionController para que
 * lo añada a la session junto con el preview.
 *
 * Flujo:
 *   1) prepareRecorder(...) → crea MediaRecorder + URI → retorna Surface
 *   2) SessionController crea CaptureSession [preview, recorderSurface]
 *   3) start() → MediaRecorder.start()
 *   4) stop() → MediaRecorder.stop() + MediaStore.IS_PENDING = 0
 *   5) abortIfActive() / release() para limpieza segura
 */
class VideoRecordingController(
    private val context: Context,
    private val storage: MediaStorageManager = MediaStorageManager()
) {
    companion object { private const val TAG = "VideoRec" }

    private var recorder: MediaRecorder? = null
    private var fd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var recording = false
    private var lastResolution: VideoResolution = VideoResolution.FHD
    private var lastFps: Int = 30
    private var lastHevc: Boolean = false

    fun prepareRecorder(
        characteristics: CameraCharacteristics?,
        isFrontCamera: Boolean,
        resolution: VideoResolution = VideoResolution.FHD,
        fps: Int = 30,
        allowHevc: Boolean = false
    ): Surface? {
        if (recording) {
            Log.w(TAG, "prepareRecorder: ya hay grabación activa")
            return null
        }
        lastResolution = resolution
        lastFps = fps
        lastHevc = allowHevc

        val newUri = storage.createVideoUri(context) ?: run {
            Log.e(TAG, "No se pudo crear URI de vídeo")
            return null
        }
        val pfd = storage.openVideoFd(context, newUri) ?: run {
            storage.deleteUri(context, newUri); return null
        }

        val rec = createMediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(pfd.fileDescriptor)

            val recSize = CameraTuning.pickOptimalRecordingSize(characteristics, resolution)
            setVideoSize(recSize.width, recSize.height)
            setVideoFrameRate(fps)

            val codec = CameraTuning.preferredCodecLabel(allowHevc)
            val bitrate = VideoBitrateCalculator.preset(resolution, fps, codec)
            setVideoEncodingBitRate(bitrate)
            setAudioEncodingBitRate(192_000)
            setAudioSamplingRate(48_000)
            setVideoEncoder(CameraTuning.preferredEncoder(allowHevc))
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

            // Orientación: simplificada, asume portrait — para deviceRotation real
            // se debería leer DisplayManager y combinar con sensorOrientation.
            val sensorOri = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val outOri = if (isFrontCamera) (360 - sensorOri) % 360 else sensorOri
            setOrientationHint(outOri)
        }

        return try {
            rec.prepare()
            recorder = rec
            fd = pfd
            uri = newUri
            rec.surface
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder.prepare falló", e)
            runCatching { rec.release() }
            runCatching { pfd.close() }
            storage.deleteUri(context, newUri)
            recorder = null; fd = null; uri = null
            null
        }
    }

    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()

    fun start(): Boolean {
        val r = recorder ?: return false
        return try {
            r.start()
            recording = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder.start fallo", e)
            abortIfActive(); false
        }
    }

    fun stop() {
        val r = recorder ?: return
        if (!recording) { releaseInternal(); return }
        recording = false
        try { r.stop() } catch (e: Exception) { Log.w(TAG, "stop() warning", e) }
        val finalized = uri?.let { storage.finalizeVideo(context, it, fd) } ?: false
        if (!finalized) Log.w(TAG, "finalizeVideo: vídeo inválido o vacío")
        releaseInternal()
    }

    /** Aborta una grabación activa si la hay (cierre defensivo). */
    fun abortIfActive() {
        if (recorder == null) return
        try { if (recording) recorder?.stop() } catch (_: Throwable) {}
        recording = false
        uri?.let { storage.deleteUri(context, it) }
        releaseInternal()
    }

    private fun releaseInternal() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        runCatching { fd?.close() }
        recorder = null
        fd = null
        uri = null
    }

    fun release() { abortIfActive() }
}
