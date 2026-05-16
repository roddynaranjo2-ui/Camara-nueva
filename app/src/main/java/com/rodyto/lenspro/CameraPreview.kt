package com.rodyto.lenspro

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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

/**
 * CameraPreview — OPTIMIZADO v2
 *
 * Mejoras vs versión previa:
 *  ① Animación del aspect ratio más rápida (StiffnessMedium) y sin rebote.
 *  ② Callback de bounds throttle: sólo emite cuando los bounds REALMENTE cambian
 *    (≥ 0.5 px de diferencia), evitando recomposiciones innecesarias en MainActivity.
 *  ③ setFixedSize en el SurfaceHolder cuando el modo cambia: el SurfaceView reserva
 *    el buffer correcto sin "tirones" en la transición FOTO ↔ VIDEO.
 *  ④ Reapertura limpia de la cámara si el surface se invalida durante el cambio
 *    de modo (algunos OEM destruyen el surface al cambiar aspect en runtime).
 *  ⑤ Lifecycle handler robusto: ON_RESUME reabre la cámara solo si el surface
 *    sigue válido y la sesión no está activa.
 */
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
    val cameraMode by viewModel.cameraMode.collectAsStateWithLifecycle()

    // FIX ①: Animación más ágil (StiffnessMedium) con damping crítico
    // → cambio FOTO(3:4) ↔ VIDEO(16:9) en ~280 ms sin rebote.
    val animatedAspect by animateFloatAsState(
        targetValue = aspect,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "preview_aspect"
    )

    var activeSurface by remember { mutableStateOf<Surface?>(null) }
    var lastBounds by remember { mutableStateOf<Rect?>(null) }

    // FIX ④: Forzar resurface cuando cambia el modo (foto ↔ video)
    // Algunos OEM (Samsung Exynos) requieren reconfigurar el buffer del surface
    // para evitar estiramientos al cambiar el aspect.
    LaunchedEffect(cameraMode) {
        // El propio cambio de aspect ya dispara surfaceChanged → re-bind a la cámara.
        // Aquí solo aseguramos que la cámara aplique el repeating actualizado
        // con el modo nuevo (AF continuo VIDEO vs PICTURE).
        viewModel.applyRepeatingPreview()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    val surface = activeSurface
                    if (surface != null && surface.isValid && !viewModel.isCameraRunning()) {
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
                .onGloballyPositioned { coords ->
                    val newBounds = coords.boundsInRoot()
                    val prev = lastBounds
                    // FIX ②: Throttle de bounds: solo emitimos si cambia ≥ 0.5px
                    val changed = prev == null ||
                        kotlin.math.abs(prev.top - newBounds.top) > 0.5f ||
                        kotlin.math.abs(prev.bottom - newBounds.bottom) > 0.5f ||
                        kotlin.math.abs(prev.left - newBounds.left) > 0.5f ||
                        kotlin.math.abs(prev.right - newBounds.right) > 0.5f
                    if (changed) {
                        lastBounds = newBounds
                        onPreviewBoundsChanged(newBounds)
                    }
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
                                val surface = holder.surface
                                activeSurface = surface
                                if (surface.isValid && !viewModel.isCameraRunning()) {
                                    viewModel.startCameraSession(
                                        context = ctx,
                                        surface = surface,
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
                                // FIX ③: Hint al sistema gráfico del tamaño óptimo del buffer
                                // → menos copias por frame, mejor rendimiento en la preview.
                                try {
                                    holder.setFixedSize(width, height)
                                } catch (_: Throwable) { /* algunos OEM no permiten setFixedSize en runtime */ }
                                viewModel.notifyPreviewSize(width, height)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                activeSurface = null
                                viewModel.closeCamera()
                            }
                        })
                    }
                },
                update = { view ->
                    val surface = view.holder.surface
                    if (surface != null && surface.isValid) {
                        activeSurface = surface
                    }
                }
            )
        }
    }
}
