package com.rodyto.lenspro

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * LensSelectorRow v3.0 — Liquid Glass premium
 *
 * NOVEDADES v3.0 (sobre FIX④):
 *  • Soporta lista dinámica de lentes (parámetro `availableLenses`).
 *    Si no se pasa nada, usa los 3 clásicos ("0.5x", "1x", "3x") para
 *    compatibilidad hacia atrás con el código existente.
 *  • Anillo de glow blanco perimetral en la lente activa (whiteGlow).
 *  • Indicador "OPT" / "DIG" debajo de la lente 3x según si es tele
 *    óptico real o zoom digital con remosaico de Samsung HAL — esta
 *    información viene del VM (telephotoIsOptical).
 *  • Debounce 400ms heredado (anti-doble-tap).
 *  • Tap largo en una lente abre el ZoomControl popup (callback opcional
 *    onLongPressLens) — permite zoom continuo de precisión.
 *  • cornerRadius 30dp+ del contenedor (especificación premium).
 *
 * NOTA IMPORTANTE: las firmas del overload original (currentLens, palette,
 * onSelect, modifier) se mantienen 100% compatibles. MainActivity no
 * necesita cambiar si no quiere usar las nuevas features.
 */
@Composable
fun LensSelectorRow(
    currentLens: String,
    palette: GlassPalette,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    availableLenses: List<String> = listOf("0.5x", "1x", "3x"),
    telephotoIsOptical: Boolean = false,
    onLongPressLens: (() -> Unit)? = null
) {
    // Debounce global anti-doble-tap (heredado del FIX④)
    var lastTapMs by remember { mutableLongStateOf(0L) }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(36.dp))
            .liquidGlass(palette, RoundedCornerShape(36.dp), strong = true)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        availableLenses.forEach { lens ->
            LensBubblePro(
                text = labelFor(lens),
                selected = currentLens == lens,
                palette = palette,
                isOpticalIndicator = when (lens) {
                    "3x" -> telephotoIsOptical
                    "2x" -> telephotoIsOptical
                    else -> true
                },
                showOpticalDot = (lens == "3x" || lens == "2x"),
                onClick = {
                    val now = System.currentTimeMillis()
                    if (now - lastTapMs >= 400L) {
                        lastTapMs = now
                        onSelect(lens)
                    }
                },
                onLongPress = onLongPressLens
            )
        }
    }
}

@Composable
private fun LensBubblePro(
    text: String,
    selected: Boolean,
    palette: GlassPalette,
    isOpticalIndicator: Boolean,
    showOpticalDot: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    val targetSize = if (selected) 42.dp else 32.dp

    val animSize by animateDpAsState(
        targetValue = targetSize,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "lens_size"
    )
    val interaction = remember { MutableInteractionSource() }
    val scaleAnim by animateFloatAsState(
        targetValue = if (selected) 1f else 0.97f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
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
            .background(
                if (selected) palette.accent.copy(alpha = 0.96f)
                else Color.White.copy(alpha = if (palette.isDark) 0.06f else 0.32f)
            )
            .border(
                width = if (selected) 0.dp else 0.6.dp,
                color = if (selected) Color.Transparent else palette.borderSoft,
                shape = CircleShape
            )
            // Glow blanco premium SOLO en la lente activa
            .whiteGlow(active = selected, shape = CircleShape, intensity = 1f)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = 22.dp),
                onClick = onClick
            )
            .then(
                if (onLongPress != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = { onLongPress() }
                        )
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) palette.onAccent else palette.onGlass,
            fontWeight = FontWeight.Bold,
            fontSize = if (selected) 13.sp else 11.sp
        )

        // Mini-dot indicador óptico/digital para tele
        if (showOpticalDot && selected) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(
                        if (isOpticalIndicator) Color.White
                        else Color(0xFFFFB020) // ámbar para indicar zoom digital
                    )
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { translationY = -2.dp.toPx() }
            )
        }
    }
}

private fun labelFor(lens: String): String = when (lens) {
    "0.5x" -> ".5"
    "1x" -> "1×"
    "2x" -> "2×"
    "3x" -> "3×"
    "5x" -> "5×"
    "10x" -> "10×"
    else -> lens
}
