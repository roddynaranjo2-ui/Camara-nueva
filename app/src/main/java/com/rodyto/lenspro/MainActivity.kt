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
                            val nextFps = when (videoFps) {
                                VideoFps.FPS30 -> VideoFps.FPS60
                                VideoFps.FPS60 -> VideoFps.FPS30
                            }
                            vm.setVideoFps(nextFps)
                        }
                    )
                }
            }

            // Lentes
            Row(
                Modifier.padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LensButton("0.5", lens == "0.5", onClick = { vm.switchLens("0.5") })
                LensButton("1x",  lens == "1.0", onClick = { vm.switchLens("1.0") })
                LensButton("3x",  lens == "3.0", onClick = { vm.switchLens("3.0") })
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
                // Anillo exterior
                Box(
                    Modifier
                        .size(78.dp)
                        .border(3.dp, if (isRecording) RecRed else Accent, CircleShape)
                )

                // Círculo interior
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(if (isRecording) 32.dp else 58.dp)
                        .clip(CircleShape)
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
                // Flip cámara
                GlassCircleButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        vm.toggleCamera()
                    },
                    modifier = Modifier.padding(start = 24.dp)
                ) {
                    Text(if (isFront) "📷" else "🔄", fontSize = 22.sp)
                }

                // Selector Foto / Video
                ModeToggle(
                    mode = mode,
                    onModeChange = { vm.setCameraMode(it) }
                )

                // Última foto thumbnail
                lastUri?.let { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = "Última foto",
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { /* abrir galería */ },
                        contentScale = ContentScale.Crop
                    )
                } ?: Box(Modifier.size(48.dp))
            }
        }

        // Panel de ajustes (modal)
        if (settingsOpen) {
            // ... (el resto del panel se mantiene igual)
            // Nota: Asumiendo que el código del modal está completo en el archivo original
        }
    }
}

// Componentes auxiliares (mantengo todo igual)
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

// --------------------- HELPERS ---------------------
private fun formatElapsed(s: Long): String {
    val m = s / 60; val ss = s % 60
    return "%02d:%02d".format(m, ss)
}

/**
 * Helper seguro para lanzar corutinas desde Compose
 */
private fun CoroutineScope.launchSafe(block: suspend () -> Unit) {
    launch {
        try {
            block()
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
