package com.rodyto.lenspro

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CameraPreview(
    viewModel: CameraControlViewModel,
    modifier: Modifier = Modifier,
    onPreviewBoundsChanged: (Rect?) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentLens by viewModel.currentLens.collectAsStateWithLifecycle()
    val latestLens by rememberUpdatedState(currentLens)

    val aspect by viewModel.previewAspectRatio.collectAsStateWithLifecycle()

    val animatedAspect by animateFloatAsState(
        targetValue = aspect,
        animationSpec = tween(220),
        label = "preview_aspect"
    )

    var activeSurface by remember { mutableStateOf<Surface?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    val surface = activeSurface
                    if (surface != null && surface.isValid) {
                        viewModel.startCameraSession(
                            context = context,
                            surface = surface,
                            lens = latestLens
                        )
                    }
                }

                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.closeCamera()
                }

                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .aspectRatio(animatedAspect)
                .clip(RoundedCornerShape(30.dp))
                .onGloballyPositioned {
                    onPreviewBoundsChanged(it.boundsInRoot())
                }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        setZOrderOnTop(false)

                        holder.setKeepScreenOn(true)

                        holder.addCallback(object : SurfaceHolder.Callback {

                            override fun surfaceCreated(holder: SurfaceHolder) {
                                activeSurface = holder.surface

                                if (holder.surface.isValid) {
                                    viewModel.startCameraSession(
                                        context = context,
                                        surface = holder.surface,
                                        lens = latestLens
                                    )
                                }
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) {
                                activeSurface = holder.surface
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                activeSurface = null
                                viewModel.closeCamera()
                            }
                        })
                    }
                },
                update = {
                    activeSurface = it.holder.surface
                }
            )
        }
    }
}