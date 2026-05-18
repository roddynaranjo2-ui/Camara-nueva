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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ActionChipBar v3.0 — barra superior translúcida glass premium.
 *
 * NOVEDADES v3.0 (sobre v2):
 *  • Chip RAW dedicado (estado activo/inactivo bien definido).
 *  • Botón "Settings gear" que abre SettingsActivity directamente.
 *  • Glow blanco perimetral en chips activos (Flash ON/AUTO, HDR, RAW).
 *  • cornerRadius del contenedor subido a 30dp+ (premium spec).
 *  • Mantiene 100% compatibilidad con la versión anterior: si llamas
 *    sin los nuevos params, ofrece la misma UX que tenías.
 *
 * Si tu MainActivity actual aún no pasa `rawOn`/`onToggleRaw`/`onOpenSettings`,
 * los defaults (false / {} / {}) mantienen comportamiento neutro.
 */
@Composable
fun ActionChipBar(
    palette: GlassPalette,
    flashMode: FlashMode,
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
    modifier: Modifier = Modifier,
    // ── Nuevos parámetros (compat: defaults seguros) ──────────────
    rawOn: Boolean = false,
    onToggleRaw: () -> Unit = {},
    onOpenSettings: () -> Unit = onOpenMore
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(30.dp))
            .liquidGlass(palette, RoundedCornerShape(30.dp), strong = true)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ▼ Flash tri-estado con icono representativo ▼
        ActionChipText(
            label = when (flashMode) {
                FlashMode.OFF -> "Off"; FlashMode.AUTO -> "Auto"; FlashMode.ON -> "On"
            },
            icon = when (flashMode) {
                FlashMode.OFF -> LensIcons.FlashOff
                FlashMode.AUTO -> LensIcons.FlashAuto
                FlashMode.ON -> LensIcons.FlashOn
            },
            active = flashMode != FlashMode.OFF,
            palette = palette,
            onClick = onToggleFlash,
            contentDescription = "Flash ${flashMode.label}"
        )
        // HDR
        ActionChip(
            icon = LensIcons.Hdr, active = hdrOn, palette = palette,
            onClick = onToggleHdr, contentDescription = "HDR"
        )
        // RAW (DNG) — chip dedicado con glow blanco si está activo
        ActionChipText(
            label = "RAW",
            icon = LensIcons.Raw,
            active = rawOn,
            palette = palette,
            onClick = onToggleRaw,
            contentDescription = "Captura RAW DNG"
        )
        // Temporizador
        ActionChipText(
            label = when (timerSec) { 3 -> "3 s"; 10 -> "10 s"; else -> "Auto" },
            icon = if (timerSec > 0) LensIcons.Timer else LensIcons.TimerOff,
            active = timerSec > 0, palette = palette,
            onClick = onCycleTimer, contentDescription = "Temporizador"
        )
        // Sonido
        ActionChip(
            icon = if (soundOn) LensIcons.SoundOn else LensIcons.SoundOff,
            active = soundOn, palette = palette,
            onClick = onToggleSound, contentDescription = "Sonido"
        )
        // Relación de aspecto
        ActionChipText(
            label = aspectLabel, icon = LensIcons.Aspect, active = false,
            palette = palette, onClick = onCycleAspect, contentDescription = "Relación de aspecto"
        )
        // Más (panel rápido)
        ActionChip(
            icon = LensIcons.More, active = false, palette = palette,
            onClick = onOpenMore, contentDescription = "Más ajustes rápidos"
        )
        // ▼ NUEVO: acceso directo a SettingsActivity (gear)
        ActionChip(
            icon = LensIcons.Settings, active = false, palette = palette,
            onClick = onOpenSettings, contentDescription = "Ajustes avanzados"
        )
    }
}

@Composable
private fun ActionChip(
    icon: ImageVector, active: Boolean, palette: GlassPalette,
    onClick: () -> Unit, contentDescription: String
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
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(if (active) palette.accent.copy(alpha = 0.96f) else Color.Transparent)
            .border(
                width = if (active) 0.dp else 0.6.dp,
                color = if (active) Color.Transparent else palette.borderSoft,
                shape = CircleShape
            )
            .whiteGlow(active = active, shape = CircleShape, intensity = 0.9f)
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
                icon = ic, contentDescription = contentDescription,
                tint = if (active) palette.onAccent else palette.onGlass, size = 18.dp
            )
        }
    }
}

@Composable
private fun ActionChipText(
    label: String, icon: ImageVector, active: Boolean, palette: GlassPalette,
    onClick: () -> Unit, contentDescription: String
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
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) palette.accent.copy(alpha = 0.95f) else Color.Transparent)
            .border(
                width = if (active) 0.dp else 0.6.dp,
                color = if (active) Color.Transparent else palette.borderSoft,
                shape = RoundedCornerShape(20.dp)
            )
            .whiteGlow(active = active, shape = RoundedCornerShape(20.dp), intensity = 0.85f)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true), onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        LensIcon(
            icon = icon, contentDescription = contentDescription,
            tint = if (active) palette.onAccent else palette.onGlassSecondary, size = 16.dp
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = label,
            color = if (active) palette.onAccent else palette.onGlass,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold
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

/**
 * ModeSelectorIos — selector FOTO/VIDEO/(PRO) glass premium.
 * v3.0: glow blanco perimetral en modo seleccionado.
 */
@Composable
fun ModeSelectorIos(
    mode: String, palette: GlassPalette,
    onModeChange: (String) -> Unit, modifier: Modifier = Modifier,
    modes: List<String> = listOf("FOTO", "VIDEO")
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(30.dp))
            .liquidGlass(palette, RoundedCornerShape(30.dp), strong = false)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        modes.forEach { item ->
            ModeLabel(
                text = item, selected = mode == item, palette = palette,
                onClick = { onModeChange(item) }
            )
        }
    }
}

@Composable
private fun ModeLabel(text: String, selected: Boolean, palette: GlassPalette, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.96f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "mode_scale"
    )
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(24.dp))
            .background(
                if (selected) palette.accent.copy(alpha = 0.98f)
                else Color.Transparent
            )
            .whiteGlow(active = selected, shape = RoundedCornerShape(24.dp), intensity = 0.9f)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) palette.onAccent else palette.onGlassSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = 0.5.sp
        )
    }
}
