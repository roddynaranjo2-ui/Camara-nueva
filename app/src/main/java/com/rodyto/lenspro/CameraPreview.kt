package com.rodyto.lenspro

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CameraPreview(
    viewModel: CameraControlViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentLens by viewModel.currentLens.collectAsStateWithLifecycle()
    val latestLens by rememberUpdatedState(currentLens)

    var activeSurface by remember { mutableStateOf<Surface?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    val s = activeSurface
                    if (s != null && s.isValid && !viewModel.isCameraRunning()) {
                        viewModel.startCameraSession(context, s, latestLens)
                    }
                }
                Lifecycle.Event.ON_PAUSE -> viewModel.closeCamera()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // NO cerramos cámara aquí: ya lo hace ON_PAUSE/ON_DESTROY del Activity
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        val s = holder.surface
                        if (s.isValid) {
                            activeSurface = s
                            if (!viewModel.isCameraRunning()) {
                                viewModel.startCameraSession(ctx, s, latestLens)
                            }
                        }
                    }
                    override fun surfaceChanged(
                        holder: SurfaceHolder, format: Int, width: Int, height: Int
                    ) {
                        // Sin re-creación; el Surface es el mismo
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        activeSurface = null
                        viewModel.closeCamera()
                    }
                })
            }
        }
    )
}
