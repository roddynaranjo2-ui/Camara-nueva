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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CameraPreview(viewModel: CameraControlViewModel, modifier: Modifier = Modifier) {
    val currentLens by viewModel.currentLens.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Estado para rastrear si la superficie está lista
    var activeSurface by remember { mutableStateOf<Surface?>(null) }

    // Manejo del ciclo de vida de la actividad/fragmento
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // La cámara se abrirá automáticamente cuando la superficie esté lista o si ya lo está
                    activeSurface?.let { surface ->
                        if (surface.isValid) {
                            viewModel.startCameraSession(context, surface, currentLens)
                        }
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
            viewModel.closeCamera()
        }
    }

    // Reiniciar sesión si cambia el lente y la superficie está activa
    LaunchedEffect(currentLens) {
        activeSurface?.let { surface ->
            if (surface.isValid) {
                viewModel.startCameraSession(context, surface, currentLens)
            }
        }
    }

    AndroidView(
        factory = { viewContext ->
            SurfaceView(viewContext).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        val surface = holder.surface
                        activeSurface = surface
                        viewModel.startCameraSession(viewContext, surface, currentLens)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
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
        update = { _ -> },
        modifier = modifier.fillMaxSize()
    )
}
