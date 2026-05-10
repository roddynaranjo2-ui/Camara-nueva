package com.rodyto.lenspro

import android.content.ContentValues
import android.content.Context
import android.media.Image
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.nio.ByteBuffer

class MediaStorageManager {

    private val folderName = "LensPro"

    fun saveImage(context: Context, image: Image?) {
        if (image == null) {
            Log.e("RodytoLensPro", "La imagen es nula, no se puede guardar.")
            return
        }

        val bytes = try {
            val plane = image.planes.firstOrNull()
            if (plane == null) {
                Log.e("RodytoLensPro", "La imagen no contiene planos válidos.")
                return
            }

            val buffer: ByteBuffer = plane.buffer
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "No se pudieron extraer bytes de la imagen", e)
            return
        } finally {
            image.close()
        }

        val filename = "IMG_${System.currentTimeMillis()}.jpg"
        val resolver = context.contentResolver

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/$folderName")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        var uri: Uri? = null

        try {
            uri = resolver.insert(collection, contentValues)
                ?: throw IllegalStateException("No se pudo crear el registro en MediaStore")

            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(bytes)
                outputStream.flush()
            } ?: throw IllegalStateException("No se pudo abrir OutputStream para $uri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updatedValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, updatedValues, null, null)
            }

            Log.d("RodytoLensPro", "Foto guardada exitosamente: $uri")
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error crítico guardando foto", e)
            uri?.let {
                runCatching { resolver.delete(it, null, null) }
            }
        }
    }

    fun createVideoUri(context: Context): Uri? {
        val filename = "VID_${System.currentTimeMillis()}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/$folderName")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        return try {
            context.contentResolver.insert(collection, contentValues)
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error creando Uri de video", e)
            null
        }
    }

    fun finalizeVideoSave(context: Context, videoUri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(videoUri, contentValues, null, null)
            } catch (e: Exception) {
                Log.e("RodytoLensPro", "Error finalizando video", e)
            }
        }
    }
}
