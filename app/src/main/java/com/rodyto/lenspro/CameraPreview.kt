package com.rodyto.lenspro

import android.view.Surface
import android.view.SurfaceHolder
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
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
import com.rodyto.lenspro.ui.overlays.GridOverlay
import com.rodyto.lenspro.ui.overlays.TimerCountdownOverlay
import com.rodyto.lenspro.ui.overlays.ShutterBlinkOverlay
import com.rodyto.lenspro.ui.components.AutoFitSurfaceView
import com.rodyto.lenspro.ui.components.BlurredBackdropLayer
import com.rodyto.lenspro.util.*
import kotlin.math.abs

/* ================================================================
 *  Rodyto Lens Pro · CameraPreview · v4.5 Premium
 *
 *  v4.5 — FIX CRÍTICO ADICIONAL:
 *  • surfaceChanged ya no llama holder.setFixedSize() — causaba un loop
 *    surfaceChanged→setFixedSize→surfaceChanged en Pixel/Samsung Exynos
 *    que impedía crear la CaptureSession → pantalla negra.
 *
 *  v4.2 — FIX CRÍTICO: LaunchedEffect(latestLens, isFront) ahora
 *  salta la ejecución inicial para evitar la race condition con
 *  surfaceCreated, que causaba un doble openCamera() → ERROR_CAMERA_IN_USE
 *  → la cámara se cerraba al abrir la app.
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

    val animatedAspect by animateFloatAsState(
        targetValue = aspect,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "preview_aspect"
    )

    var activeSurface by remember { mutableStateOf<Surface?>(null) }
    var surfaceViewRef by remember { mutableStateOf<AutoFitSurfaceView?>(null) }
    var lastBounds by remember { mutableStateOf<Rect?>(null) }
    var lastAppliedSw by remember { mutableStateOf(-1) }
    var lastAppliedSh by remember { mutableStateOf(-1) }

    // FIX v4.2: Flag para detectar si es la primera composición.
    // Usamos un ref no-state para que no provoque recomposición al cambiar.
    val isFirstLensEffect = remember { mutableStateOf(true) }

    LaunchedEffect(cameraMode) { viewModel.applyRepeatingPreview() }

    // FIX M-05 / FIX v4.2: Reiniciar sesión al cambiar de lente o cámara frontal,
    // pero NO en la primera composición (la primera apertura la gestiona surfaceCreated).
    val isFront by viewModel.isFrontCamera.collectAsStateWithLifecycle()
    LaunchedEffect(latestLens, isFront) {
        // Saltar la primera ejecución: surfaceCreated ya abre la cámara en la
        // composición inicial. Si ejecutáramos aquí también, llamaríamos a
        // closeCamera() mientras el HAL todavía está procesando el openCamera()
        // de surfaceCreated → ERROR_CAMERA_IN_USE → pantalla negra / cierre.
        if (isFirstLensEffect.value) {
            isFirstLensEffect.value = false
            return@LaunchedEffect
        }
        val surface = activeSurface
        if (surface != null && surface.isValid) {
            viewModel.closeCamera()
            viewModel.startCameraSession(context, surface, latestLens)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    val surface = activeSurface
                    if (surface != null && surface.isValid &&
                        !viewModel.isCameraRunning() &&
                        viewModel.sessionState.value != CameraSessionState.OPENING
                    ) {
                        viewModel.startCameraSession(context, surface, latestLens)
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
            .background(Color.Black)
    ) {
        // ─── CAPA 0: Letterbox blureado en vivo (detrás de todo) ──
        BlurredBackdropLayer(
            sourceSurfaceRef = { surfaceViewRef },
            modifier = Modifier.fillMaxSize()
        )

        // Vignette sutil sobre el backdrop para resaltar el frame focal
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f))
        )

        // ─── CAPA 1: Preview focal nítido centrado 3:4 ────────────
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .aspectRatio(animatedAspect)
                    .clip(RoundedCornerShape(30.dp))
                    .border(
                        width = 0.75.dp,
                        color = Color.White.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(30.dp)
                    )
                    .onGloballyPositioned { coords ->
                        val newBounds = coords.boundsInRoot()
                        val prev = lastBounds
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
                            surfaceViewRef = this

                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    val surface = holder.surface
                                    activeSurface = surface
                                    if (surface.isValid &&
                                        !viewModel.isCameraRunning() &&
                                        viewModel.sessionState.value != CameraSessionState.OPENING
                                    ) {
                                        viewModel.startCameraSession(ctx, surface, latestLens)
                                    }
                                }
                                override fun surfaceChanged(
                                    holder: SurfaceHolder, format: Int,
                                    width: Int, height: Int
                                ) {
                                    if (width <= 0 || height <= 0) return
                                    activeSurface = holder.surface
                                    // FIX v4.5: ELIMINADO holder.setFixedSize() — provocaba
                                    // un loop surfaceChanged→setFixedSize→surfaceChanged en
                                    // algunos dispositivos (Pixel, Samsung Exynos), dejando
                                    // la cámara incapaz de crear la CaptureSession → pantalla negra.
                                    viewModel.notifyPreviewSize(width, height)
                                    val (sw, sh) = viewModel.getOptimalPreviewSize()
                                    if (sw > 0 && sh > 0 &&
                                        (sw != lastAppliedSw || sh != lastAppliedSh)
                                    ) {
                                        lastAppliedSw = sw
                                        lastAppliedSh = sh
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
                        surfaceViewRef = view
                        val surface = view.holder.surface
                        if (surface != null && surface.isValid) activeSurface = surface
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

                // Grid overlay (funcional, dentro del focal frame)
                GridOverlay(viewModel = viewModel)
            }
        }

        // ─── CAPA 2: Timer countdown overlay ──────────────────────
        TimerCountdownOverlay(viewModel = viewModel)

        // ─── CAPA 3: Shutter blink ────────────────────────────────
        val blinkKey by viewModel.shutterBlinkKey.collectAsStateWithLifecycle()
        ShutterBlinkOverlay(triggerKey = blinkKey)
    }
}

/* ================================================================
 *  pinchToZoom v4.0 — sensitivity calibrada + suavizado exponencial
 * ================================================================ */
private suspend fun PointerInputScope.pinchToZoom(
    getCurrentZoom: () -> Float,
    getMinZoom: () -> Float,
    getMaxZoom: () -> Float,
    onZoom: (Float) -> Unit,
    onPinchStart: () -> Unit,
    onPinchEnd: () -> Unit
) {
    val sensitivity = 0.6f   // sensitivity logarítmica calibrada
    while (true) {
        awaitPointerEventScope {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Main)
                val active = ev.changes.count { it.pressed }
                if (active < 1) return@awaitPointerEventScope
                if (active >= 2) break
            }
            var prevDistance: Float =
                computeAverageDistance(awaitPointerEvent(PointerEventPass.Main))
                    ?: return@awaitPointerEventScope
            if (prevDistance < 16f) return@awaitPointerEventScope

            onPinchStart()
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val activePointers = event.changes.count { it.pressed }
                    if (activePointers < 2) break
                    val curDistance = computeAverageDistance(event) ?: continue
                    if (curDistance < 8f || prevDistance < 8f) continue

                    var rawFactor = curDistance / prevDistance
                    rawFactor = Math.pow(rawFactor.toDouble(), sensitivity.toDouble()).toFloat()

                    val cur = getCurrentZoom()
                    val target = (cur * rawFactor).coerceIn(getMinZoom(), getMaxZoom())
                    onZoom(target)
                    prevDistance = curDistance
                }
            } finally { onPinchEnd() }
        }
    }
}

private fun computeAverageDistance(event: PointerEvent): Float? {
    val active = event.changes.filter { it.pressed }
    if (active.size < 2) return null
    val a = active[0].position
    val b = active[1].position
    val dx = a.x - b.x
    val dy = a.y - b.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}