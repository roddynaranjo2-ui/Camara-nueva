package com.rodyto.lenspro

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
// ─── FIX ──────────────────────────────────────────────────────────────────────
// rememberRipple fue deprecado a nivel ERROR en Compose Foundation 1.7.x
// (BOM 2024.09.03). Reemplazado por ripple() de Material3 1.3.0+.
import androidx.compose.material3.ripple
// ──────────────────────────────────────────────────────────────────────────────
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pill horizontal de lentes estilo iOS 19.
 *
 *  ┌──────────────────────────────┐
 *  │   .5    ( 1× )    3        │   ← un solo contenedor glass
 *  └──────────────────────────────┘
 *
 *  - Selección agranda la burbuja interna a 36 dp con bg amarillo
 *  - Resto de burbujas quedan a 28 dp transparentes
 *  - El contenedor pill aplica blur real (Android 12+)
 */
@Composable
fun LensSelectorRow(
    currentLens: String,
    palette: GlassPalette,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Opciones según hardware disponible (siempre incluyen 1x)
    val options = listOf("0.5x", "1x", "3x")

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(32.dp))
            .gaussianBlur(20f, strong = false)
            .background(palette.ultraBase, RoundedCornerShape(32.dp))
            .background(
                brush = Brush.verticalGradient(
                    0f to palette.ultraSurface.copy(alpha = 0.55f),
                    1f to Color.Transparent
                ),
                shape = RoundedCornerShape(32.dp)
            )
            .border(0.8.dp, palette.ultraStroke, RoundedCornerShape(32.dp))
            .border(0.4.dp, palette.ultraStrokeInner, RoundedCornerShape(32.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { lens ->
            LensBubblePro(
                text = labelFor(lens),
                selected = currentLens == lens,
                palette = palette,
                onClick = { onSelect(lens) }
            )
        }
    }
}

@Composable
private fun LensBubblePro(
    text: String,
    selected: Boolean,
    palette: GlassPalette,
    onClick: () -> Unit
) {
    val baseSize = 30.dp
    val targetSize = if (selected) 38.dp else baseSize
    val animSize by animateDpAsState(
        targetValue = targetSize,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "lens_size"
    )

    val interaction = remember { MutableInteractionSource() }
    val scaleAnim by animateFloatAsState(
        targetValue = if (selected) 1f else 0.96f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "lens_scale"
    )

    val bg = if (selected) LensAccent else Color.White.copy(alpha = 0.10f)
    val fg = if (selected) Color.Black else palette.onGlass
    val fontSize = if (selected) 13.sp else 11.sp

    Box(
        modifier = Modifier
            .size(animSize)
            .graphicsLayer { scaleX = scaleAnim; scaleY = scaleAnim }
            .clip(CircleShape)
            .background(bg)
            .border(
                width = if (selected) 0.dp else 0.6.dp,
                color = palette.ultraStrokeInner,
                shape = CircleShape
            )
            .clickable(
                interactionSource = interaction,
                // FIX: ripple() reemplaza a rememberRipple() (Compose Foundation 1.7+)
                indication = ripple(bounded = false, radius = 22.dp),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = fg,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize
        )
    }
}

private fun labelFor(lens: String): String = when (lens) {
    "0.5x" -> ".5"
    "1x"   -> "1×"
    "2x"   -> "2×"
    "3x"   -> "3"
    else   -> lens
}
