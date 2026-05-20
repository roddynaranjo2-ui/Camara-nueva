package com.rodyto.lenspro

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border          // ← FIX v4.0.1 (E3): faltaba este import
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/* ================================================================
 *  LiquidGlassUiLayer · iOS 26 minimal premium · v4.0.1
 *
 *  FIX v4.0.1 (sobre v4.0):
 *   • Import añadido: androidx.compose.foundation.border.
 *   • `androidx_border_white_ring` reescrito como extensión correcta
 *     de Modifier (this.border(...)), eliminando el "Unresolved
 *     reference: border" en compileDebugKotlin.
 *   • LiquidShutter ya no envuelve la extensión con .then(...) — la
 *     extensión por sí misma devuelve Modifier.
 *
 *  Componentes (de arriba a abajo):
 *   ┌─ Quick Settings Island (top)
 *   │   • [FOTO/VIDEO] (modo activo en gris claro)
 *   │   • [4K · FHD · HD] (solo VIDEO)
 *   │   • [30 · 60] (solo VIDEO)
 *   │   • ⚙ Ajustes → abre Pro Peek Panel
 *   │
 *   ├─ Lens Dial (sobre el shutter)
 *   │   • .5 · 1× · 3×
 *   │
 *   ├─ Mode shifter Carousel (FOTO · VIDEO con máscara alpha)
 *   │
 *   └─ Bottom Action Block
 *       • [Gallery 50pt] · [Shutter 76pt morphing] · [Flip 50pt]
 *
 *  Pro Peek Panel (modal-sheet) emerge desde abajo (280pt alto).
 * ================================================================ */

@Composable
fun LiquidGlassUiLayer(
    viewModel: CameraControlViewModel,
    palette: GlassPalette,
    repo: SettingsRepository
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // Estado de cámara
    val cameraMode by viewModel.cameraMode.collectAsStateWithLifecycle()
    val zoom       by viewModel.zoomLevel.collectAsStateWithLifecycle()
    val recording  by viewModel.isRecording.collectAsStateWithLifecycle()

    // Persistencia
    val lensLabel  by repo.lastLens.collectAsStateWithLifecycle(initialValue = "1x")
    val hapticsOn  by repo.hapticsEnabled.collectAsStateWithLifecycle(initialValue = true)
    val videoRes   by repo.videoResolution.collectAsStateWithLifecycle(initialValue = "FHD")
    val videoFps   by repo.videoFps.collectAsStateWithLifecycle(initialValue = 30)

    // Pro Peek panel
    var showProPeek by remember { mutableStateOf(false) }

    val haptic: () -> Unit = remember(hapticsOn) {
        { if (hapticsOn) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    }

    val isVideoMode = cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.PRO_VIDEO

    Box(modifier = Modifier.fillMaxSize()) {

        // ─────────── CAPA 1 · QUICK SETTINGS ISLAND ───────────
        QuickSettingsIsland(
            isVideoMode = isVideoMode,
            videoRes = videoRes,
            videoFps = videoFps,
            onCycleResolution = {
                haptic()
                val next = when (videoRes) {
                    "UHD" -> "FHD"; "FHD" -> "HD"; else -> "UHD"
                }
                scope.launch { repo.setVideoResolution(next) }
            },
            onCycleFps = {
                haptic()
                val next = if (videoFps == 30) 60 else 30
                scope.launch { repo.setVideoFps(next) }
            },
            onOpenSettings = { haptic(); showProPeek = true },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // ─────────── CAPA 2 · LENS DIAL ───────────
        LensDial(
            currentLens = lensLabel,
            onSelect = { lens ->
                haptic()
                scope.launch { repo.setLastLens(lens) }
                viewModel.setLens(buildLensInfoFromLabel(lens))
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 224.dp)
        )

        // ─────────── CAPA 3 · MODE SHIFTER CAROUSEL ───────────
        ModeShifterCarousel(
            currentMode = if (isVideoMode) "VIDEO" else "FOTO",
            palette = palette,
            onModeChange = { newLabel ->
                haptic()
                val newMode = if (newLabel == "VIDEO") CameraMode.VIDEO else CameraMode.PHOTO
                viewModel.setCameraMode(newMode)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 138.dp)
        )

        // ─────────── CAPA 4 · BOTTOM ACTION BLOCK ───────────
        BottomActionBlock(
            isRecording = recording,
            isVideoMode = isVideoMode,
            onShutterTap = {
                haptic()
                if (isVideoMode) viewModel.toggleRecording() else viewModel.takePhoto()
            },
            onFlipCamera = { haptic(); viewModel.switchCamera() },
            onOpenGallery = { /* hook galería (no implementado en v4.0) */ },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 38.dp)
        )

        // ─────────── PRO PEEK PANEL (Modal Sheet) ───────────
        ProPeekPanel(
            visible = showProPeek,
            videoRes = videoRes,
            videoFps = videoFps,
            isVideoMode = isVideoMode,
            onCycleResolution = {
                haptic()
                val next = when (videoRes) {
                    "UHD" -> "FHD"; "FHD" -> "HD"; else -> "UHD"
                }
                scope.launch { repo.setVideoResolution(next) }
            },
            onCycleFps = {
                haptic()
                val next = if (videoFps == 30) 60 else 30
                scope.launch { repo.setVideoFps(next) }
            },
            onDismiss = { showProPeek = false }
        )
    }
}

/* ════════════════════════════════════════════════════════════════
 *  A · QUICK SETTINGS ISLAND  (Top floating pill)
 *  Width = Screen - 32pt, Height = 52pt, Radius = 26pt
 * ════════════════════════════════════════════════════════════════ */
@Composable
private fun QuickSettingsIsland(
    isVideoMode: Boolean,
    videoRes: String,
    videoFps: Int,
    onCycleResolution: () -> Unit,
    onCycleFps: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 10.dp, start = 16.dp, end = 16.dp)
            .fillMaxWidth()
            .height(52.dp)
    ) {
        LiquidGlassPill(
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Indicador discreto del modo
                ModeChipIndicator(isVideoMode = isVideoMode)

                // Resolución (solo VIDEO)
                AnimatedVisibility(
                    visible = isVideoMode,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(140))
                ) {
                    PillTextButton(
                        label = when (videoRes) { "UHD" -> "4K"; "HD" -> "HD"; else -> "FHD" },
                        onClick = onCycleResolution,
                        active = videoRes == "UHD"
                    )
                }

                // FPS (solo VIDEO)
                AnimatedVisibility(
                    visible = isVideoMode,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(140))
                ) {
                    PillTextButton(
                        label = "${videoFps}",
                        suffix = "FPS",
                        onClick = onCycleFps,
                        active = videoFps == 60
                    )
                }

                // Ajustes
                IslandIconButton(
                    icon = Icons.Rounded.Settings,
                    contentDescription = "Ajustes",
                    onClick = onOpenSettings
                )
            }
        }
    }
}

@Composable
private fun ModeChipIndicator(isVideoMode: Boolean) {
    val icon = if (isVideoMode) Icons.Rounded.Videocam else Icons.Rounded.PhotoCamera
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LensIcon(
            icon = icon,
            tint = Color.White.copy(alpha = 0.95f),
            size = 18.dp
        )
        Text(
            text = if (isVideoMode) "VIDEO" else "FOTO",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
private fun PillTextButton(
    label: String,
    suffix: String? = null,
    onClick: () -> Unit,
    active: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedCompat()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(stiffness = 300f, dampingRatio = 0.78f),
        label = "pill_scale"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (active) Color.White.copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.08f)
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        if (suffix != null) {
            Text(
                text = suffix,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun IslandIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedCompat()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(stiffness = 300f, dampingRatio = 0.7f),
        label = "icon_btn_scale"
    )
    Box(
        modifier = Modifier
            .size(44.dp)            // hit target 44pt
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = 22.dp),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        LensIcon(
            icon = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            size = 20.dp
        )
    }
}

/* ════════════════════════════════════════════════════════════════
 *  B · LENS DIAL  (.5 · 1× · 3×)
 *  Cápsula 42pt alto, círculos 32pt, gap 12pt
 * ════════════════════════════════════════════════════════════════ */
@Composable
private fun LensDial(
    currentLens: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    lenses: List<String> = listOf("0.5x", "1x", "3x")
) {
    Row(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .liquidGlassModifier(
                shape = RoundedCornerShape(21.dp),
                blurRadiusDp = 20.dp
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        lenses.forEach { lens ->
            LensBubble(
                label = lens,
                selected = currentLens == lens,
                onClick = { onSelect(lens) }
            )
        }
    }
}

@Composable
private fun LensBubble(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val display = when (label) {
        "0.5x" -> ".5"
        "1x"   -> "1×"
        "2x"   -> "2×"
        "3x"   -> "3×"
        "5x"   -> "5×"
        else   -> label
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedCompat()

    val targetSize = if (selected) 32.dp else 28.dp
    val animSize by animateDpAsState(
        targetValue = if (pressed) targetSize * 0.92f else targetSize,
        animationSpec = spring(stiffness = 300f, dampingRatio = 0.7f),
        label = "lens_size"
    )

    Box(
        modifier = Modifier
            .size(32.dp)   // hit area constante 32pt
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = 16.dp),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Fondo seleccionado: círculo glass blanco 20%
        if (selected) {
            Box(
                modifier = Modifier
                    .size(animSize)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.20f))
            )
        }
        Text(
            text = display,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.70f),
            fontSize = if (selected) 12.sp else 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/* ════════════════════════════════════════════════════════════════
 *  C · MODE SHIFTER CAROUSEL  (FOTO · VIDEO con máscara alpha)
 *  Centro a 148pt del borde inferior
 * ════════════════════════════════════════════════════════════════ */
@Composable
private fun ModeShifterCarousel(
    currentMode: String,
    palette: GlassPalette,
    onModeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = listOf("FOTO", "VIDEO")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        modes.forEachIndexed { idx, mode ->
            val selected = mode == currentMode
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectPressedCompat()
            val scale by animateFloatAsState(
                targetValue = if (pressed) 0.92f else 1f,
                animationSpec = spring(stiffness = 160f, dampingRatio = 0.7f),
                label = "mode_scale"
            )

            Box(
                modifier = Modifier
                    .padding(horizontal = 18.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clickable(
                        interactionSource = interaction,
                        indication = ripple(bounded = false, radius = 32.dp),
                        onClick = { if (!selected) onModeChange(mode) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = mode,
                    color = if (selected) Color(0xFFFFCC00)         // amarillo acento iOS
                            else Color.White.copy(alpha = 0.60f),
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    letterSpacing = 1.2.sp
                )
            }
        }
    }
}

/* ════════════════════════════════════════════════════════════════
 *  D · BOTTOM ACTION BLOCK  (Gallery · Shutter · Flip)
 * ════════════════════════════════════════════════════════════════ */
@Composable
private fun BottomActionBlock(
    isRecording: Boolean,
    isVideoMode: Boolean,
    onShutterTap: () -> Unit,
    onFlipCamera: () -> Unit,
    onOpenGallery: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // ── Gallery (50pt círculo)
        GalleryButton(onClick = onOpenGallery)

        // ── Shutter (76pt total) ──
        LiquidShutter(
            isRecording = isRecording,
            isVideoMode = isVideoMode,
            onTap = onShutterTap
        )

        // ── Flip camera (50pt círculo)
        FlipCameraButton(onClick = onFlipCamera)
    }
}

@Composable
private fun LiquidShutter(
    isRecording: Boolean,
    isVideoMode: Boolean,
    onTap: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedCompat()

    // Capa C (núcleo morphing)
    val innerSize by animateDpAsState(
        targetValue = when {
            isRecording -> 30.dp
            isVideoMode -> 34.dp
            else -> 60.dp
        },
        animationSpec = spring(stiffness = 300f, dampingRatio = 0.62f),
        label = "shutter_inner_size"
    )
    val innerRadius by animateDpAsState(
        targetValue = when {
            isRecording -> 6.dp        // cuadrado redondeado fuerte (stop)
            isVideoMode -> 10.dp       // squircle
            else -> 30.dp              // círculo
        },
        animationSpec = spring(stiffness = 300f, dampingRatio = 0.62f),
        label = "shutter_inner_radius"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(stiffness = 300f, dampingRatio = 0.78f),
        label = "shutter_press_scale"
    )

    val innerColor = if (isVideoMode || isRecording) Color(0xFFFF3B30) else Color.White

    Box(
        modifier = Modifier
            .size(76.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = 38.dp),
                onClick = onTap
            ),
        contentAlignment = Alignment.Center
    ) {
        // Capa A — Anillo exterior 76pt × 4.5pt blanco
        // FIX v4.0.1: la extensión ya devuelve Modifier, encadenamos directamente.
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
                .androidx_border_white_ring(4.5f)
        )
        // Capa B (3.5pt transparente) implícita: el inner está separado del anillo
        // Capa C — Núcleo
        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(RoundedCornerShape(innerRadius))
                .background(innerColor)
        )
    }
}

@Composable
private fun GalleryButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedCompat()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        animationSpec = spring(stiffness = 300f, dampingRatio = 0.78f),
        label = "gallery_scale"
    )
    Box(
        modifier = Modifier
            .size(50.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .liquidGlassModifier(
                shape = RoundedCornerShape(14.dp),
                blurRadiusDp = 16.dp
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        LensIcon(
            icon = Icons.Rounded.Collections,
            tint = Color.White.copy(alpha = 0.95f),
            size = 22.dp
        )
    }
}

@Composable
private fun FlipCameraButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedCompat()
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        animationSpec = spring(stiffness = 300f, dampingRatio = 0.7f),
        label = "flip_scale"
    )
    Box(
        modifier = Modifier
            .size(50.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .liquidGlassModifier(
                shape = CircleShape,
                blurRadiusDp = 16.dp
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = 25.dp),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        LensIcon(
            icon = Icons.Rounded.Cameraswitch,
            tint = Color.White,
            size = 24.dp
        )
    }
}

/* ════════════════════════════════════════════════════════════════
 *  E · PRO PEEK PANEL  (Modal sheet desde abajo, 280pt alto)
 * ════════════════════════════════════════════════════════════════ */
@Composable
private fun ProPeekPanel(
    visible: Boolean,
    videoRes: String,
    videoFps: Int,
    isVideoMode: Boolean,
    onCycleResolution: () -> Unit,
    onCycleFps: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Backdrop oscuro al tocar fuera
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )

            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = spring(stiffness = 220f, dampingRatio = 0.78f)
                ) + fadeIn(tween(150)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(200)
                ) + fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                LiquidGlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    cornerRadiusTop = 32.dp,
                    cornerRadiusBottom = 0.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 22.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        // Drag indicator
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(width = 38.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.25f))
                        )

                        Text(
                            text = "Ajustes Pro",
                            color = Color(0xFFF5F5F7),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )

                        // Grid 2x3
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                ProPeekCell(
                                    title = "Resolución",
                                    value = when (videoRes) {
                                        "UHD" -> "4K"; "HD" -> "HD"; else -> "FHD"
                                    },
                                    enabled = isVideoMode,
                                    onClick = onCycleResolution,
                                    modifier = Modifier.weight(1f)
                                )
                                ProPeekCell(
                                    title = "FPS",
                                    value = videoFps.toString(),
                                    enabled = isVideoMode,
                                    onClick = onCycleFps,
                                    modifier = Modifier.weight(1f)
                                )
                                ProPeekCell(
                                    title = "Modo",
                                    value = if (isVideoMode) "VIDEO" else "FOTO",
                                    enabled = false,
                                    onClick = {},
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                ProPeekCell(
                                    title = "Códec",
                                    value = "H.265",
                                    enabled = false,
                                    onClick = {},
                                    modifier = Modifier.weight(1f)
                                )
                                ProPeekCell(
                                    title = "HDR",
                                    value = "Auto",
                                    enabled = false,
                                    onClick = {},
                                    modifier = Modifier.weight(1f)
                                )
                                ProPeekCell(
                                    title = "Cuadrícula",
                                    value = "Off",
                                    enabled = false,
                                    onClick = {},
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        Text(
                            text = "Toca fuera para cerrar",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProPeekCell(
    title: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedCompat()
    val scale by animateFloatAsState(
        if (pressed && enabled) 0.94f else 1f,
        animationSpec = spring(stiffness = 300f, dampingRatio = 0.78f),
        label = "cell_scale"
    )
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                alpha = if (enabled) 1f else 0.45f
            }
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.70f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

/* ════════════════════════════════════════════════════════════════
 *  Helpers
 * ════════════════════════════════════════════════════════════════ */
private fun buildLensInfoFromLabel(label: String): LensInfo {
    val isOpticalGuess = label == "3x" || label == "2x" || label == "5x" || label == "10x"
    return LensInfo(
        id = "",
        label = label,
        focalLength = 0f,
        aperture = 0f,
        isPhysical = false,
        isOptical = isOpticalGuess
    )
}

fun formatTimestamp(seconds: Long): String {
    val minutes = seconds / 60
    val secs    = seconds % 60
    return "%02d:%02d".format(minutes, secs)
}

@Composable
private fun MutableInteractionSource.collectPressedCompat(): androidx.compose.runtime.State<Boolean> {
    val isPressed = remember { mutableStateOf(false) }
    LaunchedEffect(this) {
        val active = ArrayList<androidx.compose.foundation.interaction.PressInteraction.Press>()
        interactions.collect { interaction ->
            when (interaction) {
                is androidx.compose.foundation.interaction.PressInteraction.Press -> active.add(interaction)
                is androidx.compose.foundation.interaction.PressInteraction.Release -> active.remove(interaction.press)
                is androidx.compose.foundation.interaction.PressInteraction.Cancel -> active.remove(interaction.press)
            }
            isPressed.value = active.isNotEmpty()
        }
    }
    return isPressed
}

/* Extensión privada para el anillo blanco del shutter (4.5pt stroke)
 * FIX v4.0.1: `border` es una extensión de Modifier — debe llamarse
 * sobre el receiver (this.border(...)). Antes se invocaba como función
 * top-level (androidx.compose.foundation.border(...)) y el compilador
 * la marcaba como Unresolved reference. */
private fun Modifier.androidx_border_white_ring(strokeDp: Float): Modifier =
    this.border(
        width = strokeDp.dp,
        color = Color.White,
        shape = CircleShape
    )
