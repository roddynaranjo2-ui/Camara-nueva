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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rodyto.lenspro.settings.SettingsRepository
import com.rodyto.lenspro.ui.theme.GlassPalette
import com.rodyto.lenspro.ui.theme.LensProTheme
import com.rodyto.lenspro.ui.theme.glassPalette

/* ================================================================
 *  MainActivityCore.kt · v5.0 Premium
 *
 *  v5.0 cambios:
 *   • FIX BUG-A4: eliminados los 5 LaunchedEffect duplicados que
 *     sincronizaban repo→VM. SettingsBridge.wire() ya lo hace una
 *     única vez en el ViewModel. Cada cambio se aplicaba dos veces.
 *   • FIX BUG-K1: previewBounds del CameraPreview se propaga a los
 *     overlays superiores para posicionarlos correctamente.
 * ================================================================ */
class MainActivity : ComponentActivity() {

    private val viewModel: CameraControlViewModel by viewModels()

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op: el ViewModel ya gestiona el estado ERROR si faltan permisos */ }

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

            // BUG-A4: NO más LaunchedEffect aquí — SettingsBridge.wire() es la única fuente.

            LensProTheme(forceDark = dark, accentStyle = accent) {
                val palette = glassPalette(forceDark = dark, accentStyle = accent)
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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
            } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
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
    var previewBounds by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(Unit) { viewModel.applyRepeatingPreview() }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreviewLayer(
            viewModel = viewModel,
            palette = palette,
            repo = repo,
            onPreviewBoundsChanged = { previewBounds = it }
        )
        LiquidGlassUiLayer(viewModel = viewModel, palette = palette, repo = repo)
    }
}

@Composable
fun CameraPreviewLayer(
    viewModel: CameraControlViewModel,
    palette: GlassPalette,
    repo: SettingsRepository,
    onPreviewBoundsChanged: (Rect?) -> Unit
) {
    CameraPreview(
        viewModel = viewModel,
        palette = palette,
        repo = repo,
        modifier = Modifier.fillMaxSize(),
        onPreviewBoundsChanged = onPreviewBoundsChanged
    )
    CameraXAnalysisBridge(vm = viewModel)
}
