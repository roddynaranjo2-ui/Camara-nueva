package com.rodyto.lenspro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.MediaActionSound
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/**
 * [FIX #6] Reescrito completamente. El cálculo previo con
 * launcher.contract.parseResult(0, null) NUNCA devolvía permisos reales y la
 * pantalla nunca se recomponía. Ahora usamos un State recordado que se actualiza
 * tanto al iniciar (ContextCompat) como cuando el launcher devuelve resultados.
 */
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

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(required.toTypedArray())
    }

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
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
                ) { Text("Conceder permisos", fontWeight = FontWeight.Bold) }
            }
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

            val range = vm.getExposureRange()
            if (range != null) {
                ExposureSlider(
                    value = exposure,
                    min = range.lower,
                    max = range.upper,
                    onValueChange = { vm.setExposure(it) },
                    modifier = Modifier.offset(x = xDp + 50.dp, y = yDp - 80.dp)
                )
            }
        }

        // -------- Badge de grabación arriba [FIX #8] --------
        AnimatedVisibility(
            visible = isRecording,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
            enter = fadeIn() + slideInVertically { -it },
            exit  = fadeOut() + slideOutVertically { -it }
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(GlassBgStrong)
                    .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(RecRed))
                Spacer(Modifier.size(8.dp))
                Text(
                    formatElapsed(recordingSeconds),
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }

        // -------- Countdown overlay [FIX #9] --------
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
            // Selector resolución/FPS solo en modo VIDEO (no grabando) y trasera
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
                            val nextFps = when (videoFps) {
                                VideoFps.FPS30 -> VideoFps.FPS60
                                VideoFps.FPS60 -> VideoFps.FPS30
                            }
                            vm.setVideoFps(nextFps)
                        }
                    )
                }
            }

            // [FIX #2 + #3] Lentes — identificadores unificados a "0.5x", "1x", "3x"
            // y se pasa el context que requiere switchLens()
            Row(
                Modifier.padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LensButton("0.5", lens == "0.5x", onClick = { vm.switchLens(context, "0.5x") })
                LensButton("1x",  lens == "1x",   onClick = { vm.switchLens(context, "1x") })
                LensButton("3x",  lens == "3x",   onClick = { vm.switchLens(context, "3x") })
            }

            // Botón shutter grande
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clickable {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        coroutineScope.launchSafe { doShutter() }
                    }
            ) {
                Box(
                    Modifier
                        .size(78.dp)
                        .border(3.dp, if (isRecording) RecRed else Accent, CircleShape)
                )
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(if (isRecording) 32.dp else 58.dp)
                        .clip(if (isRecording) RoundedCornerShape(6.dp) else CircleShape)
                        .background(if (isRecording) RecRed else Accent)
                )
            }

            // Barra inferior de modo y flip
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // [FIX #1 + #7] Llama a toggleFrontCamera(context) (no toggleCamera())
                // y muestra siempre el ícono representativo de flip
                GlassCircleButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        vm.toggleFrontCamera(context)
                    },
                    modifier = Modifier.padding(start = 24.dp)
                ) {
                    Text("🔄", fontSize = 22.sp)
                }

                // Selector Foto / Video
                ModeToggle(
                    mode = mode,
                    onModeChange = { vm.setCameraMode(it) }
                )

                // [FIX #10] Thumbnail con apertura real de galería
                lastUri?.let { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = "Última foto",
                        modifier = Modifier
                            .padding(end = 24.dp)
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
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
                } ?: Box(Modifier.padding(end = 24.dp).size(48.dp))
            }
        }

        // [FIX #5] Panel de ajustes — implementado completamente
        AnimatedVisibility(
            visible = settingsOpen,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(220))
        ) {
            SettingsPanel(
                flashOn = flashOn, onToggleFlash = { vm.toggleFlash() },
                hdrOn = hdrOn, onToggleHdr = { vm.toggleHdr() },
                gridOn = gridOn, onToggleGrid = { vm.toggleGrid() },
                soundOn = soundOn, onToggleSound = { vm.toggleShutterSound() },
                timerSec = timerSec, onCycleTimer = { vm.cycleTimer() },
                onClose = { settingsOpen = false }
            )
        }
    }
}

// ============================================================
//   COMPOSABLES AUXILIARES
// ============================================================

@Composable
fun LensButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(if (selected) Accent else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (selected) Color.Black else Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GlassCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(GlassBg)
            .border(1.dp, GlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun ModeToggle(mode: String, onModeChange: (String) -> Unit) {
    val isPhoto = mode == "FOTO"
    Row(
        Modifier
            .clip(RoundedCornerShape(30.dp))
            .background(GlassBgStrong)
            .padding(4.dp)
    ) {
        ModeChip("FOTO", isPhoto) { onModeChange("FOTO") }
        ModeChip("VIDEO", !isPhoto) { onModeChange("VIDEO") }
    }
}

@Composable
fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
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

/**
 * [FIX #4-A] Cuadrícula 3x3 estilo iOS / Pro mode.
 * Antes: composable referenciado pero no declarado.
 */
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
 * [FIX #4-B] Slider vertical de exposición tipo iOS.
 * Drag vertical sobre el sol → ajusta compensación AE entre min..max.
 */
@Composable
fun ExposureSlider(
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val range = (max - min).coerceAtLeast(1).toFloat()
    Column(
        modifier = modifier
            .height(160.dp)
            .width(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(GlassBg)
            .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    val step = -dragAmount / 12f
                    val newVal = (value + step.toInt()).coerceIn(min, max)
                    if (newVal != value) onValueChange(newVal)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("☀", color = Accent, fontSize = 18.sp)
        Spacer(Modifier.size(6.dp))
        Text(
            if (value > 0) "+$value" else "$value",
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.size(6.dp))
        Box(
            Modifier
                .width(4.dp)
                .height(80.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.15f))
        ) {
            val pct = ((value - min) / range).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(pct)
                    .align(Alignment.BottomStart)
                    .background(Accent)
            )
        }
    }
}

/**
 * [FIX #4-C] Chip de cristal para resolución / FPS / timer.
 */
@Composable
fun GlassChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Accent.copy(alpha = 0.9f) else GlassBg
    val fg = if (selected) Color.Black else Color.White
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .border(0.5.dp, GlassBorderSoft, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * [FIX #5] Panel completo de ajustes — conectado con todos los métodos del ViewModel
 * que antes eran código muerto (toggleFlash, toggleHdr, toggleGrid, cycleTimer,
 * toggleShutterSound).
 */
@Composable
fun SettingsPanel(
    flashOn: Boolean, onToggleFlash: () -> Unit,
    hdrOn: Boolean, onToggleHdr: () -> Unit,
    gridOn: Boolean, onToggleGrid: () -> Unit,
    soundOn: Boolean, onToggleSound: () -> Unit,
    timerSec: Int, onCycleTimer: () -> Unit,
    onClose: () -> Unit
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
                .background(GlassBgStrong)
                .border(0.5.dp, GlassBorder, RoundedCornerShape(24.dp))
                .padding(20.dp)
                .clickable(enabled = false, onClick = {}),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Ajustes", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            SettingsRow("Flash", flashOn, onToggleFlash)
            SettingsRow("HDR", hdrOn, onToggleHdr)
            SettingsRow("Cuadrícula 3×3", gridOn, onToggleGrid)
            SettingsRow("Sonido obturador", soundOn, onToggleSound)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Temporizador", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                GlassChip(
                    text = when (timerSec) { 0 -> "Off"; 3 -> "3s"; 10 -> "10s"; else -> "Off" },
                    selected = timerSec > 0,
                    onClick = onCycleTimer
                )
            }

            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Cerrar", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun SettingsRow(label: String, checked: Boolean, onCheckedChange: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = { onCheckedChange() },
            colors = SwitchDefaults.colors(
                checkedTrackColor = Accent,
                checkedThumbColor = Color.Black,
                uncheckedTrackColor = Color.White.copy(alpha = 0.18f),
                uncheckedThumbColor = Color.White
            )
        )
    }
}

// --------------------- HELPERS ---------------------
private fun formatElapsed(s: Long): String {
    val m = s / 60; val ss = s % 60
    return "%02d:%02d".format(m, ss)
}

/** Helper seguro para lanzar corutinas desde Compose */
private fun CoroutineScope.launchSafe(block: suspend () -> Unit) {
    launch {
        try { block() } catch (e: Exception) { e.printStackTrace() }
    }
}

// --------------------- MODIFIERS DE VIDRIO ---------------------
@Suppress("unused")
private fun Modifier.glassSurface(): Modifier = this.then(
    Modifier.background(GlassBg).graphicsLayer {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            renderEffect = RenderEffect
                .createBlurEffect(18f, 18f, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
    }
)

@Suppress("unused")
private fun Modifier.glassSurfaceStrong(): Modifier = this.then(
    Modifier.background(GlassBgStrong).graphicsLayer {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            renderEffect = RenderEffect
                .createBlurEffect(28f, 28f, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
    }
)

@Suppress("unused")
fun Modifier.glassPill(shape: RoundedCornerShape = RoundedCornerShape(36.dp)): Modifier = this
    .clip(shape)
    .background(GlassBg)
    .border(0.5.dp, GlassBorderSoft, shape)
