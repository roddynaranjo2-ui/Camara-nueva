package com.rodyto.lenspro

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
// ─── FIX ──────────────────────────────────────────────────────────────────────
// rememberRipple fue deprecado a nivel ERROR en Compose Foundation 1.7.x
// (BOM 2024.09.03). Reemplazado por ripple() de Material3 1.3.0+.
import androidx.compose.material3.ripple
// ──────────────────────────────────────────────────────────────────────────────
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/* ================================================================
 *  Liquid Glass / Ultra Thin Material — primitivas reutilizables
 * ================================================================ */

/**
 * Aplica un desenfoque Gaussiano de hardware (GPU) sobre la capa.
 * Soportado en Android 12+ (API 31). En APIs anteriores se omite (sólo color).
 */
fun Modifier.gaussianBlur(radius: Float = 18f, strong: Boolean = false): Modifier =
    this.then(Modifier.graphicsLayer {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val r = if (strong) (radius * 1.6f) else radius
            renderEffect = RenderEffect
                .createBlurEffect(r, r, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
    })

/**
 * Modifier helper para aplicar el "Liquid Glass" completo a cualquier surface.
 * Combina base translúcida + gradient highlight + doble borde (outer + inner stroke).
 */
fun Modifier.liquidGlass(
    palette: GlassPalette,
    shape: androidx.compose.ui.graphics.Shape,
    blurRadius: Float = 18f,
    strong: Boolean = false
): Modifier = this
    .clip(shape)
    .gaussianBlur(blurRadius, strong)
    .background(palette.ultraBase, shape)
    .background(
        brush = Brush.verticalGradient(
            0f to palette.ultraSurface,
            0.6f to Color.Transparent,
            1f to palette.ultraStrokeInner
        ),
        shape = shape
    )
    .border(0.8.dp, palette.ultraStroke, shape)
    .border(0.4.dp, palette.ultraStrokeInner, shape)

/**
 * Burbuja Liquid Glass circular con press-scale (squishy iOS).
 */
@Composable
fun GlassBubble(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    palette: GlassPalette,
    strong: Boolean = false,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
    onClick: (() -> Unit)? = null,
    pressedScale: Float = 0.93f,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsStateCompat()
    val scale by animateFloatAsState(
        targetValue = if (pressed.value) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "bubble_press"
    )
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .liquidGlass(palette, shape, if (strong) 22f else 16f, strong)
            .let {
                if (onClick != null) it.clickable(
                    interactionSource = interaction,
                    // FIX: ripple() reemplaza a rememberRipple() (Compose Foundation 1.7+)
                    indication = ripple(bounded = false, radius = size / 2),
                    onClick = onClick
                ) else it
            },
        contentAlignment = Alignment.Center
    ) { content() }
}

/** Panel de Ultra Thin Material con esquinas redondeadas. */
@Composable
fun UltraThinPanel(
    modifier: Modifier = Modifier,
    palette: GlassPalette,
    cornerRadius: Dp = 24.dp,
    strong: Boolean = true,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(modifier = modifier.liquidGlass(palette, shape, 28f, strong)) { content() }
}

/**
 * Estado "press" portátil — devuelve true mientras haya una PressInteraction activa.
 */
@Composable
private fun MutableInteractionSource.collectIsPressedAsStateCompat(): androidx.compose.runtime.State<Boolean> {
    val isPressed = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(this) {
        val active = ArrayList<androidx.compose.foundation.interaction.PressInteraction.Press>()
        interactions.collect { interaction ->
            when (interaction) {
                is androidx.compose.foundation.interaction.PressInteraction.Press   -> active.add(interaction)
                is androidx.compose.foundation.interaction.PressInteraction.Release -> active.remove(interaction.press)
                is androidx.compose.foundation.interaction.PressInteraction.Cancel  -> active.remove(interaction.press)
            }
            isPressed.value = active.isNotEmpty()
        }
    }
    return isPressed
}
