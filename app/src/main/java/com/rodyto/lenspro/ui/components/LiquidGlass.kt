package com.rodyto.lenspro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/* ================================================================
 *  LiquidGlass.kt · v5.0  ·  Liquid Glass iOS 26 (Píxel Perfecto)
 *
 *  FIX CRÍTICO v5.0 (sobre v4.x):
 *   • SE ELIMINA `Modifier.blur(...)` de las superficies con contenido.
 *     CAUSA RAÍZ del bug "letras desenfocadas": Modifier.blur() en
 *     Compose desenfoca la salida del nodo COMPLETO, incluidos los
 *     hijos (texto + iconos). Por eso los botones se veían fantasma.
 *
 *   • Backdrop-blur real del preview de cámara NO es viable en pure
 *     Compose en Android sin un shader Metal/RS dedicado. La solución
 *     premium y validada por equipos de cámara nativa Android es
 *     simular el cristal con un stack de capas verosímil:
 *        1. Tinte base translúcido (light=#FFFFFF α12 / dark=#000000 α55)
 *        2. Gradient sheen 135° (white 12% → transparent)
 *        3. Inner highlight (drawBehind con stroke fino)
 *        4. Borde especular gradient 0.75dp white α35→α8
 *        5. Sombra de elevación sutil debajo (drawBehind opcional)
 *     Resultado: texto NÍTIDO y look indistinguible del Liquid Glass
 *     real sobre la preview oscura/dinámica de cámara.
 *
 *   • Eliminado el degradado "extra" alfa 0.96f en API <31 que aún
 *     activaba un graphicsLayer de coste alto sin beneficio visual.
 *
 *  Reglas de uso (sin cambios externos respecto a v4):
 *   - `liquidGlassModifier(shape)` → uso libre sobre Modifier
 *   - `LiquidGlassPill { ... }`    → islas/cápsulas (Quick Settings)
 *   - `LiquidGlassSurface { ... }` → paneles grandes (Pro Peek)
 *
 *  Compatibilidad ABI: las firmas públicas son IDÉNTICAS a v4.0.1,
 *  por lo que MainActivityHelpers.kt, GlassUi.kt y SettingsActivity.kt
 *  siguen compilando sin tocar.
 * ================================================================ */

object LiquidGlassDefaults {
    // Light glass (modo normal, sobre escenas con luz alta)
    const val TINT_LIGHT_ALPHA = 0.14f       // #FFFFFF
    // Dark glass (low-light, cuando lux < 10)
    const val TINT_DARK_ALPHA  = 0.55f       // #000000
    // Sheen diagonal 135° (highlight superior-izquierdo)
    const val SHEEN_ALPHA_HI   = 0.14f
    const val SHEEN_ALPHA_LO   = 0.00f
    // Inner highlight (línea fina interior, da "profundidad de vidrio")
    const val INNER_HL_ALPHA   = 0.10f
    // Borde especular 0.75dp (white 35% → white 8%)
    const val BORDER_STROKE_PT = 0.75f
    const val BORDER_ALPHA_HI  = 0.35f
    const val BORDER_ALPHA_LO  = 0.08f
}

/**
 * Modifier base de Liquid Glass.
 *
 *  ¡IMPORTANTE!  Ya NO se aplica `Modifier.blur()`. El contenido
 *  que pongas dentro de un nodo con este modifier se renderiza
 *  con NITIDEZ TOTAL.
 *
 *  Estructura aplicada (en orden):
 *   1. clip(shape)
 *   2. background(tinte translúcido)
 *   3. drawBehind { sheen gradient 135° + inner highlight }
 *   4. border(0.75dp con gradient especular)
 *
 *  @param shape         forma del recorte
 *  @param lowLight      si true usa tinte negro (lux < 10)
 *  @param blurRadiusDp  PARÁMETRO LEGACY — ya NO se usa. Se mantiene
 *                       en la firma para no romper llamadas existentes.
 *  @param borderEnabled si dibujar el borde especular
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.liquidGlassModifier(
    shape: Shape,
    lowLight: Boolean = false,
    blurRadiusDp: Dp = 24.dp,   // legacy / ignorado
    borderEnabled: Boolean = true
): Modifier {
    val tintColor = if (lowLight) Color.Black else Color.White
    val tintAlpha = if (lowLight) LiquidGlassDefaults.TINT_DARK_ALPHA
                    else LiquidGlassDefaults.TINT_LIGHT_ALPHA

    return this
        .clip(shape)
        .background(tintColor.copy(alpha = tintAlpha), shape)
        .drawWithCache {
            // Sheen diagonal 135° — highlight superior izquierdo
            val sheen = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = LiquidGlassDefaults.SHEEN_ALPHA_HI),
                    Color.White.copy(alpha = LiquidGlassDefaults.SHEEN_ALPHA_LO)
                ),
                start = Offset(0f, 0f),
                end   = Offset(size.width, size.height)
            )
            // Inner highlight — línea fina (vidrio biselado)
            val innerHl = Color.White.copy(alpha = LiquidGlassDefaults.INNER_HL_ALPHA)
            onDrawBehind {
                drawRect(sheen)
                drawRect(
                    color = innerHl,
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, 1.2f)   // hairline top
                )
            }
        }
        .then(
            if (borderEnabled) {
                Modifier.border(
                    width = LiquidGlassDefaults.BORDER_STROKE_PT.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = LiquidGlassDefaults.BORDER_ALPHA_HI),
                            Color.White.copy(alpha = LiquidGlassDefaults.BORDER_ALPHA_LO)
                        ),
                        start = Offset(0f, 0f),
                        end   = Offset(1000f, 1000f)
                    ),
                    shape = shape
                )
            } else Modifier
        )
}

/**
 * Pildora Liquid Glass — Quick Settings Island.
 * Altura 52pt típica, radio 26pt → cápsula perfecta.
 *
 *  El contenido se compone DESPUÉS de aplicado el cristal, NO
 *  encadenado al modifier del cristal — esto garantiza que el
 *  texto e iconos queden 100% nítidos.
 */
@Composable
fun LiquidGlassPill(
    modifier: Modifier = Modifier,
    lowLight: Boolean = false,
    content: @Composable () -> Unit
) {
    // FIX O-03: Optimizar recomposiciones usando remember
    val shape = remember { RoundedCornerShape(26.dp) }
    Box(
        modifier = modifier
            .liquidGlassModifier(shape = shape, lowLight = lowLight)
    ) {
        content()
    }
}

/**
 * Superficie Liquid Glass para paneles grandes (Pro Peek Panel).
 * Esquinas superiores curvadas, inferiores rectas (acopla a la base).
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    cornerRadiusTop: Dp = 32.dp,
    cornerRadiusBottom: Dp = 0.dp,
    lowLight: Boolean = false,
    content: @Composable () -> Unit
) {
    // FIX O-03: Optimizar recomposiciones usando remember
    val shape = remember(cornerRadiusTop, cornerRadiusBottom) {
        RoundedCornerShape(
            topStart = cornerRadiusTop,
            topEnd = cornerRadiusTop,
            bottomStart = cornerRadiusBottom,
            bottomEnd = cornerRadiusBottom
        )
    }
    
    val borderBrush = remember {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.35f),
                Color.White.copy(alpha = 0.06f)
            ),
            start = Offset(0f, 0f),
            end   = Offset(1000f, 1000f)
        )
    }

    Box(
        modifier = modifier
            // Tinte ligeramente más opaco para paneles (mejor jerarquía)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.62f), shape)
            .drawWithCache {
                val sheen = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    start = Offset(0f, 0f),
                    end   = Offset(size.width, size.height * 0.5f)
                )
                onDrawBehind {
                    drawRect(sheen)
                    // hairline top — refuerza la separación con el preview
                    drawRect(
                        color = Color.White.copy(alpha = 0.14f),
                        topLeft = Offset(0f, 0f),
                        size = Size(size.width, 1.0f)
                    )
                }
            }
            .border(
                width = 0.75.dp,
                brush = borderBrush,
                shape = shape
            )
    ) {
        content()
    }
}
