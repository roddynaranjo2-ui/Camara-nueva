package com.rodyto.lenspro

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Barra de chips de acción rápida estilo iOS 19 (la fila que aparece al
 * expandir el chevron en la app Cámara de iPhone). Cada chip es un círculo
 * pequeño dentro de un contenedor pill Liquid Glass.
 */
@Composable
fun ActionChipBar(
    palette: GlassPalette,
    flashOn: Boolean,
    onToggleFlash: () -> Unit,
    hdrOn: Boolean,
    onToggleHdr: () -> Unit,
    timerSec: Int,
    onCycleTimer: () -> Unit,
    soundOn: Boolean,
    onToggleSound: () -> Unit,
    aspectLabel: String,
    onCycleAspect: () -> Unit,
    onOpenMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .gaussianBlur(22f, strong = false)
            .background(palette.ultraBase, RoundedCornerShape(28.dp))
            .background(
                brush = Brush.verticalGradient(
                    0f to palette.ultraSurface.copy(alpha = 0.6f),
                    1f to Color.Transparent
                ),
                shape = RoundedCornerShape(28.dp)
            )
            .border(0.8.dp, palette.ultraStroke, RoundedCornerShape(28.dp))
            .border(0.4.dp, palette.ultraStrokeInner, RoundedCornerShape(28.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionChip(
            icon = if (flashOn) LensIcons.FlashOn else LensIcons.FlashOff,
            active = flashOn,
            palette = palette,
            onClick = onToggleFlash,
            contentDescription = "Flash"
        )
        ActionChip(
            icon = LensIcons.Hdr,
            active = hdrOn,
            palette = palette,
            onClick = onToggleHdr,
            contentDescription = "HDR"
        )
        ActionChipText(
            label = when (timerSec) { 3 -> "3s"; 10 -> "10s"; else -> "" },
            icon = if (timerSec > 0) LensIcons.Timer else LensIcons.TimerOff,
            active = timerSec > 0,
            palette = palette,
            onClick = onCycleTimer,
            contentDescription = "Temporizador"
        )
        ActionChip(
            icon = if (soundOn) LensIcons.SoundOn else LensIcons.SoundOff,
            active = false, // estado pasivo: el icono ya cambia
            palette = palette,
            onClick = onToggleSound,
            contentDescription = "Sonido"
        )
        ActionChipText(
            label = aspectLabel,
            icon = null,
            active = false,
            palette = palette,
            onClick = onCycleAspect,
            contentDescription = "Relación de aspecto"
        )
        ActionChip(
            icon = LensIcons.More,
            active = false,
            palette = palette,
            onClick = onOpenMore,
            contentDescription = "Más"
        )
    }
}

/** Chip circular con sólo icono. */
@Composable
private fun ActionChip(
    icon: ImageVector,
    active: Boolean,
    palette: GlassPalette,
    onClick: () -> Unit,
    contentDescription: String
) {
    val interaction = remember { MutableInteractionSource() }
    val bg = if (active) LensAccent.copy(alpha = 0.95f) else Color.Transparent
    val tint = if (active) Color.Black else palette.onGlass

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = rememberRipple(bounded = false, radius = 24.dp),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = icon,
            transitionSpec = {
                (scaleIn(spring(stiffness = Spring.StiffnessMedium)) + fadeIn(tween(160)))
                    .togetherWith(scaleOut(tween(120)) + fadeOut(tween(120)))
            },
            label = "chip_icon"
        ) { ic ->
            LensIcon(ic, contentDescription, tint, 18.dp)
        }
    }
}

/** Chip con texto + icono opcional (timer/aspect). */
@Composable
private fun ActionChipText(
    label: String,
    icon: ImageVector?,
    active: Boolean,
    palette: GlassPalette,
    onClick: () -> Unit,
    contentDescription: String
) {
    val interaction = remember { MutableInteractionSource() }
    val bg = if (active) LensAccent.copy(alpha = 0.95f) else Color.Transparent
    val fg = if (active) Color.Black else palette.onGlass

    val pressed by interaction.collectIsPressed()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "chip_press"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = rememberRipple(bounded = true),
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null && label.isEmpty()) {
            LensIcon(icon, contentDescription, fg, 18.dp)
        } else if (icon != null) {
            LensIcon(icon, contentDescription, fg, 16.dp)
            Spacer(Modifier.size(4.dp))
            Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MutableInteractionSource.collectIsPressed(): androidx.compose.runtime.State<Boolean> {
    val isPressed = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(this) {
        val active = ArrayList<androidx.compose.foundation.interaction.PressInteraction.Press>()
        interactions.collect { i ->
            when (i) {
                is androidx.compose.foundation.interaction.PressInteraction.Press   -> active.add(i)
                is androidx.compose.foundation.interaction.PressInteraction.Release -> active.remove(i.press)
                is androidx.compose.foundation.interaction.PressInteraction.Cancel  -> active.remove(i.press)
            }
            isPressed.value = active.isNotEmpty()
        }
    }
    return isPressed
}

/* ----------------------------------------------------------------
 *  Mode Selector iOS 19 — CINEMATIC / VIDEO / FOTO / RETRATO / PAN
 * ---------------------------------------------------------------- */

@Composable
fun ModeSelectorIos(
    mode: String,
    palette: GlassPalette,
    onModeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Mantenemos solo FOTO y VIDEO funcionales (resto preparados para futuras versiones)
    val modes = listOf("FOTO", "VIDEO")
    Row(
        modifier = modifier
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        modes.forEach { m ->
            ModeLabel(text = m, selected = mode == m, palette = palette) {
                onModeChange(m)
            }
        }
    }
}

@Composable
private fun ModeLabel(
    text: String,
    selected: Boolean,
    palette: GlassPalette,
    onClick: () -> Unit
) {
    val color = if (selected) LensAccent else palette.onGlassSecondary
    val weight = if (selected) FontWeight.Bold else FontWeight.Medium
    Text(
        text = text,
        color = color,
        fontSize = 13.sp,
        fontWeight = weight,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}
