package com.rodyto.lenspro

import android.content.Intent
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.GridOff
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Timer10
import androidx.compose.material.icons.rounded.Timer3
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rodyto.lenspro.settings.SettingsRepository
import com.rodyto.lenspro.ui.theme.GlassPalette
import com.rodyto.lenspro.ui.components.*
import com.rodyto.lenspro.util.*
import kotlinx.coroutines.launch

/* ================================================================
 *  LiquidGlassUiLayer · v6.0 Premium iOS 26
 *
 *  v6.0 — Cambios sobre v5.0:
 *   • Flash chip funcional en QuickSettingsIsland (OFF/AUTO/ON).
 *   • Timer chip funcional (Off/3s/10s).
 *   • Grid chip funcional.
 *   • Gallery button realmente abre la galería (GalleryLauncher).
 *   • Flip camera funcional (sin bug).
 *   • ProPeek panel completamente rediseñado:
 *      - Solo ajustes conectados (no celdas deshabilitadas).
 *      - Acceso directo a Ajustes avanzados (icono Tune).
 *      - Cierre por gesto + backdrop.
 * ================================================================ */

@Composable
fun LiquidGlassUiLayer(
    viewModel: CameraControlViewModel,
    palette: GlassPalette,
    repo: SettingsRepository
) {
    val view = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val cameraMode by viewModel.cameraMode.collectAsStateWithLifecycle()
    val recording by viewModel.isRecording.collectAsStateWithLifecycle()
    val flashMode by viewModel.flashMode.collectAsStateWithLifecycle()
    val timerSec by viewModel.timerSeconds.collectAsStateWithLifecycle()
    val gridOn by viewModel.gridEnabled.collectAsStateWithLifecycle()
    val flashSupported by viewModel.isFlashSupported.collectAsStateWithLifecycle()

    val lensLabel by repo.lastLens.collectAsStateWithLifecycle(initialValue = "1x")
    val hapticsOn by repo.hapticsEnabled.collectAsStateWithLifecycle(initialValue = true)
    val videoRes by repo.videoResolution.collectAsStateWithLifecycle(initialValue = "FHD")
    val videoFps by repo.videoFps.collectAsStateWithLifecycle(initialValue = 30)

    var showProPeek by remember { mutableStateOf(false) }

    val haptic: () -> Unit = remember(hapticsOn) {
        { if (hapticsOn) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    }

    val isVideoMode = cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.PRO_VIDEO

    Box(modifier = Modifier.fillMaxSize()) {

        // ─── CAPA 1 · QUICK SETTINGS ISLAND ──────────────────────
        QuickSettingsIsland(
            isVideoMode = isVideoMode,
            videoRes = videoRes,
            videoFps = videoFps,
            flashMode = flashMode,
            flashSupported = flashSupported,
            timerSec = timerSec,
            gridOn = gridOn,
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
            onCycleFlash = {
                haptic()
                val next = when (flashMode) {
                    FlashMode.OFF -> FlashMode.AUTO
                    FlashMode.AUTO -> FlashMode.ON
                    FlashMode.ON -> FlashMode.OFF
                }
                scope.launch { repo.setFlashMode(next.name) }
            },
            onCycleTimer = {
                haptic()
                val next = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
                scope.launch { repo.setTimer(next) }
            },
            onToggleGrid = {
                haptic()
                scope.launch { repo.setGrid(!gridOn) }
            },
            onOpenSettings = { haptic(); showProPeek = true },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // ─── CAPA 2 · LENS DIAL ──────────────────────────────────
        LensDial(
    currentLens = lensLabel,
    onSelect = { lens ->
        haptic()
        scope.launch { repo.setLastLens(lens) }
        // BUG-A3: resolver la lente real (con id de HAL) en lugar de id=""
        resolveLensForLabel(viewModel, lens)?.let { real ->
            viewModel.setLens(real)
        }
    },
    modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = 224.dp)
)


        // ─── CAPA 3 · MODE SHIFTER CAROUSEL ──────────────────────
        ModeShifterCarousel(
            currentMode = if (isVideoMode) "VIDEO" else "FOTO",
            onModeChange = { newLabel ->
                haptic()
                val newMode = if (newLabel == "VIDEO") CameraMode.VIDEO else CameraMode.PHOTO
                viewModel.setCameraMode(newMode)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 138.dp)
        )

        // ─── CAPA 4 · BOTTOM ACTION BLOCK ────────────────────────
        BottomActionBlock(
            isRecording = recording,
            isVideoMode = isVideoMode,
            onShutterTap = {
                haptic()
                if (isVideoMode) viewModel.toggleRecording() else viewModel.takePhoto()
            },
            onFlipCamera = { haptic(); viewModel.switchCamera() },
            onOpenGallery = { haptic(); GalleryLauncher.openGallery(context) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 38.dp)
        )

        // ─── PRO PEEK PANEL (Modal Sheet) ────────────────────────
        ProPeekPanel(
            visible = showProPeek,
            isVideoMode = isVideoMode,
            videoRes = videoRes,
            videoFps = videoFps,
            flashMode = flashMode,
            flashSupported = flashSupported,
            timerSec = timerSec,
            gridOn = gridOn,
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
            onCycleFlash = {
                haptic()
                val next = when (flashMode) {
                    FlashMode.OFF -> FlashMode.AUTO
                    FlashMode.AUTO -> FlashMode.ON
                    FlashMode.ON -> FlashMode.OFF
                }
                scope.launch { repo.setFlashMode(next.name) }
            },
            onCycleTimer = {
                haptic()
                val next = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
                scope.launch { repo.setTimer(next) }
            },
            onToggleGrid = {
                haptic()
                scope.launch { repo.setGrid(!gridOn) }
            },
            onOpenAdvanced = {
                haptic()
                showProPeek = false
                context.startActivity(Intent(context, SettingsActivity::class.java))
            },
            onDismiss = { showProPeek = false }
        )
    }
}

/* ════════════════════════════════════════════════════════════════
 *  A · QUICK SETTINGS ISLAND
 * ════════════════════════════════════════════════════════════════ */
@Composable
private fun QuickSettingsIsland(
    isVideoMode: Boolean,
    videoRes: String,
    videoFps: Int,
    flashMode: FlashMode,
    flashSupported: Boolean,
    timerSec: Int,
    gridOn: Boolean,
    onCycleResolution: () -> Unit,
    onCycleFps: () -> Unit,
    onCycleFlash: () -> Unit,
    onCycleTimer: () -> Unit,
    onToggleGrid: () -> Unit,
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
        LiquidGlassPill(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModeChipIndicator(isVideoMode = isVideoMode)

                // Flash chip (siempre visible si hay flash)
                if (flashSupported) {
                    IslandIconButton(
                        icon = when (flashMode) {
                            FlashMode.ON -> Icons.Rounded.FlashOn
                            FlashMode.AUTO -> Icons.Rounded.FlashAuto
                            FlashMode.OFF -> Icons.Rounded.FlashOff
                        },
                        active = flashMode != FlashMode.OFF,
                        contentDescription = "Flash",
                        onClick = onCycleFlash
                    )
                }

                // Timer chip
                IslandIconButton(
                    icon = when (timerSec) {
                        3 -> Icons.Rounded.Timer3
                        10 -> Icons.Rounded.Timer10
                        else -> Icons.Rounded.TimerOff
                    },
                    active = timerSec > 0,
                    contentDescription = "Timer",
                    onClick = onCycleTimer
                )

                // Grid chip
                IslandIconButton(
                    icon = if (gridOn) Icons.Rounded.GridOn else Icons.Rounded.GridOff,
                    active = gridOn,
                    contentDescription = "Cuadrícula",
                    onClick = onToggleGrid
                )

                AnimatedVisibility(
                    visible = isVideoMode,
                    enter = fadeIn(tween(180)),
                    exit  = fadeOut(tween(140))
                ) {
                    PillTextButton(
                        label = when (videoRes) { "UHD" -> "4K"; "HD" -> "HD"; else -> "FHD" },
                        onClick = onCycleResolution,
                        active = videoRes == "UHD"
                    )
                }

                AnimatedVisibility(
                    visible = isVideoMode,
                    enter = fadeIn(tween(180)),
                    exit  = fadeOut(tween(140))
                ) {
                    PillTextButton(
                        label = "$videoFps",
                        suffix = "FPS",
                        onClick = onCycleFps,
                        active = videoFps == 60
                    )
                }

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
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        LensIcon(icon = icon, tint = Color.White.copy(alpha = 0.95f), size = 16.dp)
        Text(
            text = if (isVideoMode) "VIDEO" else "FOTO",
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp
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
        animationSpec = Spring26.button(),
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
                else Color.White.copy(alpha = 0.10f)
            )
            .border(
                width = 0.5.dp,
                color = Color.White.copy(alpha = if (active) 0.25f else 0.10f),
                shape = RoundedCornerShape(16.dp)
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
    onClick: () -> Unit,
    active: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedCompat()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = Spring26.button(),
        label = "icon_btn_scale"
    )
    Box(
        modifier = Modifier
            .size(40.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(
                if (active) Color(0xFFFFCC00).copy(alpha = 0.22f)
                else Color.Transparent
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = 20.dp),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        LensIcon(
            icon = icon,
            contentDescription = contentDescription,
            tint = if (active) Color(0xFFFFCC00) else Color.White,
            size = 20.dp
        )
    }
}

/* ════════════════════════════════════════════════════════════════
 *  B · LENS DIAL
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
            .liquidGlassModifier(shape = RoundedCornerShape(21.dp))
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
    val bubbleSize by animateDpAsState(
        targetValue = if (selected) 32.dp else 26.dp,
        animationSpec = Spring26.button(),
        label = "lens_bubble_size"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = Spring26.button(),
        label = "lens_press_scale"
    )

    Box(
        modifier = Modifier
            .size(32.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = 16.dp),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(bubbleSize)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.20f))
                    .border(
                        width = 0.5.dp,
                        color = Color.White.copy(alpha = 0.30f),
                        shape = CircleShape
                    )
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
 *  C · MODE SHIFTER CAROUSEL
 * ════════════════════════════════════════════════════════════════ */
@Composable
private fun ModeShifterCarousel(
    currentMode: String,
    onModeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = listOf("FOTO", "VIDEO")
    val density = LocalDensity.current
    val fadePx = with(density) { 60.dp.toPx() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.horizontalGradient(
                        0.00f to Color.Transparent,
                        (fadePx / size.width).coerceIn(0f, 0.5f) to Color.Black,
                        (1f - fadePx / size.width).coerceIn(0.5f, 1f) to Color.Black,
                        1.00f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        modes.forEach { mode ->
            val selected = mode == currentMode
            val interaction = remember(mode) { MutableInteractionSource() }
            val pressed by interaction.collectPressedCompat()
            val scale by animateFloatAsState(
                targetValue = if (pressed) 0.92f else 1f,
                animationSpec = Spring26.carousel(),
                label = "mode_scale_$mode"
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
                    color = if (selected) Color(0xFFFFCC00)
                            else Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    letterSpacing = 1.4.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/* ════════════════════════════════════════════════════════════════
 *  D · BOTTOM ACTION BLOCK
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
        GalleryButton(onClick = onOpenGallery)
        LiquidShutter(
            isRecording = isRecording,
            isVideoMode = isVideoMode,
            onTap = onShutterTap
        )
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
    val innerSize by animateDpAsState(
        targetValue = when {
            isRecording -> 30.dp
            isVideoMode -> 34.dp
            else        -> 60.dp
        },
        animationSpec = Spring26.shutterMorph(),
        label = "shutter_inner_size"
    )
    val innerRadius by animateDpAsState(
        targetValue = when {
            isRecording -> 6.dp
            isVideoMode -> 10.dp
            else        -> 30.dp
        },
        animationSpec = Spring26.shutterMorph(),
        label = "shutter_inner_radius"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = Spring26.button(),
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
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
                .border(width = 4.5.dp, color = Color.White, shape = CircleShape)
        )
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
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = Spring26.button(),
        label = "gallery_scale"
    )
    Box(
        modifier = Modifier
            .size(50.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .liquidGlassModifier(shape = RoundedCornerShape(14.dp))
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
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = Spring26.button(),
        label = "flip_scale"
    )
    Box(
        modifier = Modifier
            .size(50.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .liquidGlassModifier(shape = CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = 25.dp),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        LensIcon(icon = Icons.Rounded.Cameraswitch, tint = Color.White, size = 24.dp)
    }
}

/* ════════════════════════════════════════════════════════════════
 *  E · PRO PEEK PANEL (rediseñado v6.0)
 *  Solo ajustes funcionales + acceso a Settings avanzados.
 * ════════════════════════════════════════════════════════════════ */
@Composable
private fun ProPeekPanel(
    visible: Boolean,
    isVideoMode: Boolean,
    videoRes: String,
    videoFps: Int,
    flashMode: FlashMode,
    flashSupported: Boolean,
    timerSec: Int,
    gridOn: Boolean,
    onCycleResolution: () -> Unit,
    onCycleFps: () -> Unit,
    onCycleFlash: () -> Unit,
    onCycleTimer: () -> Unit,
    onToggleGrid: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.38f))
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
                    animationSpec = Spring26.panel()
                ) + fadeIn(tween(160)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = Spring26.panel() // FIX V-03: Usar spring también al salir para consistencia
                ) + fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                LiquidGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadiusTop = 32.dp,
                    cornerRadiusBottom = 0.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Drag indicator
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(width = 38.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.25f))
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Ajustes rápidos",
                                color = Color(0xFFF5F5F7),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            // Acceso a Settings avanzados
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.10f))
                                    .clickable(onClick = onOpenAdvanced),
                                contentAlignment = Alignment.Center
                            ) {
                                LensIcon(
                                    icon = Icons.Rounded.Tune,
                                    tint = Color.White,
                                    size = 18.dp
                                )
                            }
                        }

                        // ─── Filas funcionales (solo lo que existe) ──
                        // Flash
                        if (flashSupported) {
                            ProPeekCell(
                                title = "Flash",
                                value = when (flashMode) {
                                    FlashMode.ON -> "Encendido"
                                    FlashMode.AUTO -> "Automático"
                                    FlashMode.OFF -> "Apagado"
                                },
                                icon = when (flashMode) {
                                    FlashMode.ON -> Icons.Rounded.FlashOn
                                    FlashMode.AUTO -> Icons.Rounded.FlashAuto
                                    FlashMode.OFF -> Icons.Rounded.FlashOff
                                },
                                active = flashMode != FlashMode.OFF,
                                onClick = onCycleFlash
                            )
                        }

                        // Timer
                        ProPeekCell(
                            title = "Temporizador",
                            value = when (timerSec) {
                                3 -> "3 segundos"
                                10 -> "10 segundos"
                                else -> "Apagado"
                            },
                            icon = when (timerSec) {
                                3 -> Icons.Rounded.Timer3
                                10 -> Icons.Rounded.Timer10
                                else -> Icons.Rounded.TimerOff
                            },
                            active = timerSec > 0,
                            onClick = onCycleTimer
                        )

                        // Grid
                        ProPeekCell(
                            title = "Cuadrícula 3×3",
                            value = if (gridOn) "Activada" else "Desactivada",
                            icon = if (gridOn) Icons.Rounded.GridOn else Icons.Rounded.GridOff,
                            active = gridOn,
                            onClick = onToggleGrid
                        )

                        // Solo en vídeo
                        if (isVideoMode) {
                            ProPeekCell(
                                title = "Resolución de vídeo",
                                value = when (videoRes) {
                                    "UHD" -> "4K · Ultra HD"
                                    "HD" -> "HD · 720p"
                                    else -> "FHD · 1080p"
                                },
                                icon = Icons.Rounded.Videocam,
                                active = videoRes == "UHD",
                                onClick = onCycleResolution
                            )
                            ProPeekCell(
                                title = "Frecuencia",
                                value = "$videoFps fps",
                                icon = Icons.Rounded.Tune,
                                active = videoFps == 60,
                                onClick = onCycleFps
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Más opciones en Ajustes avanzados (⚙️ arriba a la derecha)",
                            color = Color.White.copy(alpha = 0.50f),
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        Spacer(modifier = Modifier.navigationBarsPadding())
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
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedCompat()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = Spring26.button(),
        label = "cell_scale"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (active) Color(0xFFFFCC00).copy(alpha = 0.14f)
                else Color.White.copy(alpha = 0.08f)
            )
            .border(
                width = 0.5.dp,
                color = Color.White.copy(alpha = if (active) 0.22f else 0.10f),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    if (active) Color(0xFFFFCC00).copy(alpha = 0.25f)
                    else Color.White.copy(alpha = 0.12f)
                ),
            contentAlignment = Alignment.Center
        ) {
            LensIcon(
                icon = icon,
                tint = if (active) Color(0xFFFFCC00) else Color.White,
                size = 18.dp
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = value,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/* ════════════════════════════════════════════════════════════════
 *  Helpers
 * ════════════════════════════════════════════════════════════════ */
/* ════════════════════════════════════════════════════════════════
 *  Helpers — v6.1
 *
 *  FIX BUG-A3: ahora resolveLensForLabel busca en availableBackLenses /
 *  availableFrontLenses del ViewModel y devuelve la LensInfo REAL con
 *  su cameraId del HAL. Si no encuentra coincidencia, retorna la primera
 *  lente disponible del set actual (no un id="" como antes).
 * ════════════════════════════════════════════════════════════════ */
private fun resolveLensForLabel(
    viewModel: CameraControlViewModel,
    label: String
): LensInfo? {
    val set = if (viewModel.isFrontCamera.value)
        viewModel.availableFrontLenses.value
    else
        viewModel.availableBackLenses.value
    return set.firstOrNull { it.label == label }
        ?: set.firstOrNull()
}
