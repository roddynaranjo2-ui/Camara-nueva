package com.rodyto.lenspro

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/* ================================================================
 * Primitivas reusable glass morphing sin difuminar iconos/texto
 * ================================================================ */

fun Modifier.gaussianBlur(radius: Float = 18f, strong: Boolean = false): Modifier = this

fun Modifier.liquidGlass(
    palette: GlassPalette,
    shape: androidx.compose.ui.graphics.Shape,
    blurRadius: Float = 0f,
    strong: Boolean = false
): Modifier = this
    .clip(shape)
    .background(palette.ultraBase, shape)
    .drawWithCache {
        val topGlow = Brush.verticalGradient(
            colors = listOf(
                palette.ultraSurface,
                palette.accentSoft.copy(alpha = if (palette.isDark) 0.08f else 0.05f),
                Color.Transparent,
                palette.shadow.copy(alpha = if (strong) 0.45f else 0.25f)
            )
        )
        val sideSheen = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = if (palette.isDark) 0.18f else 0.12f),
                Color.Transparent,
                palette.accent.copy(alpha = if (palette.isDark) 0.07f else 0.04f)
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        )
        onDrawBehind {
            drawRect(topGlow)
            drawRect(sideSheen)
            drawRect(
                color = palette.shadow.copy(alpha = if (strong) 0.30f else 0.16f),
                topLeft = Offset(0f, size.height * 0.68f),
                size = Size(size.width, size.height * 0.32f)
            )
        }
    }
    .border(0.8.dp, palette.ultraStroke, shape)
    .border(0.5.dp, palette.ultraStrokeInner, shape)

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
        targetValue = if (pressed.value) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "glass_bubble_scale"
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .liquidGlass(palette, shape, blurRadius = 0f, strong = strong)
            .drawWithCache {
                // Use 'this.size' (DrawScope.size: Size) to avoid shadowing the Dp parameter 'size'
                val drawSize = this.size
                onDrawWithContent {
                    drawContent()
                    drawCircle(
                        color = Color.White.copy(alpha = if (palette.isDark) 0.06f else 0.03f),
                        radius = drawSize.minDimension * 0.32f,
                        center = Offset(drawSize.width * 0.28f, drawSize.height * 0.28f),
                        style = Stroke(width = drawSize.minDimension * 0.04f)
                    )
                }
            }
            .let {
                if (onClick != null) {
                    it.clickable(
                        interactionSource = interaction,
                        indication = ripple(bounded = false, radius = size / 2),
                        onClick = onClick
                    )
                } else {
                    it
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.offset(y = (-1).dp), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
fun UltraThinPanel(
    modifier: Modifier = Modifier,
    palette: GlassPalette,
    cornerRadius: Dp = 24.dp,
    strong: Boolean = true,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(modifier = modifier.liquidGlass(palette, shape, blurRadius = 0f, strong = strong)) {
        content()
    }
}

@Composable
private fun MutableInteractionSource.collectIsPressedAsStateCompat(): androidx.compose.runtime.State<Boolean> {
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
