package com.rodyto.rodyto_lens_pro

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat

/**
 * Archivo 1: Actividad Principal y Estructura Base.
 * Maneja permisos y el contenedor principal de la UI.
 */
class MainActivity : ComponentActivity() {

    // Instanciamos el nuevo ViewModelOps que creamos antes
    private val viewModel: CameraControlViewModelOps by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Verificación de permisos original
        checkAndRequestPermissions()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RodytoLensApp(viewModel)
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        ActivityCompat.requestPermissions(this, permissions, 101)
    }
}

@Composable
fun RodytoLensApp(viewModel: CameraControlViewModelOps) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Capa de Vista Previa (Camera Preview)
        CameraPreviewLayer(viewModel)

        // 2. Capa de Controles Básicos (Desde Helpers)
        MainControlsLayer(viewModel)

        // 3. Capa de Overlays/Paneles (Desde Overlays)
        SettingsOverlayLayer(viewModel)
    }
}

@Composable
fun CameraPreviewLayer(viewModel: CameraControlViewModelOps) {
    // Aquí va tu implementación de AndroidView para el SurfaceView/TextureView
    // que tenías en el archivo original.
}
