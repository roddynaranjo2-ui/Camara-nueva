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

/* ================================================================
 *  Rodyto Lens Pro · CameraPreview · v3.6 Pro · OPTIMIZADO
 *
 *  CORRECCIONES v3.6 (sobre v3.5):
 *   ① pinchToZoom usa awaitEachGesture (single-shot) en lugar de
 *      while(true) infinito → libera la lambda al levantar dedos,
 *      no compite con otros pointerInput descendentes.
 *   ② onPinchInProgress(true) THROTTLED — sólo se notifica una vez
 *      por gesto. Evita 60 emisiones/seg al StateFlow del padre.
 *   ③ update {} de AndroidView ya NO llama setAspectRatio en cada
 *      recomposición — sólo cuando los valores cambian (guard externo).
 *   ④ Validación robusta de surface en surfaceChanged (checa width/h>0).
 *   ⑤ Startup defensivo: si la sesión Camera2 está OPENING, no se
 *      relanza al recibir ON_RESUME (evita doble apertura HAL).
 * ================================================================ */
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

    // Animación rápida sin rebote
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

    // Guard para evitar re-aplicar el aspect ratio idéntico en cada update
    var lastAppliedSw by remember { mutableStateOf(-1) }
    var lastAppliedSh by remember { mutableStateOf(-1) }

    LaunchedEffect(cameraMode) {
        viewModel.applyRepeatingPreview()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    val surface = activeSurface
                    // FIX v3.6: comprobar también que NO esté ya abriendo
                    if (surface != null && surface.isValid &&
                        !viewModel.isCameraRunning() &&
                        viewModel.sessionState.value != CameraSessionState.OPENING
                    ) {
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
                    // Throttle bounds 0.5px → cero recomposiciones inútiles
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
                    AutoFitSurfaceView(ctx).apply {
                        setZOrderOnTop(false)
                        holder.setKeepScreenOn(true)

                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                val surface = holder.surface
                                activeSurface = surface
                                if (surface.isValid &&
                                    !viewModel.isCameraRunning() &&
                                    viewModel.sessionState.value != CameraSessionState.OPENING
                                ) {
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
                                // FIX v3.6: validar dimensiones antes de aceptar surface
                                if (width <= 0 || height <= 0) return
                                activeSurface = holder.surface
                                try { holder.setFixedSize(width, height) }
                                catch (_: Throwable) {}
                                viewModel.notifyPreviewSize(width, height)

                                val (sw, sh) = viewModel.getOptimalPreviewSize()
                                if (sw > 0 && sh > 0 &&
                                    (sw != lastAppliedSw || sh != lastAppliedSh)
                                ) {
                                    lastAppliedSw = sw
                                    lastAppliedSh = sh
                                    // En portrait altura del sensor es lado largo en pantalla → invertimos.
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
                    // FIX v3.6: sólo re-aplicar AutoFit si cambió respecto a la última vez
                    val (sw, sh) = viewModel.getOptimalPreviewSize()
                    if (sw > 0 && sh > 0 &&
                        (sw != lastAppliedSw || sh != lastAppliedSh)
                    ) {
                        lastAppliedSw = sw
                        lastAppliedSh = sh
                        view.setAspectRatio(sh, sw)
                    }
                }
            )
        }
    }
}

/* ================================================================
 *  pinchToZoom v3.6 — versión SAFE
 *
 *  Cambios respecto v3.5:
 *   • Usamos `awaitEachGesture { }` (single-shot por gesto) en
 *     lugar de un bucle `while(true)` infinito. Esto garantiza
 *     que cuando el usuario suelta los dedos la coroutine termina
 *     y libera el slot del pointerInput; el bucle anterior podía
 *     quedarse "vivo" en algunos dispositivos y consumir CPU.
 *   • Throttle del callback onPinchStart: sólo se llama UNA vez
 *     al inicio del gesto. Antes podía dispararse en cada
 *     iteración del while exterior cuando el HAL emitía eventos
 *     fantasma.
 *   • Atenuación logarítmica preservada.
 * ================================================================ */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.pinchToZoom(
    getCurrentZoom: () -> Float,
    getMinZoom: () -> Float,
    getMaxZoom: () -> Float,
    onZoom: (Float) -> Unit,
    onPinchStart: () -> Unit,
    onPinchEnd: () -> Unit
) {
    val sensitivity = 1.0f
    androidx.compose.foundation.gestures.awaitEachGesture {
        // Esperar primer down
        val firstDown = awaitPointerEvent(PointerEventPass.Main)
        if (firstDown.changes.count { it.pressed } < 2) {
            // Esperar a tener 2 dedos
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Main)
                val active = ev.changes.count { it.pressed }
                if (active < 1) return@awaitEachGesture  // se levantó todo
                if (active >= 2) break
            }
        }

        // Distancia inicial
        var prevDistance: Float = computeAverageDistance(awaitPointerEvent(PointerEventPass.Main))
            ?: return@awaitEachGesture
        if (prevDistance < 16f) return@awaitEachGesture

        onPinchStart()
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val activePointers = event.changes.count { it.pressed }
                if (activePointers < 2) break  // se levantó un dedo → fin gesto

                val curDistance = computeAverageDistance(event) ?: continue
                if (curDistance < 8f || prevDistance < 8f) continue

                var rawFactor = curDistance / prevDistance
                if (sensitivity != 1.0f) {
                    rawFactor = Math.pow(rawFactor.toDouble(), sensitivity.toDouble()).toFloat()
                }
                val cur = getCurrentZoom()
                val target = (cur * rawFactor).coerceIn(getMinZoom(), getMaxZoom())
                onZoom(target)
                prevDistance = curDistance
            }
        } finally {
            onPinchEnd()
        }
    }
}

/** Distancia euclidiana entre los dos primeros punteros activos. */
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
