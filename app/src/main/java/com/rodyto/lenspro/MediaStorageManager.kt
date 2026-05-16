package com.rodyto.lenspro

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log

/**
 * MediaStorageManager — OPTIMIZADO v2
 *
 * Persistencia en MediaStore (DCIM/LensPro).
 *  - Usa IS_PENDING en API 29+ para evitar archivos parciales visibles.
 *  - Limpia el Uri si la escritura falla a mitad.
 *  - openVideoFd: abre con "rw" — MediaRecorder lo requiere.
 *
 * FIX v2:
 *   ① finalizeVideo ahora puede recibir el FD para cerrarlo ANTES del update
 *     (orden crítico: el writer del mp4 debe haber liberado el FD para que el
 *     mediaserver pueda indexar correctamente la duración y los keyframes).
 *   ② Validación de tamaño mínimo: si el video resultante < 4 KB se considera
 *     fallido (la cabecera mp4 mínima ronda los 2-3 KB) y se elimina silenciosamente.
 *   ③ deleteUri público para que el ViewModel pueda limpiar grabaciones canceladas.
 */
class MediaStorageManager {

    companion object {
        private const val TAG    = "RodytoLensPro"
        private const val FOLDER = "LensPro"
        private const val MIN_VALID_VIDEO_BYTES = 4 * 1024L  // 4 KB
    }

    // ---------- IMÁGENES JPEG ----------
    fun saveJpeg(context: Context, bytes: ByteArray): Uri? {
        if (bytes.isEmpty()) return null

        val resolver = context.contentResolver
        val filename = "IMG_${System.currentTimeMillis()}.jpg"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/$FOLDER")
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

    /**
     * FIX: Abrimos con "rw" porque MediaRecorder necesita lectura+escritura
     * sobre el FileDescriptor (algunos OEM fallan si se abre con "w").
     */
    fun openVideoFd(context: Context, uri: Uri): ParcelFileDescriptor? = try {
        context.contentResolver.openFileDescriptor(uri, "rw")
    } catch (e: Exception) { Log.e(TAG, "Error abriendo FD de video", e); null }

    /**
     * Versión legacy (mantengo compat con el ViewModel original).
     * Marca el video como NO pendiente. Si el FD está aún abierto la indexación
     * puede fallar — recomendado usar finalizeVideo(context, uri, fd) cuando sea posible.
     */
    fun finalizeVideo(context: Context, uri: Uri) {
        finalizeVideo(context, uri, null)
    }

    /**
     * FIX ①: Versión nueva: cierra el FD ANTES del update y valida tamaño mínimo.
     * Si el archivo resultante es absurdamente pequeño, se elimina (grabación fallida).
     *
     * @return true si el video quedó válido y publicado, false si fue eliminado.
     */
    fun finalizeVideo(context: Context, uri: Uri, fd: ParcelFileDescriptor?): Boolean {
        // Cerrar FD primero — el mediaserver necesita el fichero exclusivo
        try { fd?.close() } catch (_: Throwable) {}

        // FIX ②: Validar tamaño (Android 10+)
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
                Log.e(TAG, "Error finalizando video", e)
                return false
            }
        }
        return true
    }

    /**
     * FIX ③: Limpieza pública para grabaciones canceladas/fallidas.
     */
    fun deleteUri(context: Context, uri: Uri?) {
        if (uri == null) return
        runCatching { context.contentResolver.delete(uri, null, null) }
    }
}
