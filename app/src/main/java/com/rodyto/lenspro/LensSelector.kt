package com.rodyto.lenspro

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Selector circular de lentes (.5× / 1× / 2× / 3×).
 *
 *  - Cada burbuja flota sobre Ultra Thin Material.
 *  - La burbuja seleccionada se agranda y vibra ligeramente (spring overshoot).
 *  - El conjunto está envuelto en un "tray" Glass para reforzar el agrupamiento.
 */
@Composable
fun LensSelectorRow(
    currentLens: String,
    palette: GlassPalette,
    onSelect: (String) -> Unit
) {
    val options = listOf("0.5x", "1x", "2x", "3x")
    Row(
        Modifier
            .clip(RoundedCornerShape(28.dp))
            .gaussianBlur(20f, strong = false)
            .background(palette.ultraBase, RoundedCornerShape(28.dp))
            .border(0.6.dp, palette.ultraStrokeInner, RoundedCornerShape(28.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
    val bgAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.28f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "lens_alpha"
    )
    val bg = if (selected) LensAccent else Color.White.copy(alpha = bgAlpha * 0.10f)
    val fg = if (selected) Color.Black else palette.onGlass

    Box(
        modifier = Modifier
            .size(animSize)
            .clip(CircleShape)
            .background(bg)
            .border(0.7.dp, palette.ultraStroke, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = fg,
            fontWeight = FontWeight.Bold,
            fontSize = if (selected) 12.sp else 11.sp
        )
    }
}

private fun labelFor(lens: String): String = when (lens) {
    "0.5x" -> ".5"
    "1x"   -> "1×"
    "2x"   -> "2×"
    "3x"   -> "3×"
    else   -> lens
}
