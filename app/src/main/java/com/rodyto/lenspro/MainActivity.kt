package com.rodyto.lenspro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val cameraViewModel: CameraControlViewModel = viewModel()
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                // El PermissionWrapper ahora llama al CameraScreen real
                CameraPermissionWrapper(cameraViewModel)
            }
        }
    }
}
