package com.rodyto.lenspro

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val cameraViewModel: CameraControlViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Aquí usamos el tema oscuro por defecto para que resalte el efecto vidrio
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                CameraScreen(cameraViewModel)
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
        
        // 1. Espacio para el Preview
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
            Text("AF LOCKED", color = Color.Yellow, modifier = Modifier.align(Alignment.Center))
        }

        // 3. Selector de Lentes (Lado derecho)
        LensSelector(
            currentLens = lens,
            onLensSelect = { 
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                viewModel.switchLens(it) 
            },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)
        )

        // 4. Botonera Inferior con Efecto Vidrio (Glassmorphism)
        BottomControls(view, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun BottomControls(view: View, modifier: Modifier = Modifier) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isPressed) 0.9f else 1f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
            .glassmorphismEffect()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Preview última foto (Circulito a la izquierda)
        Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(Color.Gray))
        
        // Botón Obturador (Shutter)
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
                            // tryAwaitRelease() // Eliminado porque no existe en este contexto
                            isPressed = false
                        }
                    )
                }
        )

        // Botón cambio de cámara (Frontal/Trasera)
        Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(Color.DarkGray))
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

// El Modificador Maestro para el efecto Burbuja/Vidrio
fun Modifier.glassmorphismEffect(): Modifier = this
    .clip(RoundedCornerShape(32.dp))
    .background(Color.White.copy(alpha = 0.15f))
    .blur(radius = 16.dp)
    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(32.dp))
