package com.rodyto.lenspro.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodyto.lenspro.FlashMode
import com.rodyto.lenspro.LensIcon
import com.rodyto.lenspro.LiquidGlassPill

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
