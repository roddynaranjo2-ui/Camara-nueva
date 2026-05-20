package com.rodyto.lenspro.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodyto.lenspro.FlashMode
import com.rodyto.lenspro.ui.components.LensIcon
import com.rodyto.lenspro.ui.components.LiquidGlassPill


/**
 * QuickSettingsIsland — Componente de ajustes rápidos en la parte superior.
 * Implementación Premium con Liquid Glass y animaciones.
 */
@Composable
fun QuickSettingsIsland(
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

                // Flash chip
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
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

// ─── Composables auxiliares locales ──────────────────────────────
// (Duplicados aquí para evitar dependencia cross-package con MainActivityHelpers)

@Composable
private fun MutableInteractionSource.collectPressedCompat(): androidx.compose.runtime.State<Boolean> {
    val isPressed = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(this) {
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

@Composable
private fun PillTextButton(
    label: String,
    suffix: String? = null,
    onClick: () -> Unit,
    active: Boolean = false
) {
    val interaction = androidx.compose.runtime.remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedCompat()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = com.rodyto.lenspro.util.Spring26.button(),
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    val interaction = androidx.compose.runtime.remember { MutableInteractionSource() }
    val pressed by interaction.collectPressedCompat()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = com.rodyto.lenspro.util.Spring26.button(),
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
