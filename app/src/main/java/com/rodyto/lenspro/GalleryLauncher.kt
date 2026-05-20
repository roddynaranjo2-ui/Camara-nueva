package com.rodyto.lenspro

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast

/* ================================================================
 *  GalleryLauncher.kt · v1.0 Premium
 *
 *  Abre la galería del sistema mostrando las imágenes/vídeos.
 *  Estrategia en cascada:
 *   1. ACTION_VIEW sobre MediaStore.Images
 *   2. Categoría APP_GALLERY
 *   3. Fallback: simple Intent ACTION_VIEW image/*
 * ================================================================ */
object GalleryLauncher {

    private const val TAG = "GalleryLauncher"

    fun openGallery(context: Context) {
        // 1) Mejor opción — MediaStore via ACTION_VIEW
        val tryIntents = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            Intent().apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_APP_GALLERY)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )

        for (intent in tryIntents) {
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) { /* try next */ }
            catch (t: Throwable) { Log.w(TAG, "intent failed", t) }
        }
        Toast.makeText(context, "No se encontró una galería instalada", Toast.LENGTH_SHORT).show()
    }
}