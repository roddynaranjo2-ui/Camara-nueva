package com.rodyto.lenspro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ================================================================
 *  ActionChipBar.kt · v4.0
 *
 *  El rediseño Liquid Glass iOS 26 ya no usa la barra de chips de
 *  acciones (flash, HDR, RAW, timer, sonido, aspect, more, settings).
 *
 *  Conservamos `ActionChipBar` y `ModeSelectorIos` como composables
 *  stub para mantener compatibilidad binaria con cualquier código
 *  legacy que aún los referencie (ej. SettingsActivity), pero
 *  visualmente quedan minimalistas.
 *
 *  Toda la UI principal está ahora en MainActivityHelpers.kt
 *  (LiquidGlassUiLayer).
 * ================================================================ */

@Composable
fun ActionChipBar(
    palette: GlassPalette,
    flashMode: FlashMode,
    onToggleFlash: () -> Unit,
    hdrOn: Boolean,
    onToggleHdr: () -> Unit,
    timerSec: Int,
    onCycleTimer: () -> Unit,
    soundOn: Boolean,
    onToggleSound: () -> Unit,
    aspectLabel: String,
    onCycleAspect: () -> Unit,
    onOpenMore: () -> Unit,
    modifier: Modifier = Modifier,
    rawOn: Boolean = false,
    onToggleRaw: () -> Unit = {},
    onOpenSettings: () -> Unit = onOpenMore
) {
    // No-op en v4.0: la barra superior ahora es QuickSettingsIsland.
    Box(modifier = modifier)
}

@Composable
fun ModeSelectorIos(
    mode: String,
    palette: GlassPalette,
    onModeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    modes: List<String> = listOf("FOTO", "VIDEO")
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        modes.forEach { item ->
            val selected = mode == item
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (selected) Color.White.copy(alpha = 0.25f) else Color.Transparent
                    )
                    .clickable { onModeChange(item) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
