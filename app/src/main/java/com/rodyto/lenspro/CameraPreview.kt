package com.rodyto.lenspro

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext

@Composable
fun CameraPreview(viewModel: CameraControlViewModel, modifier: Modifier = Modifier) {
    val currentLens by viewModel.currentLens.collectAsState()
    val context = LocalContext.current
    
    // Mantenemos una referencia al Surface activo para no perder el flujo al cambiar de lente
    var activeSurface by remember { mutableStateOf<Surface?>(null) }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                        val surface = Surface(surfaceTexture)
                        activeSurface = surface
                        // Iniciar la sesión de cámara con el lente actual (0.5x, 1x o 3x)
                        viewModel.startCameraSession(context, surface, currentLens)
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        // Aquí se podrían añadir correcciones de aspecto si fuera necesario
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        viewModel.closeCamera()
                        activeSurface?.release()
                        activeSurface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                        // Frame actualizado
                    }
                }
            }
        },
        update = { view ->
            // Si el usuario cambia de lente en la UI, este bloque detecta el cambio
            // y reinicia la sesión con el nuevo sensor físico del S21 FE
            if (view.isAvailable && activeSurface != null) {
                viewModel.startCameraSession(context, activeSurface!!, currentLens)
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
