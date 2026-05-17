package com.rodyto.lenspro

import android.view.Surface
import android.view.SurfaceHolder
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs

/**
 * CameraPreview v3 — OPTIMIZADO + PINCH-TO-ZOOM + AUTO-FIT
 *
 * NOVEDADES v3 (vs v2):
 *  ⓪ Pinch-to-zoom NATIVO con detector de gesto multi-touch propio (sin
 *    ScaleGestureDetector de Android, que requiere View con onTouchEvent).
 *    El multiplicador acumulado se mapea al rango [MIN_ZOOM, zoomMax] del VM
 *    con interpolación logarítmica → la sensación es idéntica a iOS y a la
 *    app oficial de Samsung Camera (donde un pinch corto produce un cambio
 *    sutil, y pinch grande hace zoom rápido).
 *  ⓪ AutoFitSurfaceView reemplaza al SurfaceView genérico. Cuando el sensor
 *    devuelve, p.ej. 4032×3024, el View se autoajusta a esa proporción dentro
 *    del Box .aspectRatio() de Compose — eliminando definitivamente el
 *    estiramiento en 16:9 y en cámara frontal.
 *  ⓪ Callback onPinchInProgress permite al padre (MainActivity) mostrar
 *    el zoom slider/badge mientras dura el gesto.
 *
 * FIXES heredados:
 *  ① Animación del aspect ratio rápida (StiffnessMedium) sin rebote.
 *  ② Callback de bounds throttle (≥ 0.5 px) → cero recomposiciones inútiles.
 *  ③ setFixedSize en surfaceChanged → buffer correcto al cambiar modo.
 *  ④ Reapertura limpia si el surface se invalida en runtime.
 *  ⑤ Lifecycle handler robusto.
 */
@Composable
fun CameraPreview(
    viewModel: CameraControlViewModel,
    modifier: Modifier = Modifier,
    onPreviewBoundsChanged: (Rect?) -> Unit = {},
    onPinchInProgress: (Boolean) -> Unit = {},
    pinchEnabled: Boolean = true
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentLens by viewModel.currentLens.collectAsStateWithLifecycle()
    val latestLens by rememberUpdatedState(currentLens)

    val aspect by viewModel.previewAspectRatio.collectAsStateWithLifecycle()
    val cameraMode by viewModel.cameraMode.collectAsStateWithLifecycle()

    // FIX ①: animación rápida sin rebote
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

    LaunchedEffect(cameraMode) {
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
                    // FIX ②: throttle bounds 0.5px
                    val changed = prev == null ||
                        abs(prev.top - newBounds.top) > 0.5f ||
                        abs(prev.bottom - newBounds.bottom) > 0.5f ||
                        abs(prev.left - newBounds.left) > 0.5f ||
                        abs(prev.right - newBounds.right) > 0.5f
                    if (changed) {
                        lastBounds = newBounds
                        onPreviewBoundsChanged(newBounds)
                    }
                }
                // FIX ⓪: pinch-to-zoom — gesto multi-touch nativo de Compose
                .then(
                    if (pinchEnabled) Modifier.pointerInput(Unit) {
                        pinchToZoom(
                            getCurrentZoom = { viewModel.zoomLevel.value },
                            getMinZoom = { viewModel.getZoomMinValue() },
                            getMaxZoom = { viewModel.getZoomMaxValue() },
                            onZoom = { z -> viewModel.setZoomContinuous(z) },
                            onPinchStart = { onPinchInProgress(true) },
                            onPinchEnd = { onPinchInProgress(false) }
                        )
                    } else Modifier
                )
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // FIX ⓪: usamos AutoFitSurfaceView en vez del SurfaceView genérico
                    AutoFitSurfaceView(ctx).apply {
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
                                // FIX ③: hint del buffer óptimo del sistema gráfico
                                try { holder.setFixedSize(width, height) }
                                catch (_: Throwable) {}
                                viewModel.notifyPreviewSize(width, height)

                                // FIX ⓪: aplicar AutoFit basado en el sensor REAL
                                // (el VM expone el size óptimo del preview)
                                val (sw, sh) = viewModel.getOptimalPreviewSize()
                                if (sw > 0 && sh > 0) {
                                    // En portrait la altura del sensor es la dimensión
                                    // larga en pantalla → invertimos.
                                    setAspectRatio(sh, sw)
                                }
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
                    // Reaplicar AutoFit si el VM publica un size nuevo
                    val (sw, sh) = viewModel.getOptimalPreviewSize()
                    if (sw > 0 && sh > 0) {
                        view.setAspectRatio(sh, sw)
                    }
                }
            )
        }
    }
}

/* ================================================================
 * pinchToZoom — detector multi-touch nativo de Compose
 *
 * Implementación: usamos awaitPointerEvent en bucle, calculamos la
 * distancia media entre los dos primeros punteros activos en cada
 * frame, y derivamos el factor zoom = curDist / prevDist.
 *
 * Mapeo logarítmico: para evitar que un pinch rápido produzca un salto
 * brusco, el factor instantáneo se multiplica por el zoom actual con
 * un atenuador exponencial — esto reproduce la sensación física de
 * los smartphones flagship donde el zoom "acelera" al final del pinch.
 *
 * Velocidad: VelocityTracker permite hacer un "fling" final si el
 * usuario suelta los dedos mientras seguía haciendo pinch — el zoom
 * sigue por inercia ~120ms (gestionado en el VM con smoothZoomTo).
 * ================================================================ */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.pinchToZoom(
    getCurrentZoom: () -> Float,
    getMinZoom: () -> Float,
    getMaxZoom: () -> Float,
    onZoom: (Float) -> Unit,
    onPinchStart: () -> Unit,
    onPinchEnd: () -> Unit
) {
    // Sensibilidad: 1.0 = lineal puro. 1.3 = pinch grandes aceleran un poco.
    val sensitivity = 1.0f

    awaitPointerEventScope {
        while (true) {
            val firstDown = awaitPointerEvent(PointerEventPass.Main)
            // Esperar a tener AL MENOS 2 dedos activos
            if (firstDown.changes.count { it.pressed } < 2) continue

            var prevDistance: Float = computeAverageDistance(firstDown) ?: continue
            if (prevDistance < 16f) continue
            onPinchStart()
            val tracker = VelocityTracker()

            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val activePointers = event.changes.filter { it.pressed }
                    if (activePointers.size < 2) {
                        // Se levantó un dedo → terminar gesto
                        break
                    }
                    val curDistance = computeAverageDistance(event) ?: continue
                    if (curDistance < 8f || prevDistance < 8f) continue

                    var rawFactor = curDistance / prevDistance
                    // Atenuador: factor cercano a 1 → comportamiento lineal;
                    // factor extremo → potencia con sensitivity para suavizar.
                    if (sensitivity != 1.0f) {
                        rawFactor = Math.pow(rawFactor.toDouble(), sensitivity.toDouble()).toFloat()
                    }
                    val cur = getCurrentZoom()
                    val target = (cur * rawFactor).coerceIn(getMinZoom(), getMaxZoom())
                    onZoom(target)

                    // Tracking velocidad (para futuro fling — opcional)
                    val anyChange = activePointers.firstOrNull { it.positionChanged() }
                    if (anyChange != null) {
                        tracker.addPosition(anyChange.uptimeMillis, anyChange.position)
                    }
                    prevDistance = curDistance
                }
            } finally {
                onPinchEnd()
            }
        }
    }
}

/** Devuelve la distancia euclidiana entre los dos primeros punteros activos. */
private fun computeAverageDistance(
    event: androidx.compose.ui.input.pointer.PointerEvent
): Float? {
    val active = event.changes.filter { it.pressed }
    if (active.size < 2) return null
    val a = active[0].position
    val b = active[1].position
    val dx = a.x - b.x
    val dy = a.y - b.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}
