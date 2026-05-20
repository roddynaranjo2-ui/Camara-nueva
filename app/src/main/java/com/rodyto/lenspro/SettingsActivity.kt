package com.rodyto.lenspro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rodyto.lenspro.settings.SettingsRepository
import com.rodyto.lenspro.ui.theme.AccentStyle
import com.rodyto.lenspro.ui.theme.GlassPalette
import com.rodyto.lenspro.ui.theme.LensProTheme
import com.rodyto.lenspro.ui.theme.glassPalette
import com.rodyto.lenspro.ui.components.liquidGlass
import com.rodyto.lenspro.ui.components.LensIcon
import com.rodyto.lenspro.util.*
import kotlinx.coroutines.launch

/**
 * SettingsActivity v3.6 Pro — OPTIMIZADO
 *
 * CORRECCIONES v3.6 (sobre v3.0):
 *  ① Paletas: ahora usan `items(AccentStyle.entries) { style → AccentRow }`
 *    dentro del LazyColumn → cada fila se RECICLA correctamente.
 *    Antes: `AccentStyle.entries.forEachIndexed { … }` dentro de UN ÚNICO
 *    `item { }` → toda la lista se composaba de golpe, sin reciclaje,
 *    provocando lag al hacer scroll en la sección "Paleta de colores".
 *  ② Selección directa por click — al pulsar una paleta se llama a
 *    `repo.setAccentIndex(idx)` con el ÍNDICE de la paleta tocada, no
 *    al ciclo.
 *  ③ collectAsStateWithLifecycle ya se beneficia internamente de
 *    repeatOnLifecycle → cuando la activity está en STOPPED ya no
 *    consume del DataStore.
 *  ④ Limpieza: se removieron StateFlows colectados pero no usados.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        setContent {
            val context = LocalContext.current
            val repo = remember { SettingsRepository(context) }
            val accentIdx by repo.accentIndex.collectAsStateWithLifecycle(initialValue = 0)
            val themeStr by repo.themeMode.collectAsStateWithLifecycle(initialValue = "system")
            val accent = repo.accentFromIndex(accentIdx)
            val dark = repo.themeFromString(themeStr)
            LensProTheme(forceDark = dark, accentStyle = accent) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    SettingsScreen(
                        repo = repo,
                        accent = accent,
                        darkPref = dark,
                        onBack = { finish() }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    repo: SettingsRepository,
    accent: AccentStyle,
    darkPref: Boolean?,
    onBack: () -> Unit
) {
    val palette = glassPalette(forceDark = darkPref, accentStyle = accent)
    val scope = rememberCoroutineScope()

    // ── Composición / overlays ─────────────────────────────────────────
    val histogram by repo.histogramEnabled.collectAsStateWithLifecycle(initialValue = false)
    val horizon by repo.horizonEnabled.collectAsStateWithLifecycle(initialValue = false)
    val focusPeak by repo.focusPeaking.collectAsStateWithLifecycle(initialValue = false)
    val grid by repo.gridEnabled.collectAsStateWithLifecycle(initialValue = false)

    // ── Captura ────────────────────────────────────────────────────────
    val raw by repo.rawCapture.collectAsStateWithLifecycle(initialValue = false)
    val hdr by repo.hdrEnabled.collectAsStateWithLifecycle(initialValue = false)
    val hevc by repo.hevcEnabled.collectAsStateWithLifecycle(initialValue = false)
    val flashStr by repo.flashMode.collectAsStateWithLifecycle(initialValue = "OFF")
    val timer by repo.timerSeconds.collectAsStateWithLifecycle(initialValue = 0)

    // ── Audio / vibración ──────────────────────────────────────────────
    val sound by repo.shutterSound.collectAsStateWithLifecycle(initialValue = true)
    val haptics by repo.hapticsEnabled.collectAsStateWithLifecycle(initialValue = true)

    // ── Video ──────────────────────────────────────────────────────────
    val videoResStr by repo.videoResolution.collectAsStateWithLifecycle(initialValue = "FHD")
    val videoFpsInt by repo.videoFps.collectAsStateWithLifecycle(initialValue = 30)
    val video60Default by repo.video60fpsDefault.collectAsStateWithLifecycle(initialValue = false)

    // ── Zoom / lente ───────────────────────────────────────────────────
    val smoothZoom by repo.smoothZoom.collectAsStateWithLifecycle(initialValue = true)

    // ── Aspect ratio ───────────────────────────────────────────────────
    val aspectStr by repo.manualAspect.collectAsStateWithLifecycle(initialValue = null)

    // ── Archivos ───────────────────────────────────────────────────────
    val orgByDate by repo.organizeByDate.collectAsStateWithLifecycle(initialValue = true)

    // ── v3.5+ Pro: arquitectura híbrida ───────────────────────────────
    val useCameraX by repo.useCameraXAnalysis.collectAsStateWithLifecycle(initialValue = false)
    val forceTeleId by repo.forceTelePhysicalId.collectAsStateWithLifecycle(initialValue = false)
    val teleId by repo.telePhysicalId.collectAsStateWithLifecycle(initialValue = "52")
    val proVendor by repo.proVendorTags.collectAsStateWithLifecycle(initialValue = true)

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
            // ─── Cabecera ─────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .liquidGlass(palette, CircleShape, strong = true)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "‹",
                            color = palette.onGlass,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.size(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Ajustes avanzados",
                            color = palette.onGlass,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Rodyto Lens Pro v${BuildConfig.VERSION_NAME} · Liquid Glass UI",
                            color = palette.onGlassSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // ─── Apariencia ───────────────────────────────────────────
            item { SectionTitle("Apariencia", palette) }
            item {
                GlassCard(palette) {
                    SettingsRowAction(
                        label = "Color de interfaz",
                        value = accent.label,
                        palette = palette,
                        onClick = {
                            val list = AccentStyle.entries
                            val nextIdx = (list.indexOf(accent) + 1) % list.size
                            scope.launch { repo.setAccentIndex(nextIdx) }
                        }
                    )
                    Divider(palette)
                    SettingsRowAction(
                        label = "Tema",
                        value = when (darkPref) { true -> "Oscuro"; false -> "Claro"; null -> "Sistema" },
                        palette = palette,
                        onClick = {
                            val nextStr = when (darkPref) {
                                null -> "dark"; true -> "light"; false -> "system"
                            }
                            scope.launch { repo.setThemeMode(nextStr) }
                        }
                    )
                }
            }

            // ─── Composición y overlays ───────────────────────────────
            item { SectionTitle("Composición y overlays", palette) }
            item {
                GlassCard(palette) {
                    SettingsRowSwitch(
                        label = "Cuadrícula 3×3",
                        sub = "Guía de composición",
                        checked = grid, palette = palette,
                        onChange = { scope.launch { repo.setGrid(it) } }
                    )
                    Divider(palette)
                    SettingsRowSwitch(
                        label = "Histograma en tiempo real",
                        sub = "Análisis de luminancia desde YUV",
                        checked = histogram, palette = palette,
                        onChange = { scope.launch { repo.setHistogram(it) } }
                    )
                    Divider(palette)
                    SettingsRowSwitch(
                        label = "Horizonte artificial",
                        sub = "Usa el acelerómetro del dispositivo",
                        checked = horizon, palette = palette,
                        onChange = { scope.launch { repo.setHorizon(it) } }
                    )
                    Divider(palette)
                    SettingsRowSwitch(
                        label = "Focus peaking",
                        sub = "Resalta bordes enfocados (manual focus)",
                        checked = focusPeak, palette = palette,
                        onChange = { scope.launch { repo.setFocusPeaking(it) } }
                    )
                }
            }

            // ─── Captura (foto) ──────────────────────────────────────
            item { SectionTitle("Captura de foto", palette) }
            item {
                GlassCard(palette) {
                    SettingsRowAction(
                        label = "Flash",
                        value = when (flashStr) { "ON" -> "Encendido"; "AUTO" -> "Auto"; else -> "Apagado" },
                        palette = palette,
                        onClick = {
                            val next = when (flashStr) {
                                "OFF" -> "AUTO"; "AUTO" -> "ON"; else -> "OFF"
                            }
                            scope.launch { repo.setFlashMode(next) }
                        }
                    )
                    Divider(palette)
                    SettingsRowSwitch(
                        label = "HDR",
                        sub = "Alta gama dinámica · Pro Tone Samsung",
                        checked = hdr, palette = palette,
                        onChange = { scope.launch { repo.setHdr(it) } }
                    )
                    Divider(palette)
                    SettingsRowSwitch(
                        label = "Formato RAW (DNG)",
                        sub = "Guarda el negativo digital sin procesar",
                        checked = raw, palette = palette,
                        onChange = { scope.launch { repo.setRaw(it) } }
                    )
                    Divider(palette)
                    SettingsRowAction(
                        label = "Temporizador",
                        value = when (timer) { 3 -> "3 s"; 10 -> "10 s"; else -> "Apagado" },
                        palette = palette,
                        onClick = {
                            val next = when (timer) { 0 -> 3; 3 -> 10; else -> 0 }
                            scope.launch { repo.setTimer(next) }
                        }
                    )
                }
            }

            // ─── Video ────────────────────────────────────────────────
            item { SectionTitle("Vídeo", palette) }
            item {
                GlassCard(palette) {
                    SettingsRowAction(
                        label = "Resolución",
                        value = when (videoResStr) { "UHD" -> "4K · UHD"; "HD" -> "HD · 720p"; else -> "FHD · 1080p" },
                        palette = palette,
                        onClick = {
                            val next = when (videoResStr) {
                                "HD" -> "FHD"; "FHD" -> "UHD"; else -> "HD"
                            }
                            scope.launch { repo.setVideoResolution(next) }
                        }
                    )
                    Divider(palette)
                    SettingsRowAction(
                        label = "Frecuencia",
                        value = "$videoFpsInt fps",
                        palette = palette,
                        onClick = {
                            val next = if (videoFpsInt == 30) 60 else 30
                            scope.launch { repo.setVideoFps(next) }
                        }
                    )
                    Divider(palette)
                    SettingsRowSwitch(
                        label = "60 fps por defecto",
                        sub = "Si el sensor lo soporta en esa resolución",
                        checked = video60Default, palette = palette,
                        onChange = { scope.launch { repo.setVideo60Default(it) } }
                    )
                    Divider(palette)
                    SettingsRowSwitch(
                        label = "Codec HEVC (H.265)",
                        sub = "Mejor compresión · menor tamaño",
                        checked = hevc, palette = palette,
                        onChange = { scope.launch { repo.setHevc(it) } }
                    )
                }
            }

            // ─── Zoom ────────────────────────────────────────────────
            item { SectionTitle("Zoom", palette) }
            item {
                GlassCard(palette) {
                    SettingsRowSwitch(
                        label = "Zoom suave",
                        sub = "Interpolación entre lentes (380 ms)",
                        checked = smoothZoom, palette = palette,
                        onChange = { scope.launch { repo.setSmoothZoom(it) } }
                    )
                }
            }

            // ─── Sonido y vibración ──────────────────────────────────
            item { SectionTitle("Sonido y vibración", palette) }
            item {
                GlassCard(palette) {
                    SettingsRowSwitch(
                        label = "Sonido del obturador",
                        sub = "Click cinematográfico al disparar",
                        checked = sound, palette = palette,
                        onChange = { scope.launch { repo.setSound(it) } }
                    )
                    Divider(palette)
                    SettingsRowSwitch(
                        label = "Vibración háptica",
                        sub = "Feedback al cambiar lente, zoom y modo",
                        checked = haptics, palette = palette,
                        onChange = { scope.launch { repo.setHaptics(it) } }
                    )
                }
            }

            // ─── Relación de aspecto por defecto ─────────────────────
            item { SectionTitle("Relación de aspecto", palette) }
            item {
                GlassCard(palette) {
                    AspectRow("Automático", aspectStr == null, palette) {
                        scope.launch { repo.setManualAspect(null) }
                    }
                    Divider(palette)
                    AspectRow("3:4 · clásico", aspectStr == "3:4", palette) {
                        scope.launch { repo.setManualAspect("3:4") }
                    }
                    Divider(palette)
                    AspectRow("9:16 · vertical", aspectStr == "9:16", palette) {
                        scope.launch { repo.setManualAspect("9:16") }
                    }
                    Divider(palette)
                    AspectRow("1:1 · cuadrado", aspectStr == "1:1", palette) {
                        scope.launch { repo.setManualAspect("1:1") }
                    }
                    Divider(palette)
                    AspectRow("Full · pantalla completa", aspectStr == "FULL", palette) {
                        scope.launch { repo.setManualAspect("FULL") }
                    }
                }
            }

            // ─── v3.5 Pro: Arquitectura híbrida ─────────────────────
            item { SectionTitle("Arquitectura Pro · v${BuildConfig.VERSION_NAME}", palette) }
            item {
                GlassCard(palette) {
                    SettingsRowSwitch(
                        label = "CameraX Image Analysis",
                        sub = "⚠️ Off por defecto — puede causar conflictos HAL en muchos dispositivos",
                        checked = useCameraX, palette = palette,
                        onChange = { scope.launch { repo.setUseCameraXAnalysis(it) } }
                    )
                    Divider(palette)
                    SettingsRowSwitch(
                        label = "Forzar Physical Camera ID en tele",
                        sub = "S21 FE: abre directamente el ID $teleId al pulsar 3× — sólo Samsung con tele real",
                        checked = forceTeleId, palette = palette,
                        onChange = { scope.launch { repo.setForceTelePhysicalId(it) } }
                    )
                    Divider(palette)
                    SettingsRowSwitch(
                        label = "Vendor Tags Samsung",
                        sub = "samsung.android.* (scaler.zoomRatio, liveHdrMode, OIS) — desactiva si no es Samsung",
                        checked = proVendor, palette = palette,
                        onChange = { scope.launch { repo.setProVendorTags(it) } }
                    )
                    Divider(palette)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Physical ID del teleobjetivo", color = palette.onGlass,
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("S21 FE default = 52. Cambia sólo si conoces tu HAL.",
                                color = palette.onGlassSecondary, fontSize = 11.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("52", "54", "3").forEach { candidate ->
                                val selected = teleId == candidate
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(if (selected) palette.accent else Color.Transparent)
                                        .border(
                                            width = if (selected) 0.dp else 0.6.dp,
                                            color = if (selected) Color.Transparent else palette.borderSoft,
                                            shape = RoundedCornerShape(20.dp)
                                        )
                                        .clickable { scope.launch { repo.setTelePhysicalId(candidate) } }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = candidate,
                                        color = if (selected) palette.onAccent else palette.onGlass,
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ─── Archivos y galería ──────────────────────────────────
            item { SectionTitle("Archivos y galería", palette) }
            item {
                GlassCard(palette) {
                    SettingsRowSwitch(
                        label = "Organizar por fecha",
                        sub = "DCIM/LensPro/yyyy-MM-dd",
                        checked = orgByDate, palette = palette,
                        onChange = { scope.launch { repo.setOrganizeByDate(it) } }
                    )
                }
            }

            // ─── Paleta visual completa — FIX v3.6: items() reciclados ────
            item { SectionTitle("Paleta de colores · ${AccentStyle.entries.size} estilos", palette) }

            // Header del card (apertura visual)
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                        .liquidGlass(
                            palette,
                            RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
                            strong = true
                        )
                        .height(6.dp)
                )
            }

            // FIX v3.6 CRÍTICO: usamos items() — cada AccentRow se recicla.
            items(
                items = AccentStyle.entries,
                key = { it.name }
            ) { style ->
                val selected = style == accent
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.ultraBase)
                ) {
                    AccentRow(
                        style = style,
                        selected = selected,
                        palette = palette,
                        onClick = {
                            // FIX v3.6: setAccentIndex DIRECTO al índice de la paleta tocada
                            // (antes era ciclo por click → UX confuso).
                            val idx = AccentStyle.entries.indexOf(style)
                            scope.launch { repo.setAccentIndex(idx) }
                        }
                    )
                    if (style != AccentStyle.entries.last()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp)
                                .height(0.6.dp)
                                .background(palette.borderSoft)
                                .align(Alignment.BottomStart)
                        )
                    }
                }
            }

            // Footer visual del card
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp))
                        .liquidGlass(
                            palette,
                            RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp),
                            strong = true
                        )
                        .height(6.dp)
                )
            }

            // ─── Footer ──────────────────────────────────────────────
            item {
                Text(
                    "Rodyto Lens Pro v${BuildConfig.VERSION_NAME} Pro · Liquid Glass · Camera2 + CameraX + NDK\n" +
                    "RAW DNG real · Manual focus · EXIF dinámico · Hybrid Image Analysis\n" +
                    "${AccentStyle.entries.size} paletas · Samsung Zoom Quality · Physical Tele ID configurable",
                    color = palette.onGlassSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 18.dp, bottom = 30.dp)
                )
            }
        }
    }
}

/* ── Componentes privados ─────────────────────────────────────────── */

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
            .clip(RoundedCornerShape(30.dp))
            .liquidGlass(palette, RoundedCornerShape(30.dp), strong = true)
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
    label: String,
    sub: String? = null,
    checked: Boolean,
    palette: GlassPalette,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = palette.onGlass, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, color = palette.onGlassSecondary, fontSize = 12.sp)
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
    label: String,
    value: String,
    palette: GlassPalette,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = palette.onGlass,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium
        )
        Text(value, color = palette.onGlassSecondary, fontSize = 14.sp)
        Spacer(Modifier.size(6.dp))
        LensIcon(LensIcons.ChevronRight, tint = palette.onGlassSecondary, size = 16.dp)
    }
}

@Composable
private fun AspectRow(
    label: String,
    selected: Boolean,
    palette: GlassPalette,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = palette.onGlass,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(palette.accent),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", color = palette.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AccentRow(
    style: AccentStyle,
    selected: Boolean,
    palette: GlassPalette,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(style.accent)
                .border(
                    width = if (selected) 2.5.dp else 1.dp,
                    color = if (selected) palette.onGlass else palette.borderSoft,
                    shape = CircleShape
                )
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = style.label,
                color = palette.onGlass,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            val category = when {
                style.isPastel -> "Pastel"
                style.isMonochrome -> "Monocromático"
                else -> "Vibrante"
            }
            Text(
                text = category,
                color = palette.onGlassSecondary,
                fontSize = 11.sp
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(palette.accent),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", color = palette.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
