package com.rodyto.lenspro

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

    var activeSurface by remember { mutableStateOf<android.view.Surface?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                activeSurface?.let { viewModel.startCameraSession(context, it, currentLens) }
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.closeCamera()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        activeSurface = holder.surface
                        viewModel.startCameraSession(context, holder.surface, currentLens)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        viewModel.closeCamera()
                        activeSurface = null
                    }
                })
            }
        },
        update = {}, // Vacío intencionalmente para evitar bucles de reinicio
        modifier = modifier.fillMaxSize()
    )
}
