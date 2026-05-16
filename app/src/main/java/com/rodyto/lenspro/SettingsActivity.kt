package com.rodyto.lenspro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * SettingsActivity — Pantalla "Ajustes avanzados" estilo iOS 26 Settings.
 *
 * UI con secciones agrupadas, glass cards y switches. Persiste vía SettingsRepository.
 * Acceso desde MainActivity → menú desplegable → "Abrir configuración".
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Lee el estilo seleccionado del repo (sin lifecycle bind para mantener simple)
            var accent by remember { mutableStateOf(AccentStyle.ICE_BLUE) }
            var dark by remember { mutableStateOf<Boolean?>(null) }
            LensProTheme(forceDark = dark, accentStyle = accent) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    SettingsScreen(
                        accent = accent,
                        onAccent = { accent = it },
                        darkPref = dark,
                        onDark = { dark = it },
                        onBack = { finish() }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    accent: AccentStyle,
    onAccent: (AccentStyle) -> Unit,
    darkPref: Boolean?,
    onDark: (Boolean?) -> Unit,
    onBack: () -> Unit
) {
    val palette = glassPalette(forceDark = darkPref, accentStyle = accent)
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    // Estados locales (replican el repo; en producción usarías collectAsStateWithLifecycle)
    var histogram by remember { mutableStateOf(false) }
    var horizon by remember { mutableStateOf(false) }
    var focusPeak by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf(false) }
    var orgByDate by remember { mutableStateOf(true) }
    var smoothZoom by remember { mutableStateOf(true) }
    var video60 by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(palette.letterboxTop, Color.Black, palette.letterboxBottom)
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                // ── Header ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(palette.ultraBase)
                            .border(0.6.dp, palette.borderSoft, CircleShape)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("‹", color = palette.onGlass, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.size(14.dp))
                    Text(
                        "Ajustes avanzados",
                        color = palette.onGlass,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // ── SECCIÓN: APARIENCIA ──
            item { SectionTitle("Apariencia", palette) }
            item {
                GlassCard(palette) {
                    SettingsRowAction(
                        label = "Color de interfaz",
                        value = accent.label,
                        palette = palette,
                        onClick = {
                            val list = AccentStyle.entries
                            onAccent(list[(list.indexOf(accent) + 1) % list.size])
                        }
                    )
                    Divider(palette)
                    SettingsRowAction(
                        label = "Tema",
                        value = when (darkPref) { true -> "Oscuro"; false -> "Claro"; null -> "Sistema" },
                        palette = palette,
                        onClick = {
                            onDark(when (darkPref) { null -> true; true -> false; false -> null })
                        }
                    )
                }
            }

            // ── SECCIÓN: COMPOSICIÓN ──
            item { SectionTitle("Composición", palette) }
            item {
                GlassCard(palette) {
                    SettingsRowSwitch(
                        label = "Histograma en tiempo real",
                        sub = "Análisis de luminancia desde YUV",
                        checked = histogram, palette = palette,
                        onChange = {
                            histogram = it
                            scope.launch { repo.set(SettingsRepository.KEY_HISTOGRAM, it) }
                        }
                    )
                    Divider(palette)
                    SettingsRowSwitch(
                        label = "Horizonte artificial",
                        sub = "Usa el acelerómetro del dispositivo",
                        checked = horizon, palette = palette,
                        onChange = {
                            horizon = it
                            scope.launch { repo.set(SettingsRepository.KEY_HORIZON, it) }
                        }
                    )
                    Divider(palette)
                    SettingsRowSwitch(
                        label = "Focus peaking",
                        sub = "Resalta bordes enfocados (manual focus)",
                        checked = focusPeak, palette = palette,
                        onChange = {
                            focusPeak = it
                            scope.launch { repo.set(SettingsRepository.KEY_FOCUS_PEAK, it) }
                        }
                    )
                }
            }

            // ── SECCIÓN: CAPTURA ──
            item { SectionTitle("Captura", palette) }
            item {
                GlassCard(palette) {
                    SettingsRowSwitch(
                        label = "Formato RAW (DNG)",
                        sub = "Guarda el negativo digital sin procesar",
                        checked = raw, palette = palette,
                        onChange = {
                            raw = it
                            scope.launch { repo.set(SettingsRepository.KEY_RAW, it) }
                        }
                    )
                    Divider(palette)
                    SettingsRowSwitch(
                        label = "Zoom suave",
                        sub = "Interpolación entre lentes (380 ms)",
                        checked = smoothZoom, palette = palette,
                        onChange = {
                            smoothZoom = it
                            scope.launch { repo.set(SettingsRepository.KEY_SMOOTH_ZOOM, it) }
                        }
                    )
                    Divider(palette)
                    SettingsRowSwitch(
                        label = "Video 60 fps por defecto",
                        sub = "Si el sensor lo soporta",
                        checked = video60, palette = palette,
                        onChange = {
                            video60 = it
                            scope.launch { repo.set(SettingsRepository.KEY_VIDEO_60FPS, it) }
                        }
                    )
                }
            }

            // ── SECCIÓN: ARCHIVOS ──
            item { SectionTitle("Archivos y galería", palette) }
            item {
                GlassCard(palette) {
                    SettingsRowSwitch(
                        label = "Organizar por fecha",
                        sub = "DCIM/LensPro/yyyy-MM-dd",
                        checked = orgByDate, palette = palette,
                        onChange = {
                            orgByDate = it
                            scope.launch { repo.set(SettingsRepository.KEY_ORG_BY_DATE, it) }
                        }
                    )
                }
            }

            // ── PALETAS DE COLOR (selección directa) ──
            item { SectionTitle("Paleta de colores", palette) }
            item {
                GlassCard(palette) {
                    AccentStyle.entries.forEachIndexed { idx, style ->
                        AccentRow(
                            style = style,
                            selected = style == accent,
                            palette = palette,
                            onClick = { onAccent(style) }
                        )
                        if (idx < AccentStyle.entries.size - 1) Divider(palette)
                    }
                }
            }

            item {
                Text(
                    "LensPro v2.0 • Glass UI • Camera2 + NDK\nFusionado con donantes Google · OpenCamera · SimpleRawCamera",
                    color = palette.onGlassSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 18.dp, bottom = 30.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, palette: GlassPalette) {
    Text(
        text = text.uppercase(),
        color = palette.onGlassSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun GlassCard(palette: GlassPalette, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .liquidGlass(palette, RoundedCornerShape(20.dp), strong = true)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) { content() }
}

@Composable
private fun Divider(palette: GlassPalette) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp)
            .height(0.6.dp)
            .background(palette.borderSoft)
    )
}

@Composable
private fun SettingsRowSwitch(
    label: String, sub: String? = null, checked: Boolean,
    palette: GlassPalette, onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = palette.onGlass, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (sub != null) {
                Text(sub, color = palette.onGlassSecondary, fontSize = 12.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = palette.accent,
                checkedThumbColor = palette.onAccent,
                uncheckedTrackColor = palette.onGlassSecondary.copy(alpha = 0.22f),
                uncheckedThumbColor = palette.onGlass
            )
        )
    }
}

@Composable
private fun SettingsRowAction(
    label: String, value: String, palette: GlassPalette, onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = palette.onGlass, fontSize = 15.sp, modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium)
        Text(value, color = palette.onGlassSecondary, fontSize = 14.sp)
        Spacer(Modifier.size(6.dp))
        LensIcon(LensIcons.ChevronRight, tint = palette.onGlassSecondary, size = 16.dp)
    }
}

@Composable
private fun AccentRow(
    style: AccentStyle, selected: Boolean, palette: GlassPalette, onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(style.accent)
                .border(0.8.dp, palette.borderSoft, CircleShape)
        )
        Spacer(Modifier.size(12.dp))
        Text(style.label, color = palette.onGlass, fontSize = 15.sp,
            modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        if (selected) {
            Text("✓", color = palette.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}
