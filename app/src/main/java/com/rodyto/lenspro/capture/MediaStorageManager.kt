package com.rodyto.lenspro

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MediaStorageManager v3.1
 *
 * NUEVO:
 *  • `organizeByDate` ahora es VAR (no val), permite cambiarlo en runtime
 *    desde el toggle de SettingsActivity sin recrear el manager.
 *  • saveRaw mejorado: si bytes vacíos, retorna null sin crear MediaStore entry
 *    huérfano.
 */
class MediaStorageManager(@Volatile var organizeByDate: Boolean = true) {

    companion object {
        private const val TAG = "RodytoLensPro"
        private const val FOLDER_ROOT = "LensPro"
        private const val MIN_VALID_VIDEO_BYTES = 4 * 1024L
    }

    private fun relativePath(): String {
        if (!organizeByDate) return "DCIM/$FOLDER_ROOT"
        val sub = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "DCIM/$FOLDER_ROOT/$sub"
    }

    fun saveJpeg(context: Context, bytes: ByteArray): Uri? {
        if (bytes.isEmpty()) return null
        val resolver = context.contentResolver
        val filename = "IMG_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath())
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        var uri: Uri? = null
        return try {
            uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore insert returned null")
            resolver.openOutputStream(uri)?.use { out ->
                out.write(bytes); out.flush()
            } ?: throw IllegalStateException("OutputStream null for $uri")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            }
            Log.d(TAG, "JPEG guardado: $uri (${bytes.size / 1024} KB)")
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando JPEG", e)
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            null
        }
    }

    fun saveRaw(context: Context, bytes: ByteArray): Uri? {
        if (bytes.isEmpty()) return null
        val resolver = context.contentResolver
        val filename = "IMG_${System.currentTimeMillis()}.dng"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath())
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        var uri: Uri? = null
        return try {
            uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes); it.flush() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            }
            Log.d(TAG, "DNG guardado: $uri (${bytes.size / 1024} KB)")
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando DNG", e)
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            null
        }
    }

    fun createVideoUri(context: Context): Uri? {
        val filename = "VID_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath())
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        return try {
            context.contentResolver.insert(collection, values)
        } catch (e: Exception) {
            Log.e(TAG, "Error creando Uri de video", e); null
        }
    }

    fun openVideoFd(context: Context, uri: Uri): ParcelFileDescriptor? = try {
        context.contentResolver.openFileDescriptor(uri, "rw")
    } catch (e: Exception) { Log.e(TAG, "Error abriendo FD de video", e); null }

    fun finalizeVideo(context: Context, uri: Uri) { finalizeVideo(context, uri, null) }

    fun finalizeVideo(context: Context, uri: Uri, fd: ParcelFileDescriptor?): Boolean {
        try { fd?.close() } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val sizeOk = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    pfd.statSize >= MIN_VALID_VIDEO_BYTES
                } ?: false
                if (!sizeOk) {
                    Log.w(TAG, "Video < ${MIN_VALID_VIDEO_BYTES}B → eliminando $uri")
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    return false
                }
            } catch (e: Exception) {
                Log.w(TAG, "No pude validar tamaño del video, asumo válido", e)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error finalizando video", e); return false
            }
        }
        return true
    }

    fun deleteUri(context: Context, uri: Uri?) {
        if (uri == null) return
        runCatching { context.contentResolver.delete(uri, null, null) }
    }
}
