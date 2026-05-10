package com.rodyto.lenspro

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.OutputStream

object MediaStorageManager {

    private const val FOLDER_NAME = "RodytoLensPro"

    // 1. Guardar Fotografía (JPEG)
    fun savePhoto(context: Context, jpegBytes: ByteArray) {
        val filename = "IMG_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/$FOLDER_NAME")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val imageUri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        imageUri?.let { uri ->
            try {
                // Simplificación del stream para evitar errores de inferencia del compilador
                resolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(jpegBytes)
                    outputStream.flush()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val updatedValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(uri, updatedValues, null, null)
                }
                Log.d("RodytoLensPro", "Foto guardada exitosamente: $uri")
            } catch (e: Exception) {
                Log.e("RodytoLensPro", "Error guardando foto", e)
            }
        }
    }

    // 2. Preparar el Uri para grabación de Video
    fun createVideoUri(context: Context): Uri? {
        val filename = "VID_${System.currentTimeMillis()}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/$FOLDER_NAME")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return try {
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error creando Uri de video", e)
            null
        }
    }

    // 3. Finalizar guardado de video (remover flag IS_PENDING)
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
