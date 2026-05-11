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

    var activeSurface by remember {
        mutableStateOf<Surface?>(null)
    }

    var isSurfaceReady by remember {
        mutableStateOf(false)
    }

    DisposableEffect(lifecycleOwner) {

        val observer = LifecycleEventObserver { _, event ->

            when (event) {

                Lifecycle.Event.ON_RESUME -> {

                    val safeSurface = activeSurface

                    if (
                        safeSurface != null &&
                        safeSurface.isValid &&
                        isSurfaceReady &&
                        !viewModel.isCameraRunning()
                    ) {
                        viewModel.startCameraSession(
                            context,
                            safeSurface,
                            latestLens
                        )
                    }
                }

                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.closeCamera()
                }

                Lifecycle.Event.ON_DESTROY -> {
                    viewModel.closeCamera()
                }

                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.closeCamera()
        }
    }

    AndroidView(
        modifier = modifier,

        factory = { viewContext ->

            SurfaceView(viewContext).apply {

                holder.addCallback(object : SurfaceHolder.Callback {

                    override fun surfaceCreated(holder: SurfaceHolder) {

                        val surface = holder.surface

                        if (surface != null && surface.isValid) {

                            activeSurface = surface
                            isSurfaceReady = true

                            if (!viewModel.isCameraRunning()) {
                                viewModel.startCameraSession(
                                    viewContext,
                                    surface,
                                    latestLens
                                )
                            }
                        }
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {

                        val surface = holder.surface

                        if (surface != null && surface.isValid) {

                            activeSurface = surface
                            isSurfaceReady = true

                            if (!viewModel.isCameraRunning()) {
                                viewModel.startCameraSession(
                                    viewContext,
                                    surface,
                                    latestLens
                                )
                            }
                        }
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        isSurfaceReady = false
                        activeSurface = null
                        viewModel.closeCamera()
                    }
                })
            }
        }
    )
}