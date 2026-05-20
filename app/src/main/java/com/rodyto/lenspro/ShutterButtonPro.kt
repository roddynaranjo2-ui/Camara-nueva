package com.rodyto.lenspro

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/* ================================================================
 *  ShutterButtonPro.kt · v4.0
 *
 *  El nuevo shutter (LiquidShutter) está en MainActivityHelpers.kt.
 *  Este archivo queda como stub para preservar compatibilidad con
 *  cualquier referencia legacy (p. ej. ShutterGlass).
 * ================================================================ */
@Composable
fun ShutterButtonPro(
    isRecording: Boolean,
    mode: String,
    onTap: () -> Unit,
    onSwipeToVideo: () -> Unit,
    onSwipeToPhoto: () -> Unit,
    onPressFeedback: () -> Unit = {},
    size: Dp = 76.dp
) {
    Box(modifier = Modifier)
}
