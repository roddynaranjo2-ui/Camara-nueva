package com.rodyto.lenspro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.combine

/* ================================================================
 *  MainActivityCore.kt · v4.0 Premium
 *
 *  v4.0 — Cambios:
 *   • Bridge sincronizado SettingsRepository ↔ ViewModel para
 *     flashMode, timerSeconds, gridEnabled, hapticsEnabled,
 *     soundEnabled — TODO funcional.
 * ================================================================ */
class MainActivity : ComponentActivity() {

    private val viewModel: CameraControlViewModel by viewModels()

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensurePermissions()

        setContent {
            val context = LocalContext.current
            val repo = remember { SettingsRepository(context) }
            val accentIdx by repo.accentIndex.collectAsStateWithLifecycle(initialValue = 0)
            val themeStr  by repo.themeMode.collectAsStateWithLifecycle(initialValue = "system")
            val accent = repo.accentFromIndex(accentIdx)
            val dark   = repo.themeFromString(themeStr)

            // ─── Bridges Repo → ViewModel
            val flashStr by repo.flashMode.collectAsStateWithLifecycle(initialValue = "OFF")
            val timer by repo.timerSeconds.collectAsStateWithLifecycle(initialValue = 0)
            val gridOn by repo.gridEnabled.collectAsStateWithLifecycle(initialValue = false)
            val hapOn by repo.hapticsEnabled.collectAsStateWithLifecycle(initialValue = true)
            val soundOn by repo.shutterSound.collectAsStateWithLifecycle(initialValue = true)

            LaunchedEffect(flashStr) { viewModel.setFlashMode(repo.flashFromString(flashStr)) }
            LaunchedEffect(timer)    { viewModel.setTimerSeconds(timer) }
            LaunchedEffect(gridOn)   { viewModel.setGridEnabled(gridOn) }
            LaunchedEffect(hapOn)    { viewModel.setHapticsEnabled(hapOn) }
            LaunchedEffect(soundOn)  { viewModel.setSoundEnabled(soundOn) }

            LensProTheme(forceDark = dark, accentStyle = accent) {
                val palette = glassPalette(forceDark = dark, accentStyle = accent)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    RodytoLensApp(viewModel = viewModel, palette = palette, repo = repo)
                }
            }
        }
    }

    private fun ensurePermissions() {
        val perms = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        }.toTypedArray()

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missing.isNotEmpty()) permissionsLauncher.launch(missing)
    }
}

@Composable
fun RodytoLensApp(
    viewModel: CameraControlViewModel,
    palette: GlassPalette,
    repo: SettingsRepository
) {
    LaunchedEffect(Unit) { viewModel.applyRepeatingPreview() }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreviewLayer(viewModel = viewModel)
        LiquidGlassUiLayer(viewModel = viewModel, palette = palette, repo = repo)
    }
}

@Composable
fun CameraPreviewLayer(viewModel: CameraControlViewModel) {
    CameraPreview(
        viewModel = viewModel,
        modifier = Modifier.fillMaxSize(),
        onPreviewBoundsChanged = { /* no usado */ }
    )
    CameraXAnalysisBridge(vm = viewModel)
}
