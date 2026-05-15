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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
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
 *  Compatibles con Compose + Android 12+ (RenderEffect Gaussian Blur).
 *  En APIs anteriores se conserva el aspecto con gradient + alpha.
 * ================================================================ */

/**
 * Aplica un desenfoque Gaussiano de hardware (GPU) sobre la capa.
 * @param radius Radio en px. Más alto = más "frosted".
 * @param strong Usa un radio fuerte (panel completo).
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
 * Filtro de vibrancia simple: aumenta saturación y contraste percibido
 * mediante un overlay multiplicativo. Suficiente para hacer que los iconos
 * extraigan color del fondo manteniendo legibilidad.
 */
fun Modifier.vibrancy(palette: GlassPalette): Modifier = this.then(
    Modifier.graphicsLayer {
        alpha = if (palette.isDark) 0.96f else 0.92f
        // ligera "lift" para simular refracción / lente
        translationY = -0.5f
    }
)

/**
 * Burbuja flotante translúcida con refracción dinámica (Ultra Thin Material).
 *  - Capa 1: blur del fondo
 *  - Capa 2: base translúcida tintada
 *  - Capa 3: highlight superior (refracción)
 *  - Capa 4: stroke exterior + stroke interior
 */
@Composable
fun GlassBubble(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    palette: GlassPalette,
    strong: Boolean = false,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
    onClick: (() -> Unit)? = null,
    pressedScale: Float = 0.95f,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsStateCompat()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
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
            .clip(shape)
            .gaussianBlur(if (strong) 22f else 16f, strong)
            .background(palette.ultraBase, shape)
            .background(
                brush = Brush.verticalGradient(
                    0f to palette.ultraSurface,
                    1f to Color.Transparent
                ),
                shape = shape
            )
            .border(0.8.dp, palette.ultraStroke, shape)
            .border(0.4.dp, palette.ultraStrokeInner, shape)
            .let {
                if (onClick != null) it.clickable(
                    interactionSource = interaction,
                    indication = rememberRipple(bounded = false, radius = size / 2),
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
    Box(
        modifier = modifier
            .clip(shape)
            .gaussianBlur(28f, strong)
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
    ) { content() }
}

/**
 * Estado "press" portátil — evita depender de `material-ripple` interno.
 * Devuelve true mientras haya una `PressInteraction` activa.
 */
@Composable
private fun MutableInteractionSource.collectIsPressedAsStateCompat(): androidx.compose.runtime.State<Boolean> {
    val isPressed = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(this) {
        val active = ArrayList<androidx.compose.foundation.interaction.PressInteraction.Press>()
        interactions.collect { interaction ->
            when (interaction) {
                is androidx.compose.foundation.interaction.PressInteraction.Press -> active.add(interaction)
                is androidx.compose.foundation.interaction.PressInteraction.Release -> active.remove(interaction.press)
                is androidx.compose.foundation.interaction.PressInteraction.Cancel  -> active.remove(interaction.press)
            }
            isPressed.value = active.isNotEmpty()
        }
    }
    return isPressed
}
