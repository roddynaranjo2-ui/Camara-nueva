package com.rodyto.lenspro

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log

class MediaStorageManager {

    companion object {
        private const val TAG = "RodytoLensPro"
        private const val FOLDER = "LensPro"
    }

    // ---------- IMÁGENES JPEG ----------
    fun saveJpeg(context: Context, bytes: ByteArray): Uri? {
        if (bytes.isEmpty()) return null

        val filename = "IMG_${System.currentTimeMillis()}.jpg"
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        var uri: Uri? = null
        return try {
            uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore insert returned null")

            resolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: throw IllegalStateException("OutputStream null for $uri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            }

            Log.d(TAG, "JPEG guardado: $uri")
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando JPEG", e)
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            null
        }
    }

    // ---------- VIDEO MP4 ----------
    fun createVideoUri(context: Context): Uri? {
        val filename = "VID_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return try {
            context.contentResolver.insert(collection, values)
        } catch (e: Exception) {
            Log.e(TAG, "Error creando Uri de video", e)
            null
        }
    }

    fun openVideoFd(context: Context, uri: Uri): ParcelFileDescriptor? =
        try {
            context.contentResolver.openFileDescriptor(uri, "w")
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo FD de video", e)
            null
        }

    fun finalizeVideo(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error finalizando video", e)
            }
        }
    }
}
