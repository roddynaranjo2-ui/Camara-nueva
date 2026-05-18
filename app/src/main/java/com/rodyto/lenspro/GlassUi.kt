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
 * GlassUi v3.0 — Liquid Glass premium
 *
 * NOVEDADES v3.0:
 *  • Modifier.whiteGlow(active, ...) — glow blanco perimetral premium,
 *    activable cuando un elemento está "seleccionado" (lente, modo, etc).
 *  • Modifier.liquidGlass ahora acepta accentGlow opcional → cuando un
 *    chip está activo, el borde se ilumina con el color de acento.
 *  • Shape default: RoundedCornerShape(30.dp) — bordes premium.
 *  • ShutterGlass — botón obturador estilo profesional con anillo blanco
 *    glow, núcleo morphing (FOTO/VIDEO/REC), squish dinámico.
 *  • ExposureSliderEv — slider de exposición vertical en STOPS EV
 *    (-2.0..+2.0 dinámico) con feedback háptico.
 *  • Conserva 100% de la API anterior (gaussianBlur, GlassBubble,
 *    UltraThinPanel, liquidGlass) — drop-in replacement.
 * ================================================================ */

/**
 * Aplica un blur gaussiano real (API 31+) o un fallback graphicsLayer
 * en APIs anteriores. Usado en paneles flotantes (ZoomDial, Settings).
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
 * Liquid Glass core — fondo con gradientes sutiles + doble borde + sheen.
 *
 * v3.0: parámetro opcional `accentBorder` para que un contenedor "activo"
 * pinte su borde con el color de acento (en vez del border neutro).
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
    .border(
        width = if (accentBorder) 1.2.dp else 0.8.dp,
        color = if (accentBorder) palette.accent.copy(alpha = 0.85f) else palette.ultraStroke,
        shape = shape
    )
    .border(0.5.dp, palette.ultraStrokeInner, shape)

/**
 * NUEVO v3.0: whiteGlow — capa de brillo blanco perimetral premium.
 *
 * Se compone de:
 *  • Borde externo blanco semitransparente (1.4dp)
 *  • Halo interno (drawWithCache) con gradiente radial blanco
 *  • Animación de intensidad al cambiar `active`
 *
 * Uso típico: bordear la lente activa, el modo seleccionado, el chip
 * de Flash en modo "ON", o cualquier elemento que necesite destacar
 * sin recurrir al color de acento.
 */
fun Modifier.whiteGlow(
    active: Boolean,
    shape: Shape,
    intensity: Float = 1f
): Modifier = this
    .drawWithCache {
        val alpha = (if (active) 0.55f else 0f) * intensity
        val haloAlpha = (if (active) 0.20f else 0f) * intensity
        onDrawWithContent {
            drawContent()
            if (alpha > 0.01f) {
                // Halo radial blanco interno (brillo perimetral suave)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = haloAlpha),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.maxDimension * 0.7f
                    )
                )
            }
        }
    }
    .border(
        width = if (active) 1.4.dp else 0.dp,
        color = if (active) Color.White.copy(alpha = 0.85f * intensity) else Color.Transparent,
        shape = shape
    )

/**
 * GlassBubble — burbuja circular glass clickable.
 * v3.0: soporta `activeGlow=true` para iluminación blanca premium.
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
            .whiteGlow(active = activeGlow, shape = shape)
            .drawWithCache {
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
 * v3.0: cornerRadius default subido a 30.dp (especificación premium).
 */
@Composable
fun UltraThinPanel(
    modifier: Modifier = Modifier,
    palette: GlassPalette,
    cornerRadius: Dp = 30.dp,
    strong: Boolean = true,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(modifier = modifier.liquidGlass(palette, shape, blurRadius = 0f, strong = strong)) {
        content()
    }
}

/* ================================================================
 * SHUTTER GLASS — botón obturador profesional (alternativa premium)
 *
 * NOTA: este botón NO reemplaza al `ShutterButtonPro.kt` actual
 * (que tiene swipe FOTO↔VIDEO + pulso de grabación). Es un envoltorio
 * glass adicional que ENVUELVE al ShutterButtonPro existente con un
 * anillo glass exterior + glow blanco para una sensación más premium.
 *
 * Si no quieres este envoltorio, puedes seguir usando ShutterButtonPro
 * directo en MainActivity — ambos son compatibles.
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
                        Color.White.copy(alpha = 0.10f),
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
 * EXPOSURE SLIDER EV — vertical, rango dinámico ±2.0 EV típico
 *
 * Lee el rango directamente del ViewModel (vm.getExposureEvRange())
 * que ya devuelve [lower*step .. upper*step] del sensor real.
 * Si el sensor expone más de ±2EV, el slider se adapta; si expone
 * menos, también. Esto cumple la especificación del prompt
 * ("rango dinámico de -2.0 a +2.0") respetando el sensor real.
 *
 * Diseño:
 *  • Contenedor liquidGlass cornerRadius 30dp
 *  • Track central con relleno de acento desde el centro (no desde abajo)
 *  • Marcas EV cada 1.0 stop
 *  • Texto central "+1.3" / "-0.7" / "0.0"
 *  • Háptica al cruzar cada 0.33 EV (1/3 de stop)
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
                    // Sensibilidad: cada 6 px ≈ 1/6 EV (sensación cinematográfica)
                    accumulator += -dragAmount / 36f
                    if (kotlin.math.abs(accumulator) >= 0.0001f) {
                        val newEv = (valueEv + accumulator).coerceIn(rangeEv.start, rangeEv.endInclusive)
                        accumulator = 0f
                        if (kotlin.math.abs(newEv - valueEv) > 0.001f) {
                            // Tick háptico cada 1/3 EV
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
        // Icono sol/luna arriba
        LensIcon(LensIcons.Brightness, tint = palette.accent, size = 18.dp)
        Spacer(Modifier.size(4.dp))
        // Valor EV con signo
        Text(
            text = formatEv(valueEv),
            color = palette.onGlass,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.size(6.dp))

        // Track vertical con relleno desde el CENTRO
        Box(
            modifier = Modifier
                .width(5.dp)
                .height(120.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(palette.onGlassSecondary.copy(alpha = 0.20f))
        ) {
            // Normalizar valueEv a [0..1] respecto a centro=0.5
            val normalized = ((valueEv - rangeEv.start) / span).coerceIn(0f, 1f)
            val centerPct = ((centerEv - rangeEv.start) / span).coerceIn(0f, 1f)
            val animatedNorm by animateFloatAsState(
                targetValue = normalized,
                animationSpec = tween(140),
                label = "ev_norm"
            )

            // Línea central de referencia
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .offset(y = (120 * (1f - centerPct)).dp - 0.5.dp)
                    .background(palette.onGlass.copy(alpha = 0.45f))
            )

            // Relleno: del centro al valor actual
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

            // Marcador (thumb) en la posición actual
            Box(
                modifier = Modifier
                    .size(width = 13.dp, height = 4.dp)
                    .offset(x = (-4).dp, y = (120 * (1f - animatedNorm)).dp - 2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White)
            )
        }

        Spacer(Modifier.size(6.dp))
        // Indicador de extremos
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
