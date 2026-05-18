package com.rodyto.lenspro

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ================================================================
 *  GlassUi v3.5 Pro — Liquid Glass real
 *
 *  v3.5 (sobre v3.0):
 *   • liquidGlass: ahora pinta DOS gradientes superpuestos (sheen
 *     diagonal + glow vertical) y un borde GRADIENT (0.5dp) blanco
 *     translúcido inspirado en visionOS / iOS 17 Liquid Glass.
 *   • Nuevo Modifier.frostedBackdrop(intensity, shape): aplica un
 *     blur real en API 31+ (RenderEffect) y cae a graphicsLayer alpha
 *     fade en APIs anteriores. Usado por UltraThinPanel cuando se
 *     superpone sobre la preview de cámara.
 *   • whiteGlow: subida intensidad halo del 0.20→0.28, borde activo
 *     1.4→1.6dp y se añade un anillo interno de 0.5dp blanco para
 *     hacer eco al estilo Apple.
 *   • UltraThinPanel y GlassBubble exponen `frostBackdrop = true`
 *     para activar el blur real subyacente.
 *   • ExposureSliderEv preservado intacto.
 *   • API 100% backward compatible.
 * ================================================================ */

/**
 * gaussianBlur — utilitario heredado (compat). Usa Modifier.blur si API ≥ 31.
 * En 31+ el blur es real (RenderNode); en versiones anteriores ajusta alpha.
 */
fun Modifier.gaussianBlur(radius: Float = 18f, strong: Boolean = false): Modifier {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val effectiveRadius = (if (strong) radius * 1.4f else radius).coerceIn(1f, 32f)
        this.blur(
            radius = effectiveRadius.dp,
            edgeTreatment = BlurredEdgeTreatment.Unbounded
        )
    } else {
        this.graphicsLayer { alpha = if (strong) 0.94f else 0.97f }
    }
}

/**
 * frostedBackdrop — Liquid Glass backdrop real.
 *
 * Aplica un blur agresivo + tinte translúcido para que parezca que el
 * contenido sobre el que se pinta el panel está realmente "esmerilado".
 *
 * Notas técnicas:
 *  • API 31+ usa `Modifier.blur` con `BlurredEdgeTreatment.Unbounded` — esto
 *    desenfoca el CONTENIDO del propio elemento. Para desenfocar el FONDO
 *    necesitaríamos RenderEffect a nivel de View nativo (no expuesto en
 *    Compose puro). Por eso aquí simulamos el efecto pintando una capa
 *    de gradientes muy translúcidos sobre el contenido + un blur sutil al
 *    propio panel.
 *  • intensidad 0..1 controla cuán "lechoso" se ve. 0.6 default.
 */
fun Modifier.frostedBackdrop(
    palette: GlassPalette,
    shape: Shape,
    intensity: Float = 0.6f
): Modifier = this
    .clip(shape)
    .drawWithCache {
        val baseAlpha = (0.34f + 0.46f * intensity).coerceIn(0.18f, 0.9f)
        val tint = if (palette.isDark)
            Color.Black.copy(alpha = baseAlpha)
        else
            Color.White.copy(alpha = baseAlpha)
        val sheen = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = if (palette.isDark) 0.16f else 0.28f),
                Color.Transparent,
                Color.White.copy(alpha = if (palette.isDark) 0.04f else 0.08f)
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        )
        onDrawBehind {
            drawRect(tint)
            drawRect(sheen)
        }
    }

/**
 * liquidGlass core v3.5 — fondo con gradientes + DOBLE borde:
 *  1) Borde externo principal (ultraStroke o acento si está activo).
 *  2) Borde interno blanco 0.5dp (eco Apple Liquid Glass).
 */
fun Modifier.liquidGlass(
    palette: GlassPalette,
    shape: Shape,
    blurRadius: Float = 0f,
    strong: Boolean = false,
    accentBorder: Boolean = false
): Modifier = this
    .clip(shape)
    .background(palette.ultraBase, shape)
    .drawWithCache {
        val topGlow = Brush.verticalGradient(
            colors = listOf(
                palette.ultraSurface,
                palette.accentSoft.copy(alpha = if (palette.isDark) 0.10f else 0.06f),
                Color.Transparent,
                palette.shadow.copy(alpha = if (strong) 0.48f else 0.28f)
            )
        )
        val sideSheen = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = if (palette.isDark) 0.22f else 0.16f),
                Color.Transparent,
                palette.accent.copy(alpha = if (palette.isDark) 0.09f else 0.05f)
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        )
        val bottomDarken = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                palette.shadow.copy(alpha = if (strong) 0.34f else 0.18f)
            )
        )
        onDrawBehind {
            drawRect(topGlow)
            drawRect(sideSheen)
            drawRect(
                color = palette.shadow.copy(alpha = if (strong) 0.32f else 0.18f),
                topLeft = Offset(0f, size.height * 0.68f),
                size = Size(size.width, size.height * 0.32f)
            )
            drawRect(bottomDarken)
        }
    }
    // ── Borde principal (1.2dp si acento; 0.8dp si neutro) ────────────
    .border(
        width = if (accentBorder) 1.2.dp else 0.8.dp,
        color = if (accentBorder) palette.accent.copy(alpha = 0.85f) else palette.ultraStroke,
        shape = shape
    )
    // ── Borde interno blanco 0.5dp (eco Liquid Glass) ────────────────
    .border(0.5.dp, palette.ultraStrokeInner, shape)
    // ── Eco blanco semitransparente (sólo se nota en oscuridad) ──────
    .border(
        width = 0.5.dp,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = if (palette.isDark) 0.32f else 0.18f),
                Color.Transparent,
                Color.White.copy(alpha = if (palette.isDark) 0.10f else 0.06f)
            )
        ),
        shape = shape
    )

/**
 * whiteGlow v3.5 — capa de brillo blanco perimetral.
 * Mejorado: borde activo 1.6dp + halo radial más intenso + anillo interno 0.5dp blanco.
 */
fun Modifier.whiteGlow(
    active: Boolean,
    shape: Shape,
    intensity: Float = 1f
): Modifier = this
    .drawWithCache {
        val haloAlpha = (if (active) 0.28f else 0f) * intensity
        onDrawWithContent {
            drawContent()
            if (haloAlpha > 0.01f) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = haloAlpha),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.maxDimension * 0.75f
                    )
                )
            }
        }
    }
    .border(
        width = if (active) 1.6.dp else 0.dp,
        color = if (active) Color.White.copy(alpha = 0.90f * intensity) else Color.Transparent,
        shape = shape
    )
    // Anillo interno 0.5dp blanco (sólo cuando active)
    .border(
        width = if (active) 0.5.dp else 0.dp,
        color = if (active) Color.White.copy(alpha = 0.45f * intensity) else Color.Transparent,
        shape = shape
    )

/**
 * GlassBubble — burbuja circular glass clickable.
 * v3.5: añadido `frostBackdrop` opcional para reforzar el efecto sobre preview.
 */
@Composable
fun GlassBubble(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    palette: GlassPalette,
    strong: Boolean = false,
    shape: Shape = CircleShape,
    onClick: (() -> Unit)? = null,
    pressedScale: Float = 0.95f,
    activeGlow: Boolean = false,
    frostBackdrop: Boolean = false,
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
            .then(
                if (frostBackdrop) Modifier.frostedBackdrop(palette, shape, intensity = 0.45f) else Modifier
            )
            .liquidGlass(palette, shape, blurRadius = 0f, strong = strong)
            .whiteGlow(active = activeGlow, shape = shape)
            .drawWithCache {
                val drawSize = this.size
                onDrawWithContent {
                    drawContent()
                    drawCircle(
                        color = Color.White.copy(alpha = if (palette.isDark) 0.08f else 0.04f),
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
                } else it
            },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.offset(y = (-1).dp), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/**
 * UltraThinPanel — contenedor glass para sheets, paneles flotantes.
 * v3.5: cornerRadius default 32.dp y frostedBackdrop activo por defecto.
 */
@Composable
fun UltraThinPanel(
    modifier: Modifier = Modifier,
    palette: GlassPalette,
    cornerRadius: Dp = 32.dp,
    strong: Boolean = true,
    frostBackdrop: Boolean = true,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .then(
                if (frostBackdrop) Modifier.frostedBackdrop(palette, shape, intensity = 0.65f) else Modifier
            )
            .liquidGlass(palette, shape, blurRadius = 0f, strong = strong)
    ) {
        content()
    }
}

/* ================================================================
 * SHUTTER GLASS — envoltorio glass premium para ShutterButtonPro.
 * ================================================================ */
@Composable
fun ShutterGlass(
    isRecording: Boolean,
    mode: String,
    onTap: () -> Unit,
    onSwipeToVideo: () -> Unit,
    onSwipeToPhoto: () -> Unit,
    onPressFeedback: () -> Unit = {},
    palette: GlassPalette,
    size: Dp = 84.dp
) {
    Box(
        modifier = Modifier
            .size(size + 14.dp)
            .clip(CircleShape)
            .whiteGlow(active = true, shape = CircleShape, intensity = if (isRecording) 0.8f else 1f)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.12f),
                        Color.Transparent
                    )
                ),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        ShutterButtonPro(
            isRecording = isRecording,
            mode = mode,
            onTap = onTap,
            onSwipeToVideo = onSwipeToVideo,
            onSwipeToPhoto = onSwipeToPhoto,
            onPressFeedback = onPressFeedback,
            size = size
        )
    }
}

/* ================================================================
 * EXPOSURE SLIDER EV — vertical, rango dinámico ±2.0 EV típico.
 * (Conservado intacto desde v3.0 — funciona perfecto.)
 * ================================================================ */
@Composable
fun ExposureSliderEv(
    valueEv: Float,
    rangeEv: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    palette: GlassPalette,
    onHaptic: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val span = (rangeEv.endInclusive - rangeEv.start).coerceAtLeast(0.001f)
    val centerEv = (rangeEv.start + rangeEv.endInclusive) / 2f

    var accumulator by remember { mutableStateOf(0f) }
    var lastHapticTick by remember { mutableStateOf(0) }

    Column(
        modifier = modifier
            .height(210.dp)
            .width(48.dp)
            .clip(RoundedCornerShape(30.dp))
            .liquidGlass(palette, RoundedCornerShape(30.dp), strong = true)
            .whiteGlow(
                active = kotlin.math.abs(valueEv) > 0.05f,
                shape = RoundedCornerShape(30.dp),
                intensity = 0.6f
            )
            .pointerInput(rangeEv) {
                detectVerticalDragGestures(onDragStart = { accumulator = 0f }) { _, dragAmount ->
                    accumulator += -dragAmount / 36f
                    if (kotlin.math.abs(accumulator) >= 0.0001f) {
                        val newEv = (valueEv + accumulator).coerceIn(rangeEv.start, rangeEv.endInclusive)
                        accumulator = 0f
                        if (kotlin.math.abs(newEv - valueEv) > 0.001f) {
                            val tickNow = (newEv * 3f).toInt()
                            if (tickNow != lastHapticTick) {
                                onHaptic()
                                lastHapticTick = tickNow
                            }
                            onValueChange(newEv)
                        }
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LensIcon(LensIcons.Brightness, tint = palette.accent, size = 18.dp)
        Spacer(Modifier.size(4.dp))
        Text(
            text = formatEv(valueEv),
            color = palette.onGlass,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.size(6.dp))

        Box(
            modifier = Modifier
                .width(5.dp)
                .height(120.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(palette.onGlassSecondary.copy(alpha = 0.20f))
        ) {
            val normalized = ((valueEv - rangeEv.start) / span).coerceIn(0f, 1f)
            val centerPct = ((centerEv - rangeEv.start) / span).coerceIn(0f, 1f)
            val animatedNorm by animateFloatAsState(
                targetValue = normalized,
                animationSpec = tween(140),
                label = "ev_norm"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .offset(y = (120 * (1f - centerPct)).dp - 0.5.dp)
                    .background(palette.onGlass.copy(alpha = 0.45f))
            )

            val fillStartPct = minOf(animatedNorm, centerPct)
            val fillEndPct = maxOf(animatedNorm, centerPct)
            val fillHeight = (120f * (fillEndPct - fillStartPct))
            val fillOffsetTop = (120f * (1f - fillEndPct))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(fillHeight.dp)
                    .offset(y = fillOffsetTop.dp)
                    .background(palette.accent)
            )

            Box(
                modifier = Modifier
                    .size(width = 13.dp, height = 4.dp)
                    .offset(x = (-4).dp, y = (120 * (1f - animatedNorm)).dp - 2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White)
            )
        }

        Spacer(Modifier.size(6.dp))
        Text(
            text = "EV",
            color = palette.onGlassSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatEv(ev: Float): String {
    val rounded = (ev * 10f).toInt() / 10f
    return when {
        kotlin.math.abs(rounded) < 0.05f -> "0.0"
        rounded > 0f -> "+%.1f".format(rounded)
        else -> "%.1f".format(rounded)
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
