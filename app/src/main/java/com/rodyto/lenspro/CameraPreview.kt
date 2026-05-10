package com.rodyto.lenspro

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun CameraPreview(viewModel: CameraControlViewModel, modifier: Modifier = Modifier) {
    val currentLens by viewModel.currentLens.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Usamos un State para la superficie para que el ciclo de vida reaccione correctamente
    var activeSurface by remember { mutableStateOf<Surface?>(null) }

    DisposableEffect(lifecycleOwner, activeSurface, currentLens) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    activeSurface?.let { surface ->
                        viewModel.startCameraSession(context, surface, currentLens)
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.closeCamera()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AndroidView(
        factory = { viewContext ->
            SurfaceView(viewContext).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        val surface = holder.surface
                        activeSurface = surface
                        // Usamos el contexto de la vista para mayor seguridad en el NDK/Camera2
                        viewModel.startCameraSession(viewContext, surface, currentLens)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        // Si el tamaño cambia, Camera2 suele necesitar una reconfiguración
                        if (holder.surface.isValid) {
                            activeSurface = holder.surface
                        }
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        activeSurface = null
                        viewModel.closeCamera()
                    }
                })
            }
        },
        update = { _ ->
            // El update se mantiene vacío para que el control lo lleve el SurfaceHolder.Callback
        },
        modifier = modifier.fillMaxSize()
    )
}
