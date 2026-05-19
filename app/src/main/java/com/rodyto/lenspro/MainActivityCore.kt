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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/* ================================================================
 *  Rodyto Lens Pro · MainActivityCore.kt · v3.6 Pro
 *
 *  Archivo 1 de la división de MainActivity. Responsabilidades:
 *    • Permisos modernos (ActivityResultLauncher, no deprecated).
 *    • Lifecycle de la Activity + enableEdgeToEdge.
 *    • Instanciación del ViewModel y composición de las 3 capas:
 *        Preview  ·  Overlays (paneles)  ·  Helpers (controles)
 *
 *  CORRECCIONES sobre el esqueleto v3.5:
 *    • ActivityCompat.requestPermissions → ActivityResultContracts.
 *    • WRITE_EXTERNAL_STORAGE eliminado del set en runtime: en API 33+
 *      no concede nada; el manifest ya lo limita a maxSdkVersion=28.
 *    • Llamada a enableEdgeToEdge() — coherente con SettingsActivity.
 *    • CameraPreviewLayer ahora SÍ monta CameraPreview().
 *    • Tema integrado vía LensProTheme + glassPalette.
 * ================================================================ */
class MainActivity : ComponentActivity() {

    private val viewModel: CameraControlViewModel by viewModels()

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* No bloqueamos: si CAMERA está denegado, la UI muestra estado vacío. */ }

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

    /* ── Permisos modernos ─────────────────────────────────────── */
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
        }
        if (missing.isNotEmpty()) permissionsLauncher.launch(missing.toTypedArray())
    }
}

/* ================================================================
 *  Composición principal — orquesta las 3 capas refactorizadas.
 *  Las capas viven en MainActivityOverlays.kt / MainActivityHelpers.kt
 * ================================================================ */
@Composable
fun RodytoLensApp(
    viewModel: CameraControlViewModel,
    palette: GlassPalette,
    repo: SettingsRepository
) {
    // Refresh inicial del repeating preview cuando arranca la composición.
    LaunchedEffect(Unit) { viewModel.applyRepeatingPreview() }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── 1. Capa de Vista Previa (Camera2 SurfaceView) ──────
        CameraPreviewLayer(viewModel)

        // ── 2. Capa de Paneles/Overlays (chips, histograma…) ───
        SettingsOverlayLayer(viewModel, palette, repo)

        // ── 3. Capa de Controles inferiores (lentes, shutter…) ─
        MainControlsLayer(viewModel, palette, repo)
    }
}

@Composable
fun CameraPreviewLayer(viewModel: CameraControlViewModel) {
    // Delega 100% en el componente ya existente y testeado.
    CameraPreview(
        viewModel = viewModel,
        modifier = Modifier.fillMaxSize()
    )
    // El bridge CameraX se activa o no según el flag persistido.
    CameraXAnalysisBridge(vm = viewModel)
}
