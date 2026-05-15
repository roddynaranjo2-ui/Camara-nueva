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
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LensSelectorRow(
    currentLens: String,
    palette: GlassPalette,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = listOf("0.5x", "1x", "3x")

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(36.dp))
            .liquidGlass(palette, RoundedCornerShape(36.dp), strong = true)
            .padding(horizontal = 8.dp, vertical = 8.dp),
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
    val targetSize = if (selected) 40.dp else 32.dp
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
        targetValue = if (selected) 1f else 0.97f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "lens_scale"
    )

    Box(
        modifier = Modifier
            .size(animSize)
            .graphicsLayer {
                scaleX = scaleAnim
                scaleY = scaleAnim
            }
            .clip(CircleShape)
            .background(if (selected) palette.accent.copy(alpha = 0.96f) else Color.White.copy(alpha = if (palette.isDark) 0.06f else 0.32f))
            .border(
                width = if (selected) 0.dp else 0.6.dp,
                color = if (selected) Color.Transparent else palette.borderSoft,
                shape = CircleShape
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = 22.dp),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) palette.onAccent else palette.onGlass,
            fontWeight = FontWeight.Bold,
            fontSize = if (selected) 13.sp else 11.sp
        )
    }
}

private fun labelFor(lens: String): String = when (lens) {
    "0.5x" -> ".5"
    "1x" -> "1×"
    "2x" -> "2×"
    "3x" -> "3×"
    else -> lens
}
