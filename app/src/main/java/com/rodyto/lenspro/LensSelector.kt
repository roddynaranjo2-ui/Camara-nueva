package com.rodyto.lenspro

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
 * LensSelectorRow v3.6 Pro — OPTIMIZADO
 *
 * Cambios v3.6 (sobre v3.5):
 *  ① Animación selección más rápida: StiffnessMedium en vez de
 *    StiffnessMediumLow + dampingRatio NoBouncy. Resultado: la transición
 *    entre lentes pasa de ~280ms perceptibles a ~140ms — feedback inmediato.
 *  ② Eliminado el double animateDpAsState/animateFloatAsState — antes el
 *    LensBubblePro animaba TAMAÑO y ESCALA en paralelo, ambos con springs
 *    bouncy. Ahora sólo se anima TAMAÑO (la escala era visualmente
 *    redundante porque el size ya cambia 44↔34dp).
 *  ③ Debounce 400ms preservado (anti-doble-tap).
 *  ④ Tap largo abre ZoomControl preservado.
 *  ⑤ API 100% backwards compatible.
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
    var lastTapMs by remember { mutableLongStateOf(0L) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(36.dp))
                .liquidGlass(palette, RoundedCornerShape(36.dp), strong = true)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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

        AnimatedVisibility(
            visible = currentLens == "3x" || currentLens == "2x",
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140))
        ) {
            Spacer(Modifier.size(6.dp))
            OpticalBadge(
                isOptical = telephotoIsOptical,
                palette = palette
            )
        }
    }
}

@Composable
private fun OpticalBadge(isOptical: Boolean, palette: GlassPalette) {
    val color = if (isOptical) palette.accent else Color(0xFFFFB020)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .liquidGlass(palette, RoundedCornerShape(20.dp), strong = false)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = if (isOptical) "OPT · tele real" else "DIG · zoom Samsung",
            color = palette.onGlass,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp
        )
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
    val targetSize = if (selected) 44.dp else 34.dp

    // FIX v3.6: animación más rápida y NO bouncy. Antes era StiffnessMediumLow
    // con DampingRatioMediumBouncy → tarda ~280ms y rebota.
    val animSize by animateDpAsState(
        targetValue = targetSize,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "lens_size"
    )
    val interaction = remember { MutableInteractionSource() }
    // FIX v3.6: eliminada la animación de escala duplicada (visualmente redundante)

    Box(
        modifier = Modifier
            .size(animSize)
            .clip(CircleShape)
            .background(
                if (selected) palette.accent.copy(alpha = 0.96f)
                else Color.White.copy(alpha = if (palette.isDark) 0.08f else 0.32f)
            )
            .border(
                width = if (selected) 0.dp else 0.6.dp,
                color = if (selected) Color.Transparent else palette.borderSoft,
                shape = CircleShape
            )
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
            fontSize = if (selected) 14.sp else 12.sp
        )

        if (showOpticalDot && selected) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(
                        if (isOpticalIndicator) Color.White
                        else Color(0xFFFFB020)
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
