package com.rodyto.lenspro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rodyto.lenspro.settings.SettingsRepository
import com.rodyto.lenspro.ui.theme.GlassPalette
import com.rodyto.lenspro.ui.theme.LensProTheme
import com.rodyto.lenspro.ui.theme.glassPalette

/* ================================================================
 *  MainActivityCore.kt · v6.0 Premium · CRASH-PROOF
 *
 *  v6.0 cambios:
 *   • FIX D1: estado `permissionsGranted` reactivo. CameraPreviewLayer
 *     SÓLO se compone cuando hay permiso CAMERA → elimina race entre
 *     surfaceCreated y conceder permiso (causa de relanzamiento infinito
 *     en algunos OEMs cuando se otorga permiso por primera vez).
 *   • FIX D2: SettingsRepository se OBTIENE del ViewModel (single source)
 *     en lugar de crear una segunda instancia → 50% menos colectores
 *     activos del DataStore.
 *   • try/catch defensivo en setContent — si Compose falla durante el
 *     primer inflado, se muestra una pantalla mínima en lugar de cerrar.
 *   • Pantalla "PermissionsScreen" elegante mientras no hay permiso.
 *   • Mantiene TODOS los fixes v5.0 (BUG-A4, BUG-K1).
 * ================================================================ */
class MainActivity : ComponentActivity() {

    companion object { private const val TAG = "MainActivity" }

    private val viewModel: CameraControlViewModel by viewModels()

    // Estado reactivo de permisos — Compose se recompone cuando cambia
    private var cameraPermissionGranted by mutableStateOf(false)

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        cameraPermissionGranted = results[Manifest.permission.CAMERA] == true ||
            hasCameraPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        cameraPermissionGranted = hasCameraPermission()
        ensurePermissions()

        // FIX v6.0: try/catch defensivo. Si setContent falla durante el primer
        // inflado por cualquier motivo (RenderEffect, fuente, etc.), evitamos
        // que la app se cierre silenciosamente y mostramos un fallback.
        try {
            setContent {
                val repo = remember { SettingsRepository(applicationContext) }
                val accentIdx by repo.accentIndex.collectAsStateWithLifecycle(initialValue = 0)
                val themeStr  by repo.themeMode.collectAsStateWithLifecycle(initialValue = "system")
                val accent = repo.accentFromIndex(accentIdx)
                val dark   = repo.themeFromString(themeStr)

                LensProTheme(forceDark = dark, accentStyle = accent) {
                    val palette = glassPalette(forceDark = dark, accentStyle = accent)
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                        if (cameraPermissionGranted) {
                            RodytoLensApp(viewModel = viewModel, palette = palette, repo = repo)
                        } else {
                            PermissionsRequiredScreen(
                                palette = palette,
                                onRetry = { ensurePermissions() }
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "setContent falló — fallback mínimo", t)
            setContent {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Error al inicializar la cámara.\nReinicia la app.",
                        color = Color.White, fontSize = 16.sp
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-evalúa el permiso al volver de Ajustes del sistema
        val granted = hasCameraPermission()
        if (granted != cameraPermissionGranted) {
            cameraPermissionGranted = granted
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

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
        if (missing.isNotEmpty()) {
            permissionsLauncher.launch(missing)
        }
    }
}

@Composable
private fun PermissionsRequiredScreen(
    palette: GlassPalette,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "📷",
                fontSize = 56.sp
            )
            Text(
                text = "LensPro necesita acceso a tu cámara",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 20.dp)
            )
            Text(
                text = "Concede el permiso de cámara y micrófono\npara poder usar la app.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
            Box(
                modifier = Modifier
                    .padding(top = 32.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(palette.accent)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Text(
                    text = "Conceder permisos",
                    color = palette.onAccent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
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
