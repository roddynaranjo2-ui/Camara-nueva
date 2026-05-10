package com.rodyto.lenspro

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.OutputStream

/**
 * Gestor de almacenamiento optimizado para Android 10+ (Scoped Storage)
 * y compatible con Android 16.
 */
object MediaStorageManager {

    private const val FOLDER_NAME = "RodytoLensPro"

    /**
     * Guarda una fotografía en la galería de forma asíncrona y segura.
     */
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
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        try {
            val imageUri: Uri? = resolver.insert(collection, contentValues)

            imageUri?.let { uri ->
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
            }
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error crítico guardando foto", e)
        }
    }

    /**
     * Crea un Uri para grabación de video en la carpeta dedicada.
     */
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

    /**
     * Finaliza el guardado del video para que sea visible en la galería.
     */
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
