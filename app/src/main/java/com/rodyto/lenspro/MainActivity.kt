package com.rodyto.lenspro

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val cameraViewModel: CameraControlViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                CameraPermissionWrapper(cameraViewModel)
            }
        }
    }
}

@Composable
fun CameraPermissionWrapper(viewModel: CameraControlViewModel) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    if (hasPermission) {
        CameraScreen(viewModel)
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Se necesita permiso de cámara para usar esta app.",
                    color = Color.White,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("Conceder permiso")
                }
            }
        }
    }
}

@Composable
fun CameraScreen(viewModel: CameraControlViewModel) {
    val lens by viewModel.currentLens.collectAsState()
    val mode by viewModel.cameraMode.collectAsState()
    val isFocusLocked by viewModel.focusLocked.collectAsState()
    val view = LocalView.current

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // 1. Preview de cámara
        CameraPreview(
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        },
                        onLongPress = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            viewModel.toggleFocusLock()
                        }
                    )
                }
        )

        // 2. Indicador AE/AF LOCK
        if (isFocusLocked) {
            Text(
                text = "AE/AF LOCK",
                color = Color(0xFFFFD700),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // 3. Selector de Lentes (lado derecho)
        LensSelector(
            currentLens = lens,
            onLensSelect = {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                viewModel.switchLens(view.context, it)
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
        )

        // 4. Controles inferiores
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Selector de Modo: FOTO / VIDEO / PRO
            Row(
                modifier = Modifier.padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                listOf("FOTO", "VIDEO", "PRO").forEach { m ->
                    Text(
                        text = m,
                        color = if (mode == m) Color(0xFFFFD700) else Color.White,
                        fontWeight = if (mode == m) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            viewModel.setCameraMode(m)
                        }
                    )
                }
            }

            BottomControls(
                view = view,
                viewModel = viewModel,
                mode = mode
            )
        }
    }
}

@Composable
fun BottomControls(view: View, viewModel: CameraControlViewModel, mode: String) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        label = "shutter_scale"
    )
    val buttonColor by animateColorAsState(
        targetValue = if (mode == "VIDEO") Color.Red else Color.White,
        label = "shutter_color"
    )

    val sound = remember {
        MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    }

    DisposableEffect(Unit) {
        onDispose { sound.release() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .glassmorphismEffect()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Miniatura última foto (simulada)
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(Color.DarkGray)
                .border(1.dp, Color.White, CircleShape)
        )

        // Botón Obturador
        Box(
            modifier = Modifier
                .size(76.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(buttonColor)
                .border(4.dp, Color.LightGray, CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

                            if (mode == "FOTO") {
                                sound.play(MediaActionSound.SHUTTER_CLICK)
                                viewModel.capturePhoto()
                            }

                            tryAwaitRelease()
                            isPressed = false
                        }
                    )
                }
        )

        // Botón switch cámara frontal/trasera
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "↺",
                color = Color.White,
                fontSize = 24.sp
            )
        }
    }
}

@Composable
fun LensSelector(currentLens: String, onLensSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.glassmorphismEffect().padding(4.dp)) {
        listOf("0.5x", "1x", "3x").forEach { lens ->
            val isSelected = currentLens == lens
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) Color.White.copy(alpha = 0.2f)
                        else Color.Transparent
                    )
                    .clickable { onLensSelect(lens) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = lens,
                    color = if (isSelected) Color(0xFFFFD700) else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// Glassmorphism real — funciona en Android 12+ (S21 FE con One UI 8 lo soporta perfectamente)
fun Modifier.glassmorphismEffect(): Modifier = this
    .clip(RoundedCornerShape(40.dp))
    .background(Color.Black.copy(alpha = 0.4f))
    .blur(radius = 24.dp)
    .border(0.5.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(40.dp))