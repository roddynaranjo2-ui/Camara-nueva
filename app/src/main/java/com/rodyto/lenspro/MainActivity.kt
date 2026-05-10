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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val cameraViewModel: CameraControlViewModel = viewModel()
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    CameraPermissionWrapper(cameraViewModel)
                }
            }
        }
    }
}

@Composable
fun CameraPermissionWrapper(viewModel: CameraControlViewModel) {
    val context = LocalContext.current
    val requiredPermissions = remember {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            list.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        list
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
                    text = "LensPro necesita permisos para funcionar.",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp)
                )
                Button(
                    onClick = { launcher.launch(requiredPermissions.toTypedArray()) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700), contentColor = Color.Black)
                ) {
                    Text("Conceder permisos")
                }
            }
        }
    }
}

@Composable
fun CameraScreen(viewModel: CameraControlViewModel) {
    val lens by viewModel.currentLens.collectAsStateWithLifecycle()
    val mode by viewModel.cameraMode.collectAsStateWithLifecycle()
    val isFront by viewModel.isFrontCamera.collectAsStateWithLifecycle()
    val isFocusLocked by viewModel.focusLocked.collectAsStateWithLifecycle()
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
                    .border(1.5.dp, Color.Yellow.copy(alpha = 0.8f))
            )
            
            // Auto-hide focus point after 2 seconds
            LaunchedEffect(it) {
                kotlinx.coroutines.delay(2000)
                focusPoint = null
            }
        }

        if (isFocusLocked) {
            Text(
                text = "AE/AF LOCK",
                color = Color(0xFFFFD700),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }

        if (!isFront) {
            LensSelector(
                currentLens = lens,
                onLensSelect = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.switchLens(view.context, it)
                },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp)
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                listOf("FOTO", "VIDEO").forEach { m ->
                    Text(
                        text = m,
                        color = if (mode == m) Color(0xFFFFD700) else Color.White.copy(alpha = 0.7f),
                        fontWeight = if (mode == m) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp,
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
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.88f else 1f, label = "scale")
    val buttonColor by animateColorAsState(targetValue = if (mode == "VIDEO") Color.Red else Color.White, label = "color")

    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }

    DisposableEffect(Unit) {
        onDispose { sound.release() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .height(100.dp)
            .clip(RoundedCornerShape(50.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50.dp))
            .padding(horizontal = 30.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail placeholder
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color.DarkGray.copy(alpha = 0.8f))
                .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
        )

        // Shutter Button
        Box(
            modifier = Modifier
                .size(80.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(buttonColor)
                .border(4.dp, Color.Gray.copy(alpha = 0.5f), CircleShape)
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

        // Flip Camera Button
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.15f))
                .clickable {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.toggleFrontCamera(view.context)
                },
            contentAlignment = Alignment.Center
        ) {
            Text(text = "↺", color = Color.White, fontSize = 28.sp)
        }
    }
}

@Composable
fun LensSelector(currentLens: String, onLensSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(30.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(30.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf("0.5x", "1x", "3x").forEach { lens ->
            val isSelected = currentLens == lens
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color.White.copy(alpha = 0.25f) else Color.Transparent)
                    .clickable { onLensSelect(lens) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = lens,
                    color = if (isSelected) Color(0xFFFFD700) else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}
