package com.rodyto.lenspro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ================================================================
 *  RODYTO LENS PRO  —  iOS 19 Liquid Glass Camera (PREMIUM REDESIGN)
 * ================================================================ */

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: CameraControlViewModel = viewModel()
            val darkPref by vm.darkTheme.collectAsStateWithLifecycle()
            LensProTheme(forceDark = darkPref) {
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
    val required = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.VIBRATE)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    fun checkAllGranted(): Boolean = required.all { p ->
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    }
    var granted by remember { mutableStateOf(checkAllGranted()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = required.all { p ->
            result[p] == true ||
                ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        }
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(required.toTypedArray()) }

    if (granted) {
        CameraScreen(viewModel)
    } else {
        Box(
            Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "LensPro necesita acceso a la cámara y al micrófono.",
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp)
                )
                Button(
                    onClick = { launcher.launch(required.toTypedArray()) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LensAccent, contentColor = Color.Black
                    )
                ) { Text("Conceder permisos", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/* ----------------------------------------------------------------
 *                        PANTALLA PRINCIPAL
 * ---------------------------------------------------------------- */

@Composable
fun CameraScreen(vm: CameraControlViewModel) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val view    = LocalView.current

    // -- ESTADO DESDE VM --
    val lens          by vm.currentLens.collectAsStateWithLifecycle()
    val mode          by vm.cameraMode.collectAsStateWithLifecycle()
    val isFront       by vm.isFrontCamera.collectAsStateWithLifecycle()
    val focusLocked   by vm.focusLocked.collectAsStateWithLifecycle()
    val isRecording   by vm.isRecording.collectAsStateWithLifecycle()
    val flashOn       by vm.flashEnabled.collectAsStateWithLifecycle()
    val exposure      by vm.exposureLevel.collectAsStateWithLifecycle()
    val lastUri       by vm.lastPhotoUri.collectAsStateWithLifecycle()
    val gridOn        by vm.gridEnabled.collectAsStateWithLifecycle()
    val timerSec      by vm.timerSeconds.collectAsStateWithLifecycle()
    val hdrOn         by vm.hdrEnabled.collectAsStateWithLifecycle()
    val soundOn       by vm.shutterSoundEnabled.collectAsStateWithLifecycle()
    val hapticsOn     by vm.hapticsEnabled.collectAsStateWithLifecycle()
    val videoRes      by vm.videoResolution.collectAsStateWithLifecycle()
    val videoFps      by vm.videoFps.collectAsStateWithLifecycle()
    val manualAspect  by vm.manualAspect.collectAsStateWithLifecycle()
    val darkPref      by vm.darkTheme.collectAsStateWithLifecycle()

    val palette = glassPalette(darkPref)

    val storage = remember { MediaStorageManager() }
    val fx      = remember { ShutterFx() }
    DisposableEffect(Unit) { onDispose { fx.release() } }

    // -- UI LOCAL --
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsIconRotation by remember { mutableStateOf(0f) }
    var countdown by remember { mutableStateOf(0) }
    var recordingSeconds by remember { mutableStateOf(0L) }
    var blinkKey by remember { mutableStateOf(0) }

    LaunchedEffect(focusPoint) {
        if (focusPoint != null) { delay(2500L); focusPoint = null }
    }
    LaunchedEffect(isRecording) {
        recordingSeconds = 0
        while (isRecording) { delay(1000); recordingSeconds += 1 }
    }
    val settingsRotationAnim by animateFloatAsState(
        targetValue = settingsIconRotation,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "settings_rot"
    )

    suspend fun doShutter() {
        if (timerSec > 0 && mode == "FOTO") {
            for (s in timerSec downTo 1) { countdown = s; delay(1000) }
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

    val coroutineScope = rememberCoroutineScope()

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val screenW = with(density) { maxWidth.toPx() }.toInt()
        val screenH = with(density) { maxHeight.toPx() }.toInt()

        // -------- Preview --------
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
                }
        )

        // Blink overlay (50 ms al disparar)
        ShutterBlinkOverlay(triggerKey = blinkKey)

        // -------- Grid 3×3 --------
        AnimatedVisibility(
            visible = gridOn,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(220))
        ) { GridOverlay() }

        // -------- Cuadro de enfoque + slider exposición --------
        focusPoint?.let { pt ->
            val xDp = with(density) { pt.x.toDp() }
            val yDp = with(density) { pt.y.toDp() }
            val color = if (focusLocked) LensRecRed else LensAccent

            val scale = remember { Animatable(1.35f) }
            LaunchedEffect(Unit) {
                scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            }
            Box(
                modifier = Modifier
                    .offset(x = xDp - 36.dp, y = yDp - 36.dp)
                    .size(72.dp)
                    .scale(scale.value)
                    .border(1.5.dp, color, RoundedCornerShape(8.dp))
            )

            val range = vm.getExposureRange()
            if (range != null) {
                ExposureSlider(
                    value = exposure,
                    min = range.lower,
                    max = range.upper,
                    onValueChange = { vm.setExposure(it) },
                    palette = palette,
                    onHaptic = { Haptics.perform(view, Haptics.Kind.SELECT, hapticsOn) },
                    modifier = Modifier.offset(x = xDp + 50.dp, y = yDp - 110.dp)
                )
            }
        }

        // -------- Top bar superior --------
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Botón ajustes (izquierda) — ICONO VECTORIAL
            GlassBubble(
                size = 40.dp,
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

            // Badge de grabación (centro)
            AnimatedVisibility(
                visible = isRecording,
                enter = fadeIn() + slideInVertically { -it },
                exit  = fadeOut() + slideOutVertically { -it }
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .liquidGlass(palette, RoundedCornerShape(20.dp), 18f)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(LensRecRed))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        formatElapsed(recordingSeconds),
                        color = palette.onGlass, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Indicador Res/FPS (esquina superior derecha) – sólo VIDEO
            AnimatedVisibility(
                visible = mode == "VIDEO" && !isFront && !isRecording,
                enter = fadeIn() + slideInHorizontally { it },
                exit  = fadeOut() + slideOutHorizontally { it }
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PillButton(
                        text = videoRes.label,
                        palette = palette,
                        onClick = {
                            Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                            val next = when (videoRes) {
                                VideoResolution.HD  -> VideoResolution.FHD
                                VideoResolution.FHD -> VideoResolution.UHD
                                VideoResolution.UHD -> VideoResolution.HD
                            }
                            vm.setVideoResolution(next)
                        }
                    )
                    PillButton(
                        text = "${videoFps.label} fps",
                        palette = palette,
                        onClick = {
                            Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                            val nextFps = when (videoFps) {
                                VideoFps.FPS30 -> VideoFps.FPS60
                                VideoFps.FPS60 -> VideoFps.FPS30
                            }
                            vm.setVideoFps(nextFps)
                        }
                    )
                }
            }

            // Placeholder para mantener SpaceBetween
            if (!(mode == "VIDEO" && !isFront && !isRecording) && !isRecording) {
                Spacer(Modifier.size(40.dp))
            }
        }

        // -------- Countdown overlay --------
        if (countdown > 0) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "$countdown",
                    color = Color.White,
                    fontSize = 140.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // -------- Controles inferiores --------
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // (1) PILL de zoom estilo iOS — el grupo dentro de un sólo contenedor glass
            LensSelectorRow(
                currentLens = lens,
                palette = palette,
                onSelect = { selected ->
                    Haptics.perform(view, Haptics.Kind.SELECT, hapticsOn)
                    vm.switchLens(context, selected)
                },
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // (2) BARRA DE CHIPS DE ACCIÓN iOS 19 (flash / hdr / timer / sonido / aspecto / más)
            ActionChipBar(
                palette = palette,
                flashOn = flashOn,
                onToggleFlash = {
                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn); vm.toggleFlash()
                },
                hdrOn = hdrOn,
                onToggleHdr = {
                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn); vm.toggleHdr()
                },
                timerSec = timerSec,
                onCycleTimer = {
                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn); vm.cycleTimer()
                },
                soundOn = soundOn,
                onToggleSound = {
                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn); vm.toggleShutterSound()
                },
                aspectLabel = when (manualAspect) {
                    PreviewAspect.RATIO_3_4  -> "3:4"
                    PreviewAspect.RATIO_9_16 -> "9:16"
                    PreviewAspect.RATIO_1_1  -> "1:1"
                    PreviewAspect.RATIO_FULL -> "Full"
                    null -> "Auto"
                },
                onCycleAspect = {
                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                    val next = when (manualAspect) {
                        null                     -> PreviewAspect.RATIO_3_4
                        PreviewAspect.RATIO_3_4  -> PreviewAspect.RATIO_9_16
                        PreviewAspect.RATIO_9_16 -> PreviewAspect.RATIO_1_1
                        PreviewAspect.RATIO_1_1  -> PreviewAspect.RATIO_FULL
                        PreviewAspect.RATIO_FULL -> null
                    }
                    vm.setManualAspect(next)
                },
                onOpenMore = {
                    settingsOpen = true
                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                },
                modifier = Modifier.padding(bottom = 18.dp)
            )

            // (3) MODE SELECTOR iOS 19 (FOTO / VIDEO)
            ModeSelectorIos(
                mode = mode,
                palette = palette,
                onModeChange = {
                    Haptics.perform(view, Haptics.Kind.SELECT, hapticsOn)
                    vm.setCameraMode(it)
                },
                modifier = Modifier.padding(bottom = 14.dp)
            )

            // (4) FILA INFERIOR: Galería ← Shutter → Flip
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, end = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // GALERÍA (izquierda) — square thumbnail estilo iOS
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.ultraBase)
                        .border(0.6.dp, palette.ultraStroke, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (lastUri != null) {
                        AsyncImage(
                            model = lastUri,
                            contentDescription = "Última captura",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                                    runCatching {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(lastUri, "image/*")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(intent)
                                    }
                                },
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

                // SHUTTER (centro) — blanco premium
                ShutterButtonPro(
                    isRecording = isRecording,
                    mode = mode,
                    onTap = {
                        Haptics.perform(view,
                            if (isRecording) Haptics.Kind.WARN else Haptics.Kind.SUCCESS,
                            hapticsOn)
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
                    onPressFeedback = {
                        Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                    }
                )

                // FLIP (derecha) — icono vectorial Cameraswitch
                GlassBubble(
                    size = 52.dp,
                    palette = palette,
                    onClick = {
                        Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                        vm.toggleFrontCamera(context)
                    }
                ) {
                    LensIcon(
                        icon = LensIcons.Flip,
                        tint = palette.onGlass,
                        size = 24.dp
                    )
                }
            }
        }

        // -------- Panel de ajustes --------
        AnimatedVisibility(
            visible = settingsOpen,
            enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.95f, animationSpec = tween(220)),
            exit  = fadeOut(tween(180)) + scaleOut(targetScale = 0.95f, animationSpec = tween(180))
        ) {
            SettingsPanel(
                palette = palette,
                flashOn = flashOn, onToggleFlash = { vm.toggleFlash() },
                hdrOn = hdrOn, onToggleHdr = { vm.toggleHdr() },
                gridOn = gridOn, onToggleGrid = { vm.toggleGrid() },
                soundOn = soundOn, onToggleSound = { vm.toggleShutterSound() },
                hapticsOn = hapticsOn, onToggleHaptics = { vm.toggleHaptics() },
                timerSec = timerSec, onCycleTimer = { vm.cycleTimer() },
                darkPref = darkPref, onCycleTheme = { vm.cycleTheme() },
                manualAspect = manualAspect, onAspectChange = { vm.setManualAspect(it) },
                onClose = { settingsOpen = false },
                onAnyAction = { Haptics.perform(view, Haptics.Kind.TAP, hapticsOn) }
            )
        }
    }
}

/* ================================================================
 *                       COMPOSABLES UI
 * ================================================================ */

@Composable
fun PillButton(text: String, palette: GlassPalette, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .liquidGlass(palette, RoundedCornerShape(18.dp), 18f)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = palette.onGlass, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GridOverlay() {
    Box(Modifier.fillMaxSize()) {
        val lineColor = Color.White.copy(alpha = 0.32f)
        Column(Modifier.fillMaxSize()) {
            repeat(3) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    repeat(3) {
                        Box(
                            Modifier
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
 * Slider vertical de exposición — drag continuo, cuantizado al rango entero.
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
            .width(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .liquidGlass(palette, RoundedCornerShape(20.dp), 18f)
            .pointerInput(min, max) {
                detectVerticalDragGestures(
                    onDragStart = { accumulator = 0f }
                ) { _, dragAmount ->
                    accumulator += -dragAmount / 8f
                    val delta = accumulator.toInt()
                    if (delta != 0) {
                        accumulator -= delta.toFloat()
                        val newVal = (value + delta).coerceIn(min, max)
                        if (newVal != value) {
                            onHaptic()
                            onValueChange(newVal)
                        }
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LensIcon(LensIcons.Exposure, tint = LensAccent, size = 18.dp)
        Spacer(Modifier.size(6.dp))
        Text(
            if (value > 0) "+$value" else "$value",
            color = palette.onGlass, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.size(6.dp))
        Box(
            Modifier
                .width(4.dp)
                .height(108.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.onGlassSecondary.copy(alpha = 0.18f))
        ) {
            val pct = ((value - min) / range).coerceIn(0f, 1f)
            val animatedPct by animateFloatAsState(
                targetValue = pct, animationSpec = tween(120), label = "exp_pct"
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(animatedPct)
                    .align(Alignment.BottomStart)
                    .background(LensAccent)
            )
        }
    }
}

/* ----------------- Panel de Ajustes ----------------- */
@Composable
fun SettingsPanel(
    palette: GlassPalette,
    flashOn: Boolean, onToggleFlash: () -> Unit,
    hdrOn: Boolean, onToggleHdr: () -> Unit,
    gridOn: Boolean, onToggleGrid: () -> Unit,
    soundOn: Boolean, onToggleSound: () -> Unit,
    hapticsOn: Boolean, onToggleHaptics: () -> Unit,
    timerSec: Int, onCycleTimer: () -> Unit,
    darkPref: Boolean?, onCycleTheme: () -> Unit,
    manualAspect: PreviewAspect?, onAspectChange: (PreviewAspect?) -> Unit,
    onClose: () -> Unit,
    onAnyAction: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .clip(RoundedCornerShape(28.dp))
                .liquidGlass(palette, RoundedCornerShape(28.dp), 28f, strong = true)
                .padding(22.dp)
                .clickable(enabled = false, onClick = {}),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Ajustes", color = palette.onGlass, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            SettingsRow("Flash",             flashOn,   palette) { onAnyAction(); onToggleFlash() }
            SettingsRow("HDR",               hdrOn,     palette) { onAnyAction(); onToggleHdr() }
            SettingsRow("Cuadrícula 3×3",    gridOn,    palette) { onAnyAction(); onToggleGrid() }
            SettingsRow("Sonido obturador",  soundOn,   palette) { onAnyAction(); onToggleSound() }
            SettingsRow("Vibración háptica", hapticsOn, palette) { onAnyAction(); onToggleHaptics() }

            // Temporizador
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Temporizador", color = palette.onGlass, fontSize = 15.sp,
                    modifier = Modifier.weight(1f))
                PillButton(
                    text = when (timerSec) { 0 -> "Off"; 3 -> "3s"; 10 -> "10s"; else -> "Off" },
                    palette = palette,
                    onClick = { onAnyAction(); onCycleTimer() }
                )
            }

            // Tema
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Tema", color = palette.onGlass, fontSize = 15.sp, modifier = Modifier.weight(1f))
                PillButton(
                    text = when (darkPref) { true -> "Oscuro"; false -> "Claro"; null -> "Sistema" },
                    palette = palette,
                    onClick = { onAnyAction(); onCycleTheme() }
                )
            }

            // Relación de aspecto manual
            Column {
                Text("Relación de aspecto", color = palette.onGlass, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val opts = listOf<Pair<String, PreviewAspect?>>(
                        "Auto" to null,
                        "3:4"  to PreviewAspect.RATIO_3_4,
                        "9:16" to PreviewAspect.RATIO_9_16,
                        "1:1"  to PreviewAspect.RATIO_1_1,
                        "Full" to PreviewAspect.RATIO_FULL
                    )
                    opts.forEach { (label, value) ->
                        val selected = manualAspect == value
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (selected) LensAccent else Color.Transparent)
                                .border(
                                    0.6.dp,
                                    if (selected) Color.Transparent else palette.borderSoft,
                                    RoundedCornerShape(18.dp)
                                )
                                .clickable { onAnyAction(); onAspectChange(value) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                label,
                                color = if (selected) Color.Black else palette.onGlass,
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(
                    containerColor = LensAccent, contentColor = Color.Black
                ),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Cerrar", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    checked: Boolean,
    palette: GlassPalette,
    onCheckedChange: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = palette.onGlass, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = { onCheckedChange() },
            colors = SwitchDefaults.colors(
                checkedTrackColor   = LensAccent,
                checkedThumbColor   = Color.Black,
                uncheckedTrackColor = palette.onGlassSecondary.copy(alpha = 0.25f),
                uncheckedThumbColor = palette.onGlass
            )
        )
    }
}

/* ================================================================
 *                          HELPERS
 * ================================================================ */

private fun formatElapsed(s: Long): String {
    val m = s / 60; val ss = s % 60
    return "%02d:%02d".format(m, ss)
}

private fun CoroutineScope.launchSafe(block: suspend () -> Unit) {
    launch {
        try { block() } catch (e: Exception) { e.printStackTrace() }
    }
}
