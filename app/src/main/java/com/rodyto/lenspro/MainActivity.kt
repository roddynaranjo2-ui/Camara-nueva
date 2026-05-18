package com.rodyto.lenspro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ================================================================
 *  Rodyto Lens Pro · MainActivity · v3.5 Pro (Liquid Glass + Híbrido)
 *
 *  NOVEDADES v3.5 (sobre v3.0):
 *   ✓ Arquitectura HÍBRIDA: Camera2 (motor primario) + CameraX (bridge
 *     Image Analysis). Se activa CameraXAnalysisBridge cuando el VM
 *     tiene useCameraXAnalysis = true.
 *   ✓ Forzado de Physical Camera ID "52" para el teleobjetivo del S21 FE
 *     (configurable desde SettingsActivity → ID por defecto "52", flag
 *     forceTelePhysicalId ON por defecto).
 *   ✓ Badge OPT/DIG visual permanente cuando se está en lente tele.
 *   ✓ Pill superior con id físico activo en uso (estilo Device Info HW).
 *   ✓ Persistencia bidireccional preservada al 100%.
 *   ✓ BackHandler, EXIF dinámico, launchSafe preservados.
 * ================================================================ */

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: CameraControlViewModel = viewModel()
            val darkPref by vm.darkTheme.collectAsStateWithLifecycle()
            val accentStyle by vm.accentStyle.collectAsStateWithLifecycle()
            LensProTheme(forceDark = darkPref, accentStyle = accentStyle) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    CameraPermissionWrapper(vm)
                }
            }
        }
    }
}

@Composable
fun CameraPermissionWrapper(viewModel: CameraControlViewModel) {
    val context = LocalContext.current
    val darkPref by viewModel.darkTheme.collectAsStateWithLifecycle()
    val accentStyle by viewModel.accentStyle.collectAsStateWithLifecycle()
    val palette = glassPalette(darkPref, accentStyle)

    val required = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.VIBRATE)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun checkAllGranted(): Boolean = required.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(checkAllGranted()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = required.all { permission ->
            result[permission] == true ||
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(required.toTypedArray())
    }

    val repo = remember { SettingsRepository(context) }
    LaunchedEffect(granted) {
        if (granted) {
            viewModel.applyPersistedSettings(repo)
            viewModel.attachToRepository(repo)
        }
    }

    if (granted) {
        CameraScreen(viewModel, repo)
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            palette.letterboxTop,
                            Color.Black,
                            palette.letterboxBottom
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            UltraThinPanel(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth(),
                palette = palette,
                cornerRadius = 32.dp,
                strong = true
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        "Rodyto Lens Pro v3.5 necesita acceso a la cámara y al micrófono.",
                        color = palette.onGlass,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Arquitectura híbrida Camera2 + CameraX. Tele óptico forzado en Samsung S21 FE.",
                        color = palette.onGlassSecondary,
                        fontSize = 14.sp
                    )
                    Button(
                        onClick = { launcher.launch(required.toTypedArray()) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.accent,
                            contentColor = palette.onAccent
                        )
                    ) {
                        Text("Conceder permisos", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun CameraScreen(vm: CameraControlViewModel, repo: SettingsRepository) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // ─── Estado del ViewModel ───────────────────────────────────────────────
    val lens by vm.currentLens.collectAsStateWithLifecycle()
    val mode by vm.cameraMode.collectAsStateWithLifecycle()
    val isFront by vm.isFrontCamera.collectAsStateWithLifecycle()
    val focusLocked by vm.focusLocked.collectAsStateWithLifecycle()
    val isRecording by vm.isRecording.collectAsStateWithLifecycle()
    val flashMode by vm.flashMode.collectAsStateWithLifecycle()
    val exposureEv by vm.exposureEv.collectAsStateWithLifecycle()
    val lastUri by vm.lastPhotoUri.collectAsStateWithLifecycle()
    val gridOn by vm.gridEnabled.collectAsStateWithLifecycle()
    val timerSec by vm.timerSeconds.collectAsStateWithLifecycle()
    val hdrOn by vm.hdrEnabled.collectAsStateWithLifecycle()
    val soundOn by vm.shutterSoundEnabled.collectAsStateWithLifecycle()
    val hapticsOn by vm.hapticsEnabled.collectAsStateWithLifecycle()
    val hevcOn by vm.hevcEnabled.collectAsStateWithLifecycle()
    val videoRes by vm.videoResolution.collectAsStateWithLifecycle()
    val videoFps by vm.videoFps.collectAsStateWithLifecycle()
    val manualAspect by vm.manualAspect.collectAsStateWithLifecycle()
    val darkPref by vm.darkTheme.collectAsStateWithLifecycle()
    val accentStyle by vm.accentStyle.collectAsStateWithLifecycle()
    val zoomLevel by vm.zoomLevel.collectAsStateWithLifecycle()
    val zoomMax by vm.zoomMax.collectAsStateWithLifecycle()
    val histogramOn by vm.histogramEnabled.collectAsStateWithLifecycle()
    val horizonOn by vm.horizonEnabled.collectAsStateWithLifecycle()
    val histogramBins by vm.histogramBins.collectAsStateWithLifecycle()
    val rawOn by vm.rawCapture.collectAsStateWithLifecycle()
    val supports60 by vm.supports60fps.collectAsStateWithLifecycle()
    val iso by vm.iso.collectAsStateWithLifecycle()
    val shutterNs by vm.shutterSpeedNs.collectAsStateWithLifecycle()
    val availableLenses by vm.availableLenses.collectAsStateWithLifecycle()
    val telephotoIsOptical by vm.telephotoIsOptical.collectAsStateWithLifecycle()
    // ── Nuevo v3.5 ──
    val useCameraX by vm.useCameraXAnalysis.collectAsStateWithLifecycle()
    val activePhysicalTeleId by vm.activePhysicalTeleId.collectAsStateWithLifecycle()
    val sessionState by vm.sessionState.collectAsStateWithLifecycle()

    val palette = glassPalette(darkPref, accentStyle)

    val orgByDate by repo.organizeByDate.collectAsStateWithLifecycle(initialValue = true)
    val storage = remember { MediaStorageManager(organizeByDate = true) }
    storage.organizeByDate = orgByDate

    val fx = remember { ShutterFx() }
    DisposableEffect(Unit) { onDispose { fx.release() } }

    // EXIF dinámico vía rotación
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME || event == Lifecycle.Event.ON_CREATE) {
                val windowManager = context.getSystemService(android.content.Context.WINDOW_SERVICE)
                    as? android.view.WindowManager
                val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.display?.rotation ?: Surface.ROTATION_0
                } else {
                    @Suppress("DEPRECATION")
                    windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
                }
                val degrees = when (rotation) {
                    Surface.ROTATION_90  -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else                 -> 0
                }
                vm.notifyDeviceRotation(degrees)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ─── Estado UI local ────────────────────────────────────────────────────
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var zoomDialOpen by remember { mutableStateOf(false) }
    var zoomControlOpen by remember { mutableStateOf(false) }
    var settingsIconRotation by remember { mutableStateOf(0f) }
    var countdown by remember { mutableStateOf(0) }
    var recordingSeconds by remember { mutableStateOf(0L) }
    var blinkKey by remember { mutableStateOf(0) }
    var previewBounds by remember { mutableStateOf<Rect?>(null) }
    var pinchInProgress by remember { mutableStateOf(false) }

    LaunchedEffect(pinchInProgress, zoomLevel) {
        if (pinchInProgress) {
            zoomControlOpen = true
        } else if (zoomControlOpen) {
            delay(2500L)
            if (!pinchInProgress) zoomControlOpen = false
        }
    }

    androidx.activity.compose.BackHandler(enabled = settingsOpen || zoomDialOpen || zoomControlOpen) {
        when {
            settingsOpen -> { settingsOpen = false; settingsIconRotation += 180f }
            zoomDialOpen -> zoomDialOpen = false
            zoomControlOpen -> zoomControlOpen = false
        }
    }

    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(2200L)
            focusPoint = null
        }
    }

    LaunchedEffect(isRecording) {
        recordingSeconds = 0
        while (isRecording) {
            delay(1000)
            recordingSeconds += 1
        }
    }

    val settingsRotationAnim by animateFloatAsState(
        targetValue = settingsIconRotation,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "settings_rotation"
    )

    suspend fun doShutter() {
        if (timerSec > 0 && mode == "FOTO") {
            for (seconds in timerSec downTo 1) {
                countdown = seconds
                delay(1000)
            }
            countdown = 0
        }
        when {
            mode == "FOTO" -> {
                if (soundOn) fx.shutter()
                blinkKey++
                vm.takePicture(storage, context)
            }
            mode == "VIDEO" && !isRecording -> {
                if (soundOn) fx.videoStart()
                vm.startVideoRecording(context, storage)
            }
            mode == "VIDEO" && isRecording -> {
                if (soundOn) fx.videoStop()
                vm.stopVideoRecording(context, storage)
            }
        }
    }

    // Helpers persistencia (UI → repo)
    fun persistFlash(mode: FlashMode) = coroutineScope.launch { repo.setFlashMode(mode.name) }
    fun persistHdr(v: Boolean) = coroutineScope.launch { repo.setHdr(v) }
    fun persistGrid(v: Boolean) = coroutineScope.launch { repo.setGrid(v) }
    fun persistRaw(v: Boolean) = coroutineScope.launch { repo.setRaw(v) }
    fun persistHevc(v: Boolean) = coroutineScope.launch { repo.setHevc(v) }
    fun persistSound(v: Boolean) = coroutineScope.launch { repo.setSound(v) }
    fun persistHaptics(v: Boolean) = coroutineScope.launch { repo.setHaptics(v) }
    fun persistTimer(v: Int) = coroutineScope.launch { repo.setTimer(v) }
    fun persistAspect(a: PreviewAspect?) = coroutineScope.launch { repo.setManualAspect(repo.aspectToLabel(a)) }
    fun persistVideoRes(r: VideoResolution) = coroutineScope.launch { repo.setVideoResolution(r.name) }
    fun persistVideoFps(f: VideoFps) = coroutineScope.launch { repo.setVideoFps(f.value) }
    fun persistLens(s: String) = coroutineScope.launch { repo.setLastLens(s) }
    fun persistAccent(s: AccentStyle) = coroutineScope.launch { repo.setAccentIndex(repo.indexOfAccent(s)) }
    fun persistTheme(v: Boolean?) = coroutineScope.launch { repo.setThemeMode(repo.themeToString(v)) }

    // ── v3.5: CameraX bridge (Image Analysis paralelo) ──
    // Sólo se monta cuando el usuario lo ha habilitado. Esto evita conflictos
    // con la sesión Camera2 del VM en dispositivos donde el HAL no soporta
    // dos clientes simultáneos sobre el mismo id físico.
    if (useCameraX) {
        CameraXAnalysisBridge(vm = vm)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        palette.letterboxTop,
                        Color.Black,
                        palette.letterboxBottom
                    )
                )
            )
    ) {
        val screenW = with(density) { maxWidth.toPx() }.toInt()
        val screenH = with(density) { maxHeight.toPx() }.toInt()

        // ─── Preview con pinch-to-zoom ──────────────────────────────────
        CameraPreview(
            viewModel = vm,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(focusLocked) {
                    detectTapGestures(
                        onTap = { offset ->
                            if (!focusLocked) {
                                focusPoint = offset
                                Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                                if (soundOn) fx.focusTick()
                                vm.tapToFocus(offset.x, offset.y, screenW, screenH)
                            }
                        },
                        onLongPress = {
                            Haptics.perform(view, Haptics.Kind.LONG, hapticsOn)
                            vm.toggleFocusLock()
                            focusPoint = it
                        }
                    )
                },
            onPreviewBoundsChanged = { previewBounds = it },
            onPinchInProgress = { pinchInProgress = it }
        )

        PreviewLetterboxChrome(
            palette = palette,
            screenHeightPx = screenH.toFloat(),
            previewBounds = previewBounds
        )

        ShutterBlinkOverlay(triggerKey = blinkKey)

        // ─── Cuadrícula 3×3 ──────────────────────────────────────────────
        AnimatedVisibility(
            visible = gridOn,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(220))
        ) {
            GridOverlay(previewBounds = previewBounds)
        }

        // ─── Horizonte artificial ─────────────────────────────────────────
        HorizonLevelOverlay(
            enabled = horizonOn,
            previewBounds = previewBounds,
            palette = palette
        )

        // ─── Punto de enfoque + slider EV ────────────────────────
        focusPoint?.let { point ->
            val xDp = with(density) { point.x.toDp() }
            val yDp = with(density) { point.y.toDp() }
            val color = if (focusLocked) LensRecRed else palette.accent
            val scaleAnim = remember { Animatable(1.25f) }
            LaunchedEffect(Unit) {
                scaleAnim.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            }

            Box(
                modifier = Modifier
                    .offset(x = xDp - 34.dp, y = yDp - 34.dp)
                    .size(68.dp)
                    .scale(scaleAnim.value)
                    .border(1.5.dp, color, RoundedCornerShape(16.dp))
            )

            val evRange = vm.getExposureEvRange()
            ExposureSliderEv(
                valueEv = exposureEv,
                rangeEv = evRange,
                onValueChange = { vm.setExposureEv(it) },
                palette = palette,
                onHaptic = { Haptics.perform(view, Haptics.Kind.SELECT, hapticsOn) },
                modifier = Modifier.offset(x = xDp + 52.dp, y = yDp - 108.dp)
            )
        }

        // ─── Top bar ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            GlassBubble(
                size = 48.dp,
                palette = palette,
                onClick = {
                    settingsOpen = true
                    settingsIconRotation += 180f
                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                }
            ) {
                LensIcon(
                    icon = LensIcons.Settings,
                    tint = palette.onGlass,
                    size = 20.dp,
                    modifier = Modifier.rotate(settingsRotationAnim)
                )
            }

            // Pill central
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AnimatedVisibility(
                    visible = isRecording,
                    enter = fadeIn() + slideInVertically { -it },
                    exit = fadeOut() + slideOutVertically { -it }
                ) {
                    StatusPill(
                        label = formatElapsed(recordingSeconds),
                        palette = palette,
                        leadingColor = LensRecRed
                    )
                }
                AnimatedVisibility(
                    visible = !isRecording && (iso != null || shutterNs != null),
                    enter = fadeIn(tween(220)),
                    exit = fadeOut(tween(180))
                ) {
                    SensorMetaPill(iso = iso, shutterNs = shutterNs, palette = palette)
                }
                // v3.5: id físico activo (sólo si forzamos tele)
                AnimatedVisibility(
                    visible = !isRecording && activePhysicalTeleId.isNotEmpty(),
                    enter = fadeIn(tween(220)),
                    exit = fadeOut(tween(180))
                ) {
                    PhysicalIdPill(id = activePhysicalTeleId, palette = palette)
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassBubble(
                    size = 48.dp,
                    palette = palette,
                    onClick = {
                        Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                        zoomDialOpen = true
                    }
                ) {
                    LensIcon(icon = LensIcons.Sparkle, tint = palette.accent, size = 18.dp)
                }

                AnimatedVisibility(
                    visible = mode == "VIDEO" && !isFront && !isRecording,
                    enter = fadeIn() + slideInHorizontally { it },
                    exit = fadeOut() + slideOutHorizontally { it }
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PillButton(
                            text = videoRes.label,
                            palette = palette,
                            onClick = {
                                Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                                val next = when (videoRes) {
                                    VideoResolution.HD -> VideoResolution.FHD
                                    VideoResolution.FHD -> VideoResolution.UHD
                                    VideoResolution.UHD -> VideoResolution.HD
                                }
                                vm.setVideoResolution(next); persistVideoRes(next)
                            }
                        )
                        FpsPillButton(
                            currentFps = videoFps,
                            supports60 = supports60,
                            palette = palette,
                            onClick = {
                                Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                                val next = when (videoFps) {
                                    VideoFps.FPS30 -> VideoFps.FPS60
                                    VideoFps.FPS60 -> VideoFps.FPS30
                                }
                                vm.setVideoFps(next); persistVideoFps(next)
                            }
                        )
                    }
                }
            }
        }

        // ─── Histograma ───────────────────────────────────────────────────
        AnimatedVisibility(
            visible = histogramOn && histogramBins != null,
            enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.92f, animationSpec = tween(220)),
            exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.92f, animationSpec = tween(180)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 76.dp, end = 16.dp)
        ) {
            HistogramView(bins = histogramBins, palette = palette)
        }

        // ─── Countdown overlay ────────────────────────────────────────────
        if (countdown > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.26f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$countdown",
                    color = Color.White,
                    fontSize = 128.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ─── ZoomControl flotante ──
        AnimatedVisibility(
            visible = zoomControlOpen,
            enter = fadeIn(tween(180)) + slideInHorizontally { it / 2 },
            exit = fadeOut(tween(160)) + slideOutHorizontally { it / 2 },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
        ) {
            ZoomControl(
                currentZoom = zoomLevel,
                minZoom = CameraControlViewModel.MIN_ZOOM,
                maxZoom = zoomMax,
                palette = palette,
                visible = true,
                onZoomChange = { vm.setZoomContinuous(it) },
                onSmoothZoomTo = { vm.smoothZoomTo(it) },
                onHapticTick = { Haptics.perform(view, Haptics.Kind.SELECT, hapticsOn) },
                onDismiss = { zoomControlOpen = false },
                showOpticalHint = (zoomLevel >= 1.8f),
                isOptical = telephotoIsOptical
            )
        }

        // ─── Bottom controls ──────────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LensSelectorRow(
                currentLens = lens,
                palette = palette,
                onSelect = { selected ->
                    Haptics.perform(view, Haptics.Kind.SELECT, hapticsOn)
                    vm.switchLens(context, selected)
                    persistLens(selected)
                },
                modifier = Modifier.padding(bottom = 12.dp),
                availableLenses = availableLenses,
                telephotoIsOptical = telephotoIsOptical,
                onLongPressLens = { zoomControlOpen = true }
            )

            ActionChipBar(
                palette = palette,
                flashMode = flashMode,
                onToggleFlash = {
                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                    vm.toggleFlash(); persistFlash(vm.flashMode.value)
                },
                hdrOn = hdrOn,
                onToggleHdr = {
                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                    vm.toggleHdr(); persistHdr(!hdrOn)
                },
                timerSec = timerSec,
                onCycleTimer = {
                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                    vm.cycleTimer(); persistTimer(vm.timerSeconds.value)
                },
                soundOn = soundOn,
                onToggleSound = {
                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                    vm.toggleShutterSound(); persistSound(!soundOn)
                },
                aspectLabel = when (manualAspect) {
                    PreviewAspect.RATIO_3_4 -> "3:4"
                    PreviewAspect.RATIO_9_16 -> "9:16"
                    PreviewAspect.RATIO_1_1 -> "1:1"
                    PreviewAspect.RATIO_FULL -> "Full"
                    null -> "Auto"
                },
                onCycleAspect = {
                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                    val next = when (manualAspect) {
                        null -> PreviewAspect.RATIO_3_4
                        PreviewAspect.RATIO_3_4 -> PreviewAspect.RATIO_9_16
                        PreviewAspect.RATIO_9_16 -> PreviewAspect.RATIO_1_1
                        PreviewAspect.RATIO_1_1 -> PreviewAspect.RATIO_FULL
                        PreviewAspect.RATIO_FULL -> null
                    }
                    vm.setManualAspect(next); persistAspect(next)
                },
                onOpenMore = {
                    settingsOpen = true
                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                },
                rawOn = rawOn,
                onToggleRaw = {
                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                    vm.toggleRaw(); persistRaw(!rawOn)
                },
                onOpenSettings = {
                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                    runCatching {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    }
                },
                modifier = Modifier.padding(bottom = 12.dp)
            )

            ModeSelectorIos(
                mode = mode,
                palette = palette,
                onModeChange = {
                    Haptics.perform(view, Haptics.Kind.SELECT, hapticsOn)
                    vm.setCameraMode(it)
                },
                modifier = Modifier.padding(bottom = 14.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GalleryThumb(
                    lastUri = lastUri,
                    palette = palette,
                    onOpen = {
                        Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                        runCatching {
                            val mime = if (mode == "VIDEO") "video/*" else "image/*"
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(lastUri, mime)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        }
                    }
                )

                ShutterButtonPro(
                    isRecording = isRecording,
                    mode = mode,
                    onTap = {
                        Haptics.perform(
                            view,
                            if (isRecording) Haptics.Kind.WARN else Haptics.Kind.SUCCESS,
                            hapticsOn
                        )
                        coroutineScope.launchSafe { doShutter() }
                    },
                    onSwipeToVideo = {
                        Haptics.perform(view, Haptics.Kind.SELECT, hapticsOn)
                        vm.setCameraMode("VIDEO")
                    },
                    onSwipeToPhoto = {
                        Haptics.perform(view, Haptics.Kind.SELECT, hapticsOn)
                        vm.setCameraMode("FOTO")
                    },
                    onPressFeedback = { Haptics.perform(view, Haptics.Kind.TAP, hapticsOn) }
                )

                GlassBubble(
                    size = 54.dp,
                    palette = palette,
                    onClick = {
                        Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                        vm.toggleFrontCamera(context)
                    }
                ) {
                    LensIcon(icon = LensIcons.Flip, tint = palette.onGlass, size = 24.dp)
                }
            }
        }

        // ─── Zoom dial popup ──
        AnimatedVisibility(
            visible = zoomDialOpen,
            enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.92f, animationSpec = tween(220)),
            exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.92f, animationSpec = tween(180))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.46f))
                    .clickable { zoomDialOpen = false },
                contentAlignment = Alignment.Center
            ) {
                UltraThinPanel(
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .clickable(enabled = false, onClick = {}),
                    palette = palette,
                    cornerRadius = 34.dp,
                    strong = true
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Zoom de precisión",
                            color = palette.onGlass,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.size(18.dp))
                        ZoomDial(
                            currentZoom = zoomLevel,
                            palette = palette,
                            onZoomChange = { vm.setZoom(it) },
                            onHapticTick = { Haptics.perform(view, Haptics.Kind.SELECT, hapticsOn) },
                            onSmoothZoom = { vm.smoothZoomTo(it) }
                        )
                        Spacer(Modifier.size(20.dp))
                        Button(
                            onClick = { zoomDialOpen = false },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.accent,
                                contentColor = palette.onAccent
                            )
                        ) {
                            Text("Cerrar", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // ─── Settings panel (quick) ───────────────────────────────────────
        AnimatedVisibility(
            visible = settingsOpen,
            enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.95f, animationSpec = tween(220)),
            exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.95f, animationSpec = tween(180))
        ) {
            SettingsPanel(
                palette = palette,
                maxHeightFraction = 0.85f,
                flashMode = flashMode,
                onToggleFlash = { vm.toggleFlash(); persistFlash(vm.flashMode.value) },
                hdrOn = hdrOn,
                onToggleHdr = { vm.toggleHdr(); persistHdr(!hdrOn) },
                gridOn = gridOn,
                onToggleGrid = { vm.toggleGrid(); persistGrid(!gridOn) },
                soundOn = soundOn,
                onToggleSound = { vm.toggleShutterSound(); persistSound(!soundOn) },
                hapticsOn = hapticsOn,
                onToggleHaptics = { vm.toggleHaptics(); persistHaptics(!hapticsOn) },
                hevcOn = hevcOn,
                onToggleHevc = { vm.toggleHevc(); persistHevc(!hevcOn) },
                histogramOn = histogramOn,
                onToggleHistogram = {
                    val next = !histogramOn
                    vm.setHistogramEnabled(next)
                    coroutineScope.launch { repo.setHistogram(next) }
                },
                horizonOn = horizonOn,
                onToggleHorizon = {
                    val next = !horizonOn
                    vm.setHorizonEnabled(next)
                    coroutineScope.launch { repo.setHorizon(next) }
                },
                rawOn = rawOn,
                onToggleRaw = { vm.toggleRaw(); persistRaw(!rawOn) },
                timerSec = timerSec,
                onCycleTimer = { vm.cycleTimer(); persistTimer(vm.timerSeconds.value) },
                darkPref = darkPref,
                onCycleTheme = { vm.cycleTheme(); persistTheme(vm.darkTheme.value) },
                accentStyleLabel = accentStyle.label,
                onCycleAccentStyle = { vm.cycleAccentStyle(); persistAccent(vm.accentStyle.value) },
                manualAspect = manualAspect,
                onAspectChange = { vm.setManualAspect(it); persistAspect(it) },
                onOpenFullSettings = {
                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                    settingsOpen = false
                    runCatching {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    }
                },
                onClose = {
                    settingsOpen = false
                    settingsIconRotation += 180f
                },
                onAnyAction = { Haptics.perform(view, Haptics.Kind.TAP, hapticsOn) }
            )
        }
    }
}

/* ================================================================
 * COMPONENTES AUXILIARES
 * ================================================================ */

@Composable
private fun BoxWithConstraintsScope.PreviewLetterboxChrome(
    palette: GlassPalette,
    screenHeightPx: Float,
    previewBounds: Rect?
) {
    if (previewBounds == null) return
    val density = LocalDensity.current
    val topHeight = with(density) { previewBounds.top.toDp() }
    val bottomHeight = with(density) {
        (screenHeightPx - previewBounds.bottom).coerceAtLeast(0f).toDp()
    }

    if (topHeight > 2.dp) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(topHeight)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            palette.letterboxTop,
                            palette.bgStrong,
                            Color.Transparent
                        )
                    )
                )
        )
    }

    if (bottomHeight > 2.dp) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bottomHeight)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            palette.bgStrong,
                            palette.letterboxBottom
                        )
                    )
                )
        )
    }
}

@Composable
private fun StatusPill(label: String, palette: GlassPalette, leadingColor: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(30.dp))
            .liquidGlass(palette, RoundedCornerShape(30.dp), strong = true)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(leadingColor)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = label,
            color = palette.onGlass,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SensorMetaPill(
    iso: Int?,
    shutterNs: Long?,
    palette: GlassPalette
) {
    val isoText = iso?.let { "ISO $it" }
    val shutterText = shutterNs?.let { ns ->
        if (ns <= 0L) null
        else {
            val seconds = ns / 1_000_000_000.0
            if (seconds >= 1.0) "%.1fs".format(seconds)
            else {
                val denom = (1.0 / seconds).toInt().coerceAtLeast(1)
                "1/$denom"
            }
        }
    }
    val joined = listOfNotNull(isoText, shutterText).joinToString(" · ")
    if (joined.isBlank()) return

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .liquidGlass(palette, RoundedCornerShape(20.dp), strong = false)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = joined,
            color = palette.onGlassSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * NUEVO v3.5: pill mostrando el Physical Camera ID activo del tele forzado.
 * Inspirado en Device Info HW: "System ID = 52".
 */
@Composable
private fun PhysicalIdPill(id: String, palette: GlassPalette) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .liquidGlass(palette, RoundedCornerShape(20.dp), strong = false)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(palette.accent)
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = "Tele · System ID = $id",
            color = palette.onGlass,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
    }
}

@Composable
private fun GalleryThumb(
    lastUri: android.net.Uri?,
    palette: GlassPalette,
    onOpen: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(RoundedCornerShape(20.dp))
            .liquidGlass(palette, RoundedCornerShape(20.dp), strong = false)
            .clickable(enabled = lastUri != null, onClick = onOpen),
        contentAlignment = Alignment.Center
    ) {
        if (lastUri != null) {
            AsyncImage(
                model = lastUri,
                contentDescription = "Última captura",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            LensIcon(
                icon = LensIcons.Gallery,
                tint = palette.onGlassSecondary,
                size = 22.dp
            )
        }
    }
}

@Composable
fun PillButton(text: String, palette: GlassPalette, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .liquidGlass(palette, RoundedCornerShape(20.dp), strong = false)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            color = palette.onGlass,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun FpsPillButton(
    currentFps: VideoFps,
    supports60: Boolean,
    palette: GlassPalette,
    onClick: () -> Unit
) {
    val showing60 = currentFps == VideoFps.FPS60
    val warningColor = Color(0xFFFFB020)
    val tint = if (showing60 && !supports60) warningColor else palette.onGlass

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .liquidGlass(palette, RoundedCornerShape(20.dp), strong = false)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = "${currentFps.label} fps",
            color = tint,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun GridOverlay(previewBounds: Rect?) {
    if (previewBounds == null) return
    val density = LocalDensity.current
    val topPad = with(density) { previewBounds.top.toDp() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 10.dp, end = 10.dp, top = topPad, bottom = 0.dp)
    ) {
        val lineColor = Color.White.copy(alpha = 0.22f)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { previewBounds.height.toDp() })
        ) {
            repeat(3) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .border(0.5.dp, lineColor)
                        )
                    }
                }
            }
        }
    }
}

/**
 * ExposureSlider LEGACY — conservado para retrocompat.
 */
@Composable
fun ExposureSlider(
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    palette: GlassPalette,
    onHaptic: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val range = (max - min).coerceAtLeast(1).toFloat()
    var accumulator by remember { mutableStateOf(0f) }

    Column(
        modifier = modifier
            .height(190.dp)
            .width(42.dp)
            .clip(RoundedCornerShape(30.dp))
            .liquidGlass(palette, RoundedCornerShape(30.dp), strong = true)
            .pointerInput(min, max, value) {
                detectVerticalDragGestures(onDragStart = { accumulator = 0f }) { _, dragAmount ->
                    accumulator += -dragAmount / 8f
                    val delta = accumulator.toInt()
                    if (delta != 0) {
                        accumulator -= delta.toFloat()
                        val newValue = (value + delta).coerceIn(min, max)
                        if (newValue != value) {
                            onHaptic()
                            onValueChange(newValue)
                        }
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LensIcon(LensIcons.Brightness, tint = palette.accent, size = 18.dp)
        Spacer(Modifier.size(6.dp))
        Text(
            text = if (value > 0) "+$value" else "$value",
            color = palette.onGlass,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.size(6.dp))
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(108.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.onGlassSecondary.copy(alpha = 0.18f))
        ) {
            val pct = ((value - min) / range).coerceIn(0f, 1f)
            val animatedPct by animateFloatAsState(
                targetValue = pct,
                animationSpec = tween(120),
                label = "exp_pct"
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(animatedPct)
                    .align(Alignment.BottomStart)
                    .background(palette.accent)
            )
        }
    }
}

/* ================================================================
 * SETTINGS PANEL — quick settings sheet
 * ================================================================ */
@Composable
fun SettingsPanel(
    palette: GlassPalette,
    maxHeightFraction: Float = 0.85f,
    flashMode: FlashMode,
    onToggleFlash: () -> Unit,
    hdrOn: Boolean,
    onToggleHdr: () -> Unit,
    gridOn: Boolean,
    onToggleGrid: () -> Unit,
    soundOn: Boolean,
    onToggleSound: () -> Unit,
    hapticsOn: Boolean,
    onToggleHaptics: () -> Unit,
    hevcOn: Boolean,
    onToggleHevc: () -> Unit,
    histogramOn: Boolean,
    onToggleHistogram: () -> Unit,
    horizonOn: Boolean,
    onToggleHorizon: () -> Unit,
    rawOn: Boolean,
    onToggleRaw: () -> Unit,
    timerSec: Int,
    onCycleTimer: () -> Unit,
    darkPref: Boolean?,
    onCycleTheme: () -> Unit,
    accentStyleLabel: String,
    onCycleAccentStyle: () -> Unit,
    manualAspect: PreviewAspect?,
    onAspectChange: (PreviewAspect?) -> Unit,
    onOpenFullSettings: () -> Unit,
    onClose: () -> Unit,
    onAnyAction: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.48f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter
    ) {
        val maxPanelHeight = maxHeight * maxHeightFraction

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxPanelHeight)
                .clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp))
                .background(palette.ultraBase)
                .border(
                    width = 0.8.dp,
                    color = palette.ultraStroke,
                    shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)
                )
                .clickable(enabled = false, onClick = {}),
            verticalArrangement = Arrangement.Top
        ) {
            // Handle pill
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 12.dp, bottom = 2.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.onGlassSecondary.copy(alpha = 0.30f))
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Ajustes rápidos",
                    color = palette.onGlass,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(palette.onGlassSecondary.copy(alpha = 0.12f))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Text("×", color = palette.onGlassSecondary, fontSize = 20.sp, fontWeight = FontWeight.Light)
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { SectionLabel("Captura", palette) }
                item {
                    SettingsActionRow(
                        label = "Flash",
                        value = when (flashMode) {
                            FlashMode.OFF -> "Off"; FlashMode.AUTO -> "Auto"; FlashMode.ON -> "On"
                        },
                        palette = palette
                    ) { onAnyAction(); onToggleFlash() }
                }
                item { SettingsRow("HDR", hdrOn, palette) { onAnyAction(); onToggleHdr() } }
                item { SettingsRow("Formato RAW (DNG)", rawOn, palette) { onAnyAction(); onToggleRaw() } }
                item { SettingsRow("Codec HEVC (H.265)", hevcOn, palette) { onAnyAction(); onToggleHevc() } }
                item {
                    SettingsActionRow(
                        label = "Temporizador",
                        value = when (timerSec) { 3 -> "3 s"; 10 -> "10 s"; else -> "Off" },
                        palette = palette
                    ) { onAnyAction(); onCycleTimer() }
                }

                item { SectionLabel("Composición", palette) }
                item { SettingsRow("Cuadrícula 3×3", gridOn, palette) { onAnyAction(); onToggleGrid() } }
                item {
                    SettingsRow("Histograma en tiempo real", histogramOn, palette) {
                        onAnyAction(); onToggleHistogram()
                    }
                }
                item {
                    SettingsRow("Horizonte artificial", horizonOn, palette) {
                        onAnyAction(); onToggleHorizon()
                    }
                }

                item { SectionLabel("Sonido y vibración", palette) }
                item {
                    SettingsRow("Sonido del obturador", soundOn, palette) {
                        onAnyAction(); onToggleSound()
                    }
                }
                item {
                    SettingsRow("Vibración háptica", hapticsOn, palette) {
                        onAnyAction(); onToggleHaptics()
                    }
                }

                item { SectionLabel("Apariencia", palette) }
                item {
                    SettingsActionRow(
                        label = "Tema",
                        value = when (darkPref) { true -> "Oscuro"; false -> "Claro"; null -> "Sistema" },
                        palette = palette
                    ) { onAnyAction(); onCycleTheme() }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Color de interfaz",
                            color = palette.onGlass,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = accentStyleLabel,
                            color = palette.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AccentStyle.entries.take(8).forEach { style ->
                                val isSelected = style.label == accentStyleLabel
                                Box(
                                    modifier = Modifier
                                        .size(if (isSelected) 34.dp else 28.dp)
                                        .clip(CircleShape)
                                        .background(style.accent)
                                        .border(
                                            width = if (isSelected) 2.5.dp else 0.dp,
                                            color = if (isSelected) palette.onGlass else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .clickable { onAnyAction(); onCycleAccentStyle() }
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AccentStyle.entries.drop(8).forEach { style ->
                                val isSelected = style.label == accentStyleLabel
                                Box(
                                    modifier = Modifier
                                        .size(if (isSelected) 34.dp else 28.dp)
                                        .clip(CircleShape)
                                        .background(style.accent)
                                        .border(
                                            width = if (isSelected) 2.5.dp else 0.dp,
                                            color = if (isSelected) palette.onGlass else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .clickable { onAnyAction(); onCycleAccentStyle() }
                                )
                            }
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Relación de aspecto",
                            color = palette.onGlass,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val options = listOf(
                                "Auto" to null,
                                "3:4" to PreviewAspect.RATIO_3_4,
                                "9:16" to PreviewAspect.RATIO_9_16,
                                "1:1" to PreviewAspect.RATIO_1_1,
                                "Full" to PreviewAspect.RATIO_FULL
                            )
                            options.forEach { (label, value) ->
                                val selected = manualAspect == value
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(
                                            if (selected) palette.accent else Color.Transparent
                                        )
                                        .border(
                                            width = if (selected) 0.dp else 0.6.dp,
                                            color = if (selected) Color.Transparent else palette.borderSoft,
                                            shape = RoundedCornerShape(20.dp)
                                        )
                                        .clickable {
                                            onAnyAction()
                                            onAspectChange(value)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (selected) palette.onAccent else palette.onGlass,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .liquidGlass(palette, RoundedCornerShape(22.dp), strong = true)
                            .clickable(onClick = onOpenFullSettings)
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LensIcon(icon = LensIcons.More, tint = palette.accent, size = 20.dp)
                            Spacer(Modifier.size(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Configuración avanzada",
                                    color = palette.onGlass,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "RAW, zoom suave, histograma, Camera2/CameraX y más",
                                    color = palette.onGlassSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            LensIcon(
                                icon = LensIcons.ChevronRight,
                                tint = palette.onGlassSecondary,
                                size = 18.dp
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(6.dp)) }
            }

            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.onAccent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text("Cerrar", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, palette: GlassPalette) {
    Text(
        text = text.uppercase(),
        color = palette.onGlassSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun SettingsActionRow(
    label: String,
    value: String,
    palette: GlassPalette,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = palette.onGlass,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        PillButton(text = value, palette = palette, onClick = onClick)
    }
}

@Composable
private fun SettingsRow(
    label: String,
    checked: Boolean,
    palette: GlassPalette,
    onCheckedChange: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = palette.onGlass,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = { onCheckedChange() },
            colors = SwitchDefaults.colors(
                checkedTrackColor = palette.accent,
                checkedThumbColor = palette.onAccent,
                uncheckedTrackColor = palette.onGlassSecondary.copy(alpha = 0.25f),
                uncheckedThumbColor = palette.onGlass
            )
        )
    }
}

/* ================================================================
 * HELPERS
 * ================================================================ */

private fun formatElapsed(seconds: Long): String {
    val minutes = seconds / 60
    val remaining = seconds % 60
    return "%02d:%02d".format(minutes, remaining)
}

private fun CoroutineScope.launchSafe(block: suspend () -> Unit) {
    launch {
        try {
            block()
        } catch (e: Exception) {
            android.util.Log.w("RodytoLensPro", "launchSafe: excepción capturada", e)
        }
    }
}
