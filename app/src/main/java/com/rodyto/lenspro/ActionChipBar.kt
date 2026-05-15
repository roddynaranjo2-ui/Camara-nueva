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
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
            .clip(RoundedCornerShape(30.dp))
            .liquidGlass(palette, RoundedCornerShape(30.dp), strong = true)
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
            label = when (timerSec) {
                3 -> "3 s"
                10 -> "10 s"
                else -> "Auto"
            },
            icon = if (timerSec > 0) LensIcons.Timer else LensIcons.TimerOff,
            active = timerSec > 0,
            palette = palette,
            onClick = onCycleTimer,
            contentDescription = "Temporizador"
        )
        ActionChip(
            icon = if (soundOn) LensIcons.SoundOn else LensIcons.SoundOff,
            active = soundOn,
            palette = palette,
            onClick = onToggleSound,
            contentDescription = "Sonido"
        )
        ActionChipText(
            label = aspectLabel,
            icon = LensIcons.Aspect,
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
            contentDescription = "Más ajustes"
        )
    }
}

@Composable
private fun ActionChip(
    icon: ImageVector,
    active: Boolean,
    palette: GlassPalette,
    onClick: () -> Unit,
    contentDescription: String
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressed()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "chip_scale"
    )

    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (active) palette.accent.copy(alpha = 0.96f) else Color.Transparent)
            .border(
                width = if (active) 0.dp else 0.6.dp,
                color = if (active) Color.Transparent else palette.borderSoft,
                shape = CircleShape
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = 24.dp),
                onClick = onClick
            )
            .padding(9.dp),
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
            LensIcon(
                icon = ic,
                contentDescription = contentDescription,
                tint = if (active) palette.onAccent else palette.onGlass,
                size = 18.dp
            )
        }
    }
}

@Composable
private fun ActionChipText(
    label: String,
    icon: ImageVector,
    active: Boolean,
    palette: GlassPalette,
    onClick: () -> Unit,
    contentDescription: String
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressed()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "chip_text_scale"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) palette.accent.copy(alpha = 0.95f) else Color.Transparent)
            .border(
                width = if (active) 0.dp else 0.6.dp,
                color = if (active) Color.Transparent else palette.borderSoft,
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        LensIcon(
            icon = icon,
            contentDescription = contentDescription,
            tint = if (active) palette.onAccent else palette.onGlassSecondary,
            size = 16.dp
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = label,
            color = if (active) palette.onAccent else palette.onGlass,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MutableInteractionSource.collectIsPressed(): androidx.compose.runtime.State<Boolean> {
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

@Composable
fun ModeSelectorIos(
    mode: String,
    palette: GlassPalette,
    onModeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = listOf("FOTO", "VIDEO")
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(palette.bg.copy(alpha = if (palette.isDark) 0.30f else 0.42f))
            .border(0.6.dp, palette.borderSoft, RoundedCornerShape(22.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        modes.forEach { item ->
            ModeLabel(
                text = item,
                selected = mode == item,
                palette = palette,
                onClick = { onModeChange(item) }
            )
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
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) palette.accent.copy(alpha = 0.96f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) palette.onAccent else palette.onGlassSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}
