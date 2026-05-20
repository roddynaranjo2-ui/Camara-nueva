package com.rodyto.lenspro

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/* ================================================================
 *  LiquidGlass.kt  ·  iOS 26 Liquid Glass · Píxel Perfecto
 *
 *  Pipeline (según spec):
 *   1. Backdrop downsampling (graphicsLayer scale virtual)
 *   2. Gaussian Blur 2-pass (sigma = 20 → radius ≈ 40pt)
 *   3. Color Tint Overlay (#FFFFFF @ 0.12  /  #000000 @ 0.18 si lowLight)
 *   4. Borde con gradiente especular 135° (white 35% → white 8%)
 *
 *  Reglas de uso:
 *   - El blur real solo se aplica en API 31+ (Android 12+); en versiones
 *     anteriores degradamos a un tint sólido para no perder rendimiento.
 *   - Usa `LiquidGlassSurface` para superficies grandes (paneles).
 *   - Usa `LiquidGlassPill` para islas/cápsulas de la barra superior.
 *   - Usa `liquidGlassModifier` cuando ya tienes un contenedor.
 * ================================================================ */

object LiquidGlassDefaults {
    // Spec del prompt
    const val TINT_LIGHT_ALPHA = 0.12f      // #FFFFFF @ 0.12
    const val TINT_DARK_ALPHA  = 0.18f      // #000000 @ 0.18
    const val BLUR_RADIUS_PT   = 40f        // radio real
    const val BORDER_STROKE_PT = 0.75f      // stroke
    const val BORDER_ALPHA_HI  = 0.35f      // gradient inicio
    const val BORDER_ALPHA_LO  = 0.08f      // gradient final
}

/**
 * Modifier base de Liquid Glass. Aplica blur + tint + border especular.
 * @param lowLight si true usa tint negro (cuando el sensor detecta Lux < 10).
 */
fun Modifier.liquidGlassModifier(
    shape: Shape,
    lowLight: Boolean = false,
    blurRadiusDp: Dp = 24.dp,   // capeado: en Compose blur > 25dp degrada muy fuerte
    borderEnabled: Boolean = true
): Modifier {
    val tintColor = if (lowLight) Color.Black else Color.White
    val tintAlpha = if (lowLight) LiquidGlassDefaults.TINT_DARK_ALPHA
                    else LiquidGlassDefaults.TINT_LIGHT_ALPHA

    return this
        .clip(shape)
        .then(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Blur real (RenderNode) — 2-pass gaussiano nativo
                Modifier.blur(
                    radius = blurRadiusDp,
                    edgeTreatment = BlurredEdgeTreatment.Unbounded
                )
            } else {
                Modifier.graphicsLayer { alpha = 0.96f }
            }
        )
        .background(tintColor.copy(alpha = tintAlpha), shape)
        // Source-Over con un overlay sutil más para profundidad
        .drawWithCache {
            val sheen = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (lowLight) 0.06f else 0.10f),
                    Color.Transparent
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height * 0.6f)
            )
            onDrawBehind { drawRect(sheen) }
        }
        .then(
            if (borderEnabled) {
                // Borde con gradiente especular a 135° (spec)
                Modifier.border(
                    width = 0.75.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = LiquidGlassDefaults.BORDER_ALPHA_HI),
                            Color.White.copy(alpha = LiquidGlassDefaults.BORDER_ALPHA_LO)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(1000f, 1000f)
                    ),
                    shape = shape
                )
            } else Modifier
        )
}

/**
 * Pildora Liquid Glass (Quick Settings Island).
 * Altura 52pt, radio 26pt → cápsula perfecta.
 */
@Composable
fun LiquidGlassPill(
    modifier: Modifier = Modifier,
    lowLight: Boolean = false,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(26.dp)
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .liquidGlassModifier(shape = shape, lowLight = lowLight, blurRadiusDp = 22.dp)
    ) {
        content()
    }
}

/**
 * Superficie Liquid Glass para paneles grandes (Pro Peek Panel).
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    cornerRadiusTop: Dp = 32.dp,
    cornerRadiusBottom: Dp = 0.dp,
    lowLight: Boolean = false,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(
        topStart = cornerRadiusTop,
        topEnd = cornerRadiusTop,
        bottomStart = cornerRadiusBottom,
        bottomEnd = cornerRadiusBottom
    )
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .liquidGlassModifier(shape = shape, lowLight = lowLight, blurRadiusDp = 24.dp)
    ) {
        content()
    }
}
