package com.rodyto.lenspro

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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

// Constantes de color para el diseño
private val GoldColor = Color(0xFFFFD700)
private val GlassBackground = Color.Black.copy(alpha = 0.45f)
private val GlassBorder = Color.White.copy(alpha = 0.18f)

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
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    var hasPermissions by remember {
        mutableStateOf(
            requiredPermissions.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = requiredPermissions.all { permission ->
            permissions[permission] == true ||
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) launcher.launch(requiredPermissions.toTypedArray())
    }

    if (hasPermissions) {
        CameraScreen(viewModel)
    } else {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "LensPro necesita acceso a la cámara y al micrófono para funcionar.",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp)
                )
                Button(
                    onClick = { launcher.launch(requiredPermissions.toTypedArray()) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GoldColor,
                        contentColor = Color.Black
                    )
                ) {
                    Text("Conceder permisos", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun CameraScreen(viewModel: CameraControlViewModel) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lens by viewModel.currentLens.collectAsStateWithLifecycle()
    val mode by viewModel.cameraMode.collectAsStateWithLifecycle()
    val isFront by viewModel.isFrontCamera.collectAsStateWithLifecycle()
    val isFocusLocked by viewModel.focusLocked.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val flashEnabled by viewModel.flashEnabled.collectAsStateWithLifecycle()
    val exposureLevel by viewModel.exposureLevel.collectAsStateWithLifecycle()
    val lastPhotoUri by viewModel.lastPhotoUri.collectAsStateWithLifecycle()
    val view = LocalView.current

    // Instancias persistentes que no se recrean en cada recomposición
    val storage = remember { MediaStorageManager() }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(Unit) { onDispose { sound.release() } }

    // Corrección del error de tipo: Offset? con tipo explícito
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    // Auto-dismiss del indicador de foco después de 2 segundos
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(2000L)
            focusPoint = null
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val screenWidthPx = with(density) { maxWidth.toPx() }.toInt()
        val screenHeightPx = with(density) { maxHeight.toPx() }.toInt()

        // Preview de cámara
        CameraPreview(
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            focusPoint = offset
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            viewModel.tapToFocus(
                                x = offset.x,
                                y = offset.y,
                                screenWidth = screenWidthPx,
                                screenHeight = screenHeightPx
                            )
                        },
                        onLongPress = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            viewModel.toggleFocusLock()
                        }
                    )
                }
        )

        // Indicador de foco (cuadro amarillo o rojo en tap)
        focusPoint?.let { point ->
            val xDp = with(density) { point.x.toDp() }
            val yDp = with(density) { point.y.toDp() }
            val focusColor = if (isFocusLocked) Color.Red else GoldColor
            Box(
                modifier = Modifier
                    .offset(x = xDp - 28.dp, y = yDp - 28.dp)
                    .size(56.dp)
                    .border(2.dp, focusColor, RoundedCornerShape(6.dp))
            )
        }

        // Barra superior: Flash / AE-AF Lock / Cambio de cámara
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 48.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Botón de flash
            GlassCircleButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    viewModel.toggleFlash()
                },
                modifier = Modifier.size(44.dp)
            ) {
                Text(
                    text = "⚡",
                    color = if (flashEnabled && !isFront) GoldColor else Color.White.copy(alpha = 0.5f),
                    fontSize = 20.sp
                )
            }

            // Indicador AE/AF LOCK
            if (isFocusLocked) {
                Box(
                    modifier = Modifier
                        .background(GoldColor.copy(alpha = 0.92f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "AE/AF LOCK",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(88.dp))
            }

            // Botón frontal/trasera
            GlassCircleButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    viewModel.toggleFrontCamera(context)
                },
                modifier = Modifier.size(44.dp)
            ) {
                Text(text = "↺", color = Color.White, fontSize = 22.sp)
            }
        }

        // Control de exposición (lado derecho, columna de botones)
        val exposureRange = viewModel.getExposureRange()
        if (exposureRange != null && exposureRange.lower < exposureRange.upper) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
                    .glassCard(RoundedCornerShape(24.dp))
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("☀", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                GlassCircleButton(
                    onClick = { viewModel.setExposure(exposureLevel + 1) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Text("+", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Text(
                    text = if (exposureLevel >= 0) "+$exposureLevel" else "$exposureLevel",
                    color = GoldColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                GlassCircleButton(
                    onClick = { viewModel.setExposure(exposureLevel - 1) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Text("−", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Text("☁", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
            }
        }

        // Controles inferiores
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Selector de lente (solo cámara trasera)
            if (!isFront) {
                Row(
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .glassCard(RoundedCornerShape(20.dp))
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("0.5x", "1x", "2x").forEach { lensOption ->
                        LensButton(
                            text = lensOption,
                            isSelected = lens == lensOption,
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                viewModel.switchLens(context, lensOption)
                            }
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(52.dp))
            }

            // Selector de modo FOTO / VIDEO
            Row(
                modifier = Modifier.padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp)
            ) {
                listOf("FOTO", "VIDEO").forEach { m ->
                    Text(
                        text = m,
                        color = if (mode == m) GoldColor else Color.White.copy(alpha = 0.6f),
                        fontWeight = if (mode == m) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable(enabled = !isRecording) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            viewModel.setCameraMode(m)
                        }
                    )
                }
            }

            // Fila principal: thumbnail | obturador | espacio
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .glassCard(RoundedCornerShape(36.dp))
                    .padding(horizontal = 28.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Miniatura de la última foto (real con Coil)
                ThumbnailButton(uri = lastPhotoUri)

                // Botón obturador / grabar
                CaptureButton(
                    mode = mode,
                    isRecording = isRecording,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        when {
                            mode == "FOTO" -> {
                                sound.play(MediaActionSound.SHUTTER_CLICK)
                                viewModel.takePicture(storage, context)
                            }
                            mode == "VIDEO" && !isRecording -> {
                                viewModel.startVideoRecording(context, storage)
                            }
                            mode == "VIDEO" && isRecording -> {
                                viewModel.stopVideoRecording(context, storage)
                            }
                        }
                    }
                )

                // Espacio simétrico al thumbnail
                Box(modifier = Modifier.size(56.dp))
            }
        }
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
            .background(GlassBackground)
            .border(1.dp, GlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
fun LensButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) GoldColor else Color.Transparent,
        label = "lens_bg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) Color.Black else Color.White,
        label = "lens_text"
    )
    Box(
        modifier = Modifier
            .size(width = 54.dp, height = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ThumbnailButton(uri: Uri?) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.DarkGray)
            .border(1.5.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = "Última foto capturada",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text("📷", fontSize = 22.sp)
        }
    }
}

@Composable
fun CaptureButton(mode: String, isRecording: Boolean, onClick: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        label = "shutter_scale",
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
    )
    val outerColor by animateColorAsState(
        targetValue = if (isRecording) Color.Red else Color.White,
        label = "outer_color"
    )

    Box(
        modifier = Modifier
            .size(80.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(outerColor)
            .border(3.5.dp, Color.White.copy(alpha = 0.4f), CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                        onClick()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            mode == "VIDEO" && isRecording -> {
                // Icono de detener (cuadrado blanco)
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(Color.White, RoundedCornerShape(4.dp))
                )
            }
            mode == "VIDEO" -> {
                // Icono de grabar (punto rojo)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color.Red, CircleShape)
                )
            }
            else -> {
                // Obturador fotográfico (círculo interno negro)
                Box(
                    modifier = Modifier
                        .size(66.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                )
            }
        }
    }
}

fun Modifier.glassCard(shape: RoundedCornerShape = RoundedCornerShape(36.dp)): Modifier = this
    .clip(shape)
    .background(GlassBackground)
    .border(0.5.dp, GlassBorder, shape)