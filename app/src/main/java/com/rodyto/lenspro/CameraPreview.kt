package com.rodyto.lenspro

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Preview correcto sin "imagen estirada":
 *  - Usamos `aspectRatio(ratio)` que respeta el ratio real elegido (3:4 / 9:16 / 1:1 / FULL).
 *  - Animamos suavemente los cambios de aspect para que la transición foto↔video sea fluida.
 *  - El Surface conserva el mismo `holder` para evitar reconfigurar la sesión Camera2.
 */
@Composable
fun CameraPreview(
    viewModel: CameraControlViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentLens by viewModel.currentLens.collectAsStateWithLifecycle()
    val latestLens by rememberUpdatedState(currentLens)
    val aspect by viewModel.previewAspectRatio.collectAsStateWithLifecycle()

    // Animación suave del aspect ratio al cambiar de modo o ajuste manual
    val animatedAspect by animateFloatAsState(
        targetValue = aspect,
        animationSpec = tween(durationMillis = 280),
        label = "preview_aspect"
    )

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
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Cuando aspect < 9/19.5 (≈ "FULL") preferimos llenar el ancho para
        // ocupar toda la pantalla. En otros casos llenamos la altura disponible.
        val isFull = animatedAspect <= 9f / 17f
        AndroidView(
            modifier = if (isFull)
                Modifier.fillMaxSize()
            else
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(animatedAspect),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            val s = holder.surface
                            if (s != null && s.isValid) {
                                activeSurface = s
                                if (!viewModel.isCameraRunning()) {
                                    viewModel.startCameraSession(ctx, s, latestLens)
                                }
                            }
                        }
                        override fun surfaceChanged(
                            holder: SurfaceHolder, format: Int, width: Int, height: Int
                        ) { /* mismo Surface, sin re-creación */ }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            activeSurface = null
                            viewModel.closeCamera()
                        }
                    })
                }
            }
        )
    }
}
