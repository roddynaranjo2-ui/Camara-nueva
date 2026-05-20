package com.rodyto.lenspro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.rodyto.lenspro.ui.theme.GlassPalette
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/* ================================================================
 *  GlassUi.kt · v4.0  (compatibilidad legacy)
 *
 *  El motor Liquid Glass real ahora vive en LiquidGlass.kt.
 *  Conservamos aquí los composables y modifiers legacy como
 *  pass-through o stubs no-op para mantener compatibilidad con
 *  archivos no migrados (CameraXBridge, SettingsActivity, etc.).
 * ================================================================ */

fun Modifier.gaussianBlur(radius: Float = 18f, strong: Boolean = false): Modifier = this

fun Modifier.frostedBackdrop(
    palette: GlassPalette,
    shape: Shape,
    intensity: Float = 0.6f
): Modifier = this
    .clip(shape)
    .background(
        if (palette.isDark) Color.Black.copy(alpha = 0.30f * intensity + 0.25f)
        else Color.White.copy(alpha = 0.30f * intensity + 0.30f),
        shape
    )

fun Modifier.liquidGlass(
    palette: GlassPalette,
    shape: Shape,
    blurRadius: Float = 0f,
    strong: Boolean = false,
    accentBorder: Boolean = false
): Modifier = this.liquidGlassModifier(
    shape = shape,
    blurRadiusDp = 20.dp,
    borderEnabled = true
)

fun Modifier.whiteGlow(
    active: Boolean,
    shape: Shape,
    intensity: Float = 1f
): Modifier = this

@Composable
fun GlassBubble(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    palette: GlassPalette,
    strong: Boolean = false,
    shape: Shape = CircleShape,
    onClick: (() -> Unit)? = null,
    pressedScale: Float = 0.95f,
    activeGlow: Boolean = false,
    frostBackdrop: Boolean = false,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .liquidGlassModifier(shape = shape),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        content()
    }
}

@Composable
fun UltraThinPanel(
    modifier: Modifier = Modifier,
    palette: GlassPalette,
    cornerRadius: Dp = 32.dp,
    strong: Boolean = true,
    frostBackdrop: Boolean = true,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier.liquidGlassModifier(shape = shape)
    ) { content() }
}

@Composable
fun ShutterGlass(
    isRecording: Boolean,
    mode: String,
    onTap: () -> Unit,
    onSwipeToVideo: () -> Unit,
    onSwipeToPhoto: () -> Unit,
    onPressFeedback: () -> Unit = {},
    palette: GlassPalette,
    size: Dp = 84.dp
) {
    Box(modifier = Modifier.size(size))
}

@Composable
fun ExposureSliderEv(
    valueEv: Float,
    rangeEv: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    palette: GlassPalette,
    onHaptic: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier)
}
