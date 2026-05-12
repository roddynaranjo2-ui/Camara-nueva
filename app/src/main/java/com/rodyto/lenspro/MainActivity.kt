package com.rodyto.lenspro

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

// ====== Tokens visuales tipo iOS 18 / OneUI 8 ======
private val Accent          = Color(0xFFFFD60A)
private val AccentSoft      = Color(0xFFFFE066)
private val RecRed          = Color(0xFFFF3B30)
private val GlassBg         = Color.Black.copy(alpha = 0.32f)
private val GlassBgStrong   = Color.Black.copy(alpha = 0.55f)
private val GlassBorder     = Color.White.copy(alpha = 0.22f)
private val GlassBorderSoft = Color.White.copy(alpha = 0.10f)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: CameraControlViewModel = viewModel()
            MaterialTheme {
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
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    val required = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.VIBRATE)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    val perms = launcher.contract.parseResult(0, null) ?: emptyMap()
    val granted = required.all { p ->
        perms[p] == true ||
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(required.toTypedArray()) }

    if (granted) CameraScreen(viewModel)
    else Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "LensPro necesita acceso a la cámara y al micrófono.",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp)
            )
            Button(
                onClick = { launcher.launch(required.toTypedArray()) },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
            ) { Text("Conceder permisos", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun CameraScreen(vm: CameraControlViewModel) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current

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
    val videoRes      by vm.videoResolution.collectAsStateWithLifecycle()
    val videoFps      by vm.videoFps.collectAsStateWithLifecycle()

    val storage = remember { MediaStorageManager() }
    val sound   = remember {
        MediaActionSound().apply {
            load(MediaActionSound.SHUTTER_CLICK)
            load(MediaActionSound.FOCUS_COMPLETE)
            load(MediaActionSound.START_VIDEO_RECORDING)
            load(MediaActionSound.STOP_VIDEO_RECORDING)
        }
    }
    DisposableEffect(Unit) { onDispose { sound.release() } }

    // ----- UI state -----
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsIconRotation by remember { mutableStateOf(0f) }
    var countdown by remember { mutableStateOf(0) }
    var recordingSeconds by remember { mutableStateOf(0L) }

    // Auto-ocultar cuadro de enfoque y slider de exposición
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) { delay(2500L); focusPoint = null }
    }
    // Contador de grabación
    LaunchedEffect(isRecording) {
        recordingSeconds = 0
        while (isRecording) { delay(1000); recordingSeconds += 1 }
    }
    // Rotación animada del icono de ajustes
    val settingsRotationAnim by animateFloatAsState(
        targetValue = settingsIconRotation,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "settings_rot"
    )

    // Acción de disparo con timer
    suspend fun doShutter() {
        if (timerSec > 0 && mode == "FOTO") {
            for (s in timerSec downTo 1) { countdown = s; delay(1000) }
            countdown = 0
        }
        when {
            mode == "FOTO" -> {
                if (soundOn) sound.play(MediaActionSound.SHUTTER_CLICK)
                vm.takePicture(storage, context)
            }
            mode == "VIDEO" && !isRecording -> {
                if (soundOn) sound.play(MediaActionSound.START_VIDEO_RECORDING)
                vm.startVideoRecording(context, storage)
            }
            mode == "VIDEO" && isRecording -> {
                if (soundOn) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
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
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                if (soundOn) sound.play(MediaActionSound.FOCUS_COMPLETE)
                                vm.tapToFocus(offset.x, offset.y, screenW, screenH)
                            }
                        },
                        onLongPress = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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

        // -------- Cuadro de enfoque + slider exposición acoplado --------
        focusPoint?.let { pt ->
            val xDp = with(density) { pt.x.toDp() }
            val yDp = with(density) { pt.y.toDp() }
            val color = if (focusLocked) RecRed else Accent

            // Cuadro animado
            val scale = remember { Animatable(1.3f) }
            LaunchedEffect(Unit) {
                scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            }
            Box(
                modifier = Modifier
                    .offset(x = xDp - 36.dp, y = yDp - 36.dp)
                    .size(72.dp)
                    .scale(scale.value)
                    .border(1.5.dp, color, RoundedCornerShape(6.dp))
            )

            // Slider exposición vertical pegado al cuadro (estilo iOS)
            val range = vm.getExposureRange()
            if (range != null) {
                ExposureSlider(
                    value = exposure,
                    range = range.lower..range.upper,
                    onValueChange = { vm.setExposure(it) },
                    modifier = Modifier.offset(x = xDp + 50.dp, y = yDp - 80.dp)
                )
            }
        }

        // Botón de ajustes (engranaje) arriba izquierda
        GlassCircleButton(
            onClick = {
                settingsOpen = true
                settingsIconRotation += 180f
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .size(48.dp)
        ) {
            Text("⚙", color = Color.White, fontSize = 24.sp, modifier = Modifier.rotate(settingsRotationAnim))
        }

        // -------- Controles inferiores --------
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Selector de resolución/FPS solo en modo VIDEO (no grabando) y trasera
            AnimatedVisibility(
                visible = mode == "VIDEO" && !isFront && !isRecording,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit  = fadeOut() + slideOutVertically { it / 2 }
            ) {
                Row(
                    Modifier.padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassChip(
                        text = videoRes.label,
                        selected = true,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            vm.setVideoFps(if (videoFps == VideoFps.FPS30) VideoFps.FPS60 else VideoFps.FPS30)
                        }
                    )
                }
            }

            // Selector de lentes (solo trasera)
            if (!isFront) {
                Row(
                    Modifier
                        .padding(bottom = 14.dp)
                        .glassPill(RoundedCornerShape(22.dp))
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val lensOptions = listOf("0.5x" to "0.6", "1x" to "1", "3x" to "3")
                    lensOptions.forEach { (internal, display) ->
                        LensButton(
                            display = display,
                            isSelected = lens == internal,
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                vm.switchLens(context, internal)
                            }
                        )
                    }
                }
            } else Spacer(Modifier.height(46.dp))

            // FOTO / VIDEO
            Row(
                Modifier.padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(40.dp)
            ) {
                listOf("FOTO", "VIDEO").forEach { m ->
                    val sel = mode == m
                    Text(
                        text = m,
                        color = if (sel) Accent else Color.White.copy(alpha = 0.6f),
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable(enabled = !isRecording) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            vm.setCameraMode(m)
                        }
                    )
                }
            }

            // Barra inferior con thumbnail + capture + flip
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .glassPill(RoundedCornerShape(38.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThumbnailButton(uri = lastUri)

                CaptureButton(
                    mode = mode,
                    isRecording = isRecording,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        coroutineScope.launchSafe { doShutter() }
                    }
                )

                // Flip ahora abajo
                GlassCircleButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        vm.toggleFrontCamera(context)
                    },
                    modifier = Modifier.size(52.dp)
                ) {
                    val rot by animateFloatAsState(
                        targetValue = if (isFront) 180f else 0f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "flip_rot"
                    )
                    Text("↺", color = Color.White, fontSize = 24.sp, modifier = Modifier.rotate(rot))
                }
            }
        }

        // -------- Hoja de ajustes --------
        SettingsSheet(
            visible = settingsOpen,
            onDismiss = { settingsOpen = false; settingsIconRotation -= 180f },
            hdrOn = hdrOn, onHdrToggle = { vm.toggleHdr() },
            gridOn = gridOn, onGridToggle = { vm.toggleGrid() },
            soundOn = soundOn, onSoundToggle = { vm.toggleShutterSound() },
            timerSec = timerSec, onTimerCycle = { vm.cycleTimer() },
            videoRes = videoRes, onVideoRes = { vm.setVideoResolution(it) },
            videoFps = videoFps, onVideoFps = { vm.setVideoFps(it) }
        )
    }
}

// ============================ COMPONENTES ============================

@Composable
private fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val c = Color.White.copy(alpha = 0.28f)
        drawLine(c, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = 1.2f)
        drawLine(c, Offset(2 * w / 3f, 0f), Offset(2 * w / 3f, h), strokeWidth = 1.2f)
        drawLine(c, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = 1.2f)
        drawLine(c, Offset(0f, 2 * h / 3f), Offset(w, 2 * h / 3f), strokeWidth = 1.2f)
    }
}

@Composable
fun ExposureSlider(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    Column(
        modifier = modifier
            .glassPill(RoundedCornerShape(22.dp))
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("☀", color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp,
            modifier = Modifier.clickable {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onValueChange((value + 1).coerceAtMost(range.last))
            })

        // Pista vertical
        val total = (range.last - range.first).coerceAtLeast(1).toFloat()
        val frac = (value - range.first) / total          // 0..1
        Box(
            Modifier
                .height(110.dp).width(28.dp),
            contentAlignment = Alignment.Center
        ) {
            // pista
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(Color.White.copy(alpha = 0.30f), RoundedCornerShape(2.dp))
            )
            // thumb
            val thumbOffset = with(LocalDensity.current) {
                ((1f - frac) * 110f - 110f / 2f).dp
            }
            Box(
                Modifier
                    .offset(y = thumbOffset)
                    .size(16.dp)
                    .background(Accent, CircleShape)
                    .border(1.dp, Color.White, CircleShape)
                    .pointerInput(range) {
                        detectTapGestures(onPress = { })
                    }
            )
            // Etiqueta del valor a la derecha
            Text(
                if (value >= 0) "+$value" else "$value",
                color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.offset(x = 22.dp, y = thumbOffset - 6.dp)
            )
        }

        Text("☾", color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp,
            modifier = Modifier.clickable {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onValueChange((value - 1).coerceAtLeast(range.first))
            })
    }
}

@Composable
fun GlassCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .glassSurface()
            .border(1.dp, GlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
fun LensButton(display: String, isSelected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (isSelected) Accent else Color.Transparent, label = "lens_bg"
    )
    val fg by animateColorAsState(
        if (isSelected) Color.Black else Color.White, label = "lens_fg"
    )
    val scale by animateFloatAsState(
        if (isSelected) 1.05f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "lens_sc"
    )
    Box(
        modifier = Modifier
            .scale(scale)
            .size(width = 50.dp, height = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(if (isSelected) "${display}×" else display,
            color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GlassChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent,
        label = "chip_bg"
    )
    Box(
        Modifier
            .glassPill(RoundedCornerShape(18.dp))
            .background(bg, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ThumbnailButton(uri: Uri?) {
    Box(
        Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.DarkGray)
            .border(1.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (uri != null) AsyncImage(
            model = uri, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
        ) else Text("📷", fontSize = 22.sp)
    }
}

@Composable
fun CaptureButton(mode: String, isRecording: Boolean, onClick: () -> Unit) {
    val cur by rememberUpdatedState(onClick)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "shutter_sc"
    )
    val ring by animateColorAsState(
        if (isRecording) RecRed else Color.White, label = "shutter_ring"
    )
    Box(
        Modifier
            .size(82.dp)
            .scale(scale)
            .clip(CircleShape)
            .border(4.dp, ring, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    tryAwaitRelease(); pressed = false
                    cur()
                })
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            mode == "VIDEO" && isRecording -> Box(
                Modifier.size(30.dp).background(RecRed, RoundedCornerShape(7.dp))
            )
            mode == "VIDEO" -> Box(Modifier.size(60.dp).clip(CircleShape).background(RecRed))
            else -> Box(Modifier.size(64.dp).clip(CircleShape).background(Color.White))
        }
    }
}

// --------------------- HOJA DE AJUSTES ---------------------
@Composable
fun SettingsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    hdrOn: Boolean, onHdrToggle: () -> Unit,
    gridOn: Boolean, onGridToggle: () -> Unit,
    soundOn: Boolean, onSoundToggle: () -> Unit,
    timerSec: Int, onTimerCycle: () -> Unit,
    videoRes: VideoResolution, onVideoRes: (VideoResolution) -> Unit,
    videoFps: VideoFps, onVideoFps: (VideoFps) -> Unit
) {
    // Backdrop oscurecido
    AnimatedVisibility(
        visible = visible, enter = fadeIn(), exit = fadeOut()
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onDismiss)
        )
    }
    // Sheet desde abajo
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        ) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(260)) + fadeOut()
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .glassSurfaceStrong()
                    .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 38.dp, height = 4.dp)
                        .background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.height(14.dp))
                Text("Ajustes", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))

                SettingsToggleRow("HDR", hdrOn, onHdrToggle)
                SettingsToggleRow("Cuadrícula 3×3", gridOn, onGridToggle)
                SettingsToggleRow("Sonido de obturador", soundOn, onSoundToggle)

                SettingsClickableRow(
                    title = "Temporizador",
                    value = if (timerSec == 0) "Off" else "${timerSec}s",
                    onClick = onTimerCycle
                )

                Spacer(Modifier.height(8.dp))
                Text("Vídeo", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                // Resolución
                Text("Resolución", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                Row(
                    Modifier.padding(top = 6.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VideoResolution.values().forEach { r ->
                        SegmentChip(r.label, videoRes == r) { onVideoRes(r) }
                    }
                }
                // FPS
                Text("FPS", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                Row(
                    Modifier.padding(top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VideoFps.values().forEach { f ->
                        SegmentChip(f.label, videoFps == f) { onVideoFps(f) }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent, contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Cerrar", fontWeight = FontWeight.Bold) }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(title: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, color = Color.White, fontSize = 15.sp)
        Switch(
            checked = checked, onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedTrackColor = Accent,
                checkedThumbColor = Color.Black,
                uncheckedTrackColor = Color.White.copy(alpha = 0.2f),
                uncheckedThumbColor = Color.White
            )
        )
    }
}

@Composable
private fun SettingsClickableRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, color = Color.White, fontSize = 15.sp)
        Text(value, color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SegmentChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) Accent else Color.White.copy(alpha = 0.08f), label = "seg_bg"
    )
    val fg by animateColorAsState(
        if (selected) Color.Black else Color.White, label = "seg_fg"
    )
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

// --------------------- HELPERS ---------------------
private fun formatElapsed(s: Long): String {
    val m = s / 60; val ss = s % 60
    return "%02d:%02d".format(m, ss)
}

private fun kotlinx.coroutines.CoroutineScope.launchSafe(block: suspend () -> Unit) {
    kotlinx.coroutines.launch(start = kotlinx.coroutines.CoroutineStart.DEFAULT) {
        try { block() } catch (_: Throwable) {}
    }
}

// --------------------- MODIFIERS DE VIDRIO ---------------------
private fun Modifier.glassSurface(): Modifier = this.then(
    Modifier.background(GlassBg).graphicsLayer {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            renderEffect = RenderEffect
                .createBlurEffect(18f, 18f, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
    }
)

private fun Modifier.glassSurfaceStrong(): Modifier = this.then(
    Modifier.background(GlassBgStrong).graphicsLayer {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            renderEffect = RenderEffect
                .createBlurEffect(28f, 28f, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
    }
)

fun Modifier.glassPill(shape: RoundedCornerShape = RoundedCornerShape(36.dp)): Modifier = this
    .clip(shape)
    .background(GlassBg)
    .border(0.5.dp, GlassBorderSoft, shape)
