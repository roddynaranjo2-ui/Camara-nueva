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
import androidx.compose.runtime.getValue      // ← FIX v4.0.1 (E1/E2): habilita `by State<T>`
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue      // ← simetría (futuro `var by mutableStateOf`)
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/* ================================================================
 *  MainActivityCore.kt · REDISEÑO v4.0.1 LIQUID GLASS
 *
 *  FIX v4.0.1:
 *   • Añadido `import androidx.compose.runtime.getValue` (y setValue
 *     por simetría). Sin él, `val x by stateFlow.collectAsStateWith
 *     Lifecycle()` fallaba con:
 *       Type 'State<…>' has no method 'getValue(Nothing?, KProperty<*>)'
 *       and thus it cannot serve as a delegate
 *     porque `getValue` es una extensión de `State<T>` y debe estar
 *     importada para que el operador `by` la resuelva.
 *   • Pequeña guard en ensurePermissions() — evita lanzar el launcher
 *     con un array vacío en algunos OEM que devuelven todo concedido.
 *
 *  Composición simplificada: Preview + UI minimal iOS 26.
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

        if (missing.isNotEmpty()) {
            permissionsLauncher.launch(missing)
        }
    }
}

/* ================================================================
 *  Composición principal — 3 capas limpias:
 *   0. Camera Preview (hardware)
 *   1. Top Quick Settings Island (modo, resolución, fps, ajustes)
 *   2. Bottom Controls (lentes, shutter, gallery, flip, Pro Peek)
 * ================================================================ */
@Composable
fun RodytoLensApp(
    viewModel: CameraControlViewModel,
    palette: GlassPalette,
    repo: SettingsRepository
) {
    LaunchedEffect(Unit) { viewModel.applyRepeatingPreview() }

    Box(modifier = Modifier.fillMaxSize()) {
        // Capa 0 — Camera Preview
        CameraPreviewLayer(viewModel = viewModel)

        // Capa 1 + 2 — UI Liquid Glass minimal
        LiquidGlassUiLayer(
            viewModel = viewModel,
            palette = palette,
            repo = repo
        )
    }
}

@Composable
fun CameraPreviewLayer(viewModel: CameraControlViewModel) {
    CameraPreview(
        viewModel = viewModel,
        modifier = Modifier.fillMaxSize(),
        onPreviewBoundsChanged = { /* no usado en rediseño minimal */ }
    )
    CameraXAnalysisBridge(vm = viewModel)
}
