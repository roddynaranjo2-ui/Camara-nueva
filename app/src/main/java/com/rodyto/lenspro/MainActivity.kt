package com.rodyto.lenspro

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
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
import androidx.compose.runtime.collectAsState // Importación explícita para evitar errores de resolución
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val cameraViewModel: CameraControlViewModel = viewModel()
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                CameraPermissionWrapper(cameraViewModel)
            }
        }
    }
}

@Composable
fun CameraPermissionWrapper(viewModel: CameraControlViewModel) {
    val context = LocalContext.current
    val requiredPermissions = remember {
        mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    var hasPermissions by remember {
        mutableStateOf(requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            launcher.launch(requiredPermissions.toTypedArray())
        }
    }

    if (hasPermissions) {
        CameraScreen(viewModel)
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Se necesitan permisos para usar la app.",
                    color = Color.White,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
                Button(onClick = { launcher.launch(requiredPermissions.toTypedArray()) }) {
                    Text("Conceder permisos")
                }
            }
        }
    }
}

@Composable
fun CameraScreen(viewModel: CameraControlViewModel) {
    val lens by viewModel.currentLens.collectAsState()
    val mode by viewModel.cameraMode.collectAsState()
    val isFront by viewModel.isFrontCamera.collectAsState()
    val isFocusLocked by viewModel.focusLocked.collectAsState()
    val view = LocalView.current
    val density = LocalDensity.current

    var focusPoint by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            focusPoint = offset
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        },
                        onLongPress = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            viewModel.toggleFocusLock()
                        }
                    )
                }
        )

        focusPoint?.let {
            val xDp = with(density) { it.x.toDp() }
            val yDp = with(density) { it.y.toDp() }

            Box(
                modifier = Modifier
                    .offset(x = xDp - 30.dp, y = yDp - 30.dp)
                    .size(60.dp)
                    .border(2.dp, Color.Yellow)
            )
        }

        if (isFocusLocked) {
            Text(
                text = "AE/AF LOCK",
                color = Color(0xFFFFD700),
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        if (!isFront) {
            LensSelector(
                currentLens = lens,
                onLensSelect = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.switchLens(view.context, it)
                },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                listOf("FOTO", "VIDEO").forEach { m ->
                    Text(
                        text = m,
                        color = if (mode == m) Color(0xFFFFD700) else Color.White,
                        fontWeight = if (mode == m) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            viewModel.setCameraMode(m)
                        }
                    )
                }
            }
            BottomControls(view = view, viewModel = viewModel, mode = mode)
        }
    }
}

@Composable
fun BottomControls(view: View, viewModel: CameraControlViewModel, mode: String) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.85f else 1f, label = "scale")
    val buttonColor by animateColorAsState(targetValue = if (mode == "VIDEO") Color.Red else Color.White, label = "color")

    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }

    DisposableEffect(Unit) {
        onDispose { sound.release() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(40.dp))
            .background(Color(0x66000000))
            .border(0.5.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(40.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(Color.DarkGray)
                .border(1.dp, Color.White, CircleShape)
        )

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

        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.2f))
                .clickable {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.toggleFrontCamera(view.context)
                },
            contentAlignment = Alignment.Center
        ) {
            Text(text = "↺", color = Color.White, fontSize = 24.sp)
        }
    }
}

@Composable
fun LensSelector(currentLens: String, onLensSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(40.dp))
            .background(Color(0x66000000))
            .border(0.5.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(40.dp))
            .padding(4.dp)
    ) {
        listOf("0.5x", "1x", "3x").forEach { lens ->
            val isSelected = currentLens == lens
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable { onLensSelect(lens) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = lens,
                    color = if (isSelected) Color(0xFFFFD700) else Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
