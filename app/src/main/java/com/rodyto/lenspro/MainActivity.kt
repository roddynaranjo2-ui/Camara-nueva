package com.rodyto.lenspro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
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
    val context = androidx.compose.ui.platform.LocalContext.current
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
        if (!hasPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasPermission) {
        CameraScreen(viewModel)
    } else {
        // Pantalla de aviso si el usuario deniega el permiso
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
                        onTap = { /* Lógica TAP_TO_FOCUS */ },
                        onLongPress = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            viewModel.toggleFocusLock()
                        }
                    )
                }
        )

        // 2. Indicador de AF_LOCK
        if (isFocusLocked) {
            Text(
                text = "AF LOCKED",
                color = Color.Yellow,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 3. Selector de Lentes
        LensSelector(
            currentLens = lens,
            onLensSelect = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                viewModel.switchLens(view.context, it)
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
        )

        // 4. Botonera Inferior
        BottomControls(
            view = view,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun BottomControls(view: View, modifier: Modifier = Modifier) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        label = "shutter_scale"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
            .glassmorphismEffect()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Preview última foto
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(Color.Gray)
        )

        // Botón Obturador
        Box(
            modifier = Modifier
                .size(72.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(Color.White)
                .border(4.dp, Color.LightGray, CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            tryAwaitRelease()
                            isPressed = false
                        }
                    )
                }
        )

        // Botón cambio de cámara
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(Color.DarkGray)
        )
    }
}

@Composable
fun LensSelector(currentLens: String, onLensSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.glassmorphismEffect().padding(8.dp)) {
        listOf("0.5x", "1x", "3x").forEach { lens ->
            val isSelected = currentLens == lens
            Text(
                text = lens,
                color = if (isSelected) Color.Yellow else Color.White,
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .clickable { onLensSelect(lens) }
            )
        }
    }
}

// Glassmorphism compatible con Android 8+ (sin .blur())
fun Modifier.glassmorphismEffect(): Modifier = this
    .clip(RoundedCornerShape(32.dp))
    .background(Color.White.copy(alpha = 0.18f))
    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(32.dp))