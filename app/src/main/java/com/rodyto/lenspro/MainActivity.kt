package com.rodyto.lenspro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RenderEffect
import android.graphics.Shader
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
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
 *  RODYTO LENS PRO  —  Glassmorphism iOS / Dark+Light + Hápticos
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
                    textAlign = TextAlign.Center,
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
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Botón ajustes (izquierda)
            GlassCircleButton(
                onClick = {
                    settingsOpen = true
                    settingsIconRotation += 180f
                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                },
                palette = palette,
                size = 44.dp
            ) {
                Text("⚙", color = palette.onGlass, fontSize = 22.sp,
                    modifier = Modifier.rotate(settingsRotationAnim))
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
                        .background(palette.bgStrong)
                        .border(0.5.dp, palette.borderSoft, RoundedCornerShape(20.dp))
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

            // Indicador Res/FPS (esquina superior derecha) – sólo modo VIDEO
            AnimatedVisibility(
                visible = mode == "VIDEO" && !isFront && !isRecording,
                enter = fadeIn() + slideInHorizontally { it },
                exit  = fadeOut() + slideOutHorizontally { it }
            ) {
                Column(horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassChip(
                        text = videoRes.label,
                        selected = true,
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
                    GlassChip(
                        text = "${videoFps.label} fps",
                        selected = true,
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

            // Cuando no estamos en modo VIDEO mostramos un placeholder vacío para mantener el SpaceBetween
            if (!(mode == "VIDEO" && !isFront && !isRecording) && !isRecording) {
                Spacer(Modifier.size(44.dp))
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
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Lentes – reducidos a la mitad de tamaño
            Row(
                Modifier.padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LensBubble(
                    text = ".5",
                    selected = lens == "0.5x",
                    palette = palette,
                    onClick = {
                        Haptics.perform(view, Haptics.Kind.SELECT, hapticsOn)
                        vm.switchLens(context, "0.5x")
                    }
                )
                LensBubble(
                    text = "1×",
                    selected = lens == "1x",
                    palette = palette,
                    onClick = {
                        Haptics.perform(view, Haptics.Kind.SELECT, hapticsOn)
                        vm.switchLens(context, "1x")
                    }
                )
                LensBubble(
                    text = "3×",
                    selected = lens == "3x",
                    palette = palette,
                    onClick = {
                        Haptics.perform(view, Haptics.Kind.SELECT, hapticsOn)
                        vm.switchLens(context, "3x")
                    }
                )
            }

            // Botón shutter grande
            ShutterButton(
                isRecording = isRecording,
                onClick = {
                    Haptics.perform(view,
                        if (isRecording) Haptics.Kind.WARN else Haptics.Kind.SUCCESS,
                        hapticsOn)
                    coroutineScope.launchSafe { doShutter() }
                }
            )

            // Fila inferior — Galería (izq) / Modo / Flip (der)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, start = 24.dp, end = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // [LAYOUT FIX] Círculo de galería → IZQUIERDA
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    lastUri?.let { uri ->
                        AsyncImage(
                            model = uri,
                            contentDescription = "Última captura",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .border(1.dp, palette.border, CircleShape)
                                .clickable {
                                    Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                                    runCatching {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, "image/*")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(intent)
                                    }
                                },
                            contentScale = ContentScale.Crop
                        )
                    } ?: GlassCircleButton(
                        onClick = { /* sin foto aún */ },
                        palette = palette,
                        size = 44.dp
                    ) {
                        Text("🖼", color = palette.onGlass, fontSize = 18.sp)
                    }
                }

                ModeToggle(
                    mode = mode,
                    palette = palette,
                    onModeChange = {
                        Haptics.perform(view, Haptics.Kind.SELECT, hapticsOn)
                        vm.setCameraMode(it)
                    }
                )

                // [LAYOUT FIX] Botón Flip → DERECHA
                GlassCircleButton(
                    onClick = {
                        Haptics.perform(view, Haptics.Kind.TAP, hapticsOn)
                        vm.toggleFrontCamera(context)
                    },
                    palette = palette,
                    size = 48.dp
                ) {
                    Text("⟳", color = palette.onGlass, fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // -------- Panel de ajustes --------
        AnimatedVisibility(
            visible = settingsOpen,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(220))
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
private fun ShutterButton(isRecording: Boolean, onClick: () -> Unit) {
    val innerSize by animateDpAsState(
        targetValue = if (isRecording) 28.dp else 60.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "shutter_size"
    )
    val innerShape = if (isRecording) RoundedCornerShape(8.dp) else CircleShape

    Box(
        modifier = Modifier
            .size(82.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(bounded = false, radius = 48.dp),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(82.dp)
                .border(3.5.dp, if (isRecording) LensRecRed else LensAccent, CircleShape)
        )
        Box(
            Modifier
                .size(innerSize)
                .clip(innerShape)
                .background(if (isRecording) LensRecRed else LensAccent)
        )
    }
}

@Composable
fun LensBubble(
    text: String,
    selected: Boolean,
    palette: GlassPalette,
    onClick: () -> Unit
) {
    // Tamaño reducido a la mitad (≈29-30dp vs anteriores 58dp)
    val baseSize = 30.dp
    val animSize by animateDpAsState(
        targetValue = if (selected) 34.dp else baseSize,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "lens_size"
    )
    val bg = if (selected) LensAccent else palette.bg
    val fg = if (selected) Color.Black else palette.onGlass
    Box(
        modifier = Modifier
            .size(animSize)
            .clip(CircleShape)
            .background(bg)
            .border(0.6.dp, palette.borderSoft, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
fun GlassCircleButton(
    onClick: () -> Unit,
    palette: GlassPalette,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .glassBlur()
            .background(palette.bg)
            .border(1.dp, palette.border, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun ModeToggle(mode: String, palette: GlassPalette, onModeChange: (String) -> Unit) {
    val isPhoto = mode == "FOTO"
    Row(
        Modifier
            .clip(RoundedCornerShape(30.dp))
            .glassBlur()
            .background(palette.bgStrong)
            .border(0.6.dp, palette.borderSoft, RoundedCornerShape(30.dp))
            .padding(4.dp)
    ) {
        ModeChip("FOTO",  isPhoto,  palette) { onModeChange("FOTO") }
        ModeChip("VIDEO", !isPhoto, palette) { onModeChange("VIDEO") }
    }
}

@Composable
fun ModeChip(text: String, selected: Boolean, palette: GlassPalette, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) LensAccent else Color.Transparent, label = "seg_bg"
    )
    val fg by animateColorAsState(
        if (selected) Color.Black else palette.onGlass, label = "seg_fg"
    )
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) { Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
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
 * Slider de exposición — fluido y de mayor rango.
 *  - Drag continuo (Float) → cuantizado al rango entero soportado por la cámara.
 *  - Step refinado (≈ 1 unidad cada 8 px) en lugar de los 12 px anteriores.
 *  - Animación suave del marker.
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
    // Acumulador continuo para gestos fluidos
    var accumulator by remember { mutableStateOf(0f) }

    Column(
        modifier = modifier
            .height(190.dp)
            .width(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .glassBlur()
            .background(palette.bg)
            .border(0.6.dp, palette.borderSoft, RoundedCornerShape(20.dp))
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
        Text("☀", color = LensAccent, fontSize = 18.sp)
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

@Composable
fun GlassChip(text: String, selected: Boolean, palette: GlassPalette, onClick: () -> Unit) {
    val bg = if (selected) LensAccent.copy(alpha = 0.92f) else palette.bg
    val fg = if (selected) Color.Black else palette.onGlass
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .glassBlur()
            .background(bg)
            .border(0.6.dp, palette.borderSoft, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                .clip(RoundedCornerShape(24.dp))
                .glassBlur(strong = true)
                .background(palette.bgStrong)
                .border(0.6.dp, palette.border, RoundedCornerShape(24.dp))
                .padding(22.dp)
                .clickable(enabled = false, onClick = {}),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Ajustes", color = palette.onGlass, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            SettingsRow("Flash",            flashOn,    palette) { onAnyAction(); onToggleFlash() }
            SettingsRow("HDR",              hdrOn,      palette) { onAnyAction(); onToggleHdr() }
            SettingsRow("Cuadrícula 3×3",   gridOn,     palette) { onAnyAction(); onToggleGrid() }
            SettingsRow("Sonido obturador", soundOn,    palette) { onAnyAction(); onToggleSound() }
            SettingsRow("Vibración háptica",hapticsOn,  palette) { onAnyAction(); onToggleHaptics() }

            // Temporizador
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Temporizador", color = palette.onGlass, fontSize = 15.sp,
                    modifier = Modifier.weight(1f))
                GlassChip(
                    text = when (timerSec) { 0 -> "Off"; 3 -> "3s"; 10 -> "10s"; else -> "Off" },
                    selected = timerSec > 0,
                    palette = palette,
                    onClick = { onAnyAction(); onCycleTimer() }
                )
            }

            // Tema
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Tema", color = palette.onGlass, fontSize = 15.sp, modifier = Modifier.weight(1f))
                GlassChip(
                    text = when (darkPref) { true -> "Oscuro"; false -> "Claro"; null -> "Sistema" },
                    selected = true,
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
                        GlassChip(
                            text = label,
                            selected = manualAspect == value,
                            palette = palette,
                            onClick = { onAnyAction(); onAspectChange(value) }
                        )
                    }
                }
            }

            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = LensAccent, contentColor = Color.Black),
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

/**
 * Modifier que aplica un RenderEffect Blur real cuando se ejecuta en Android 12+,
 * imitando el efecto Glassmorphism iOS. En APIs inferiores se omite (queda sólo
 * el color translúcido + borde, que sigue dando aspecto "vidrio").
 */
private fun Modifier.glassBlur(strong: Boolean = false): Modifier = this.then(
    Modifier.graphicsLayer {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val r = if (strong) 28f else 18f
            renderEffect = RenderEffect
                .createBlurEffect(r, r, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
    }
)
