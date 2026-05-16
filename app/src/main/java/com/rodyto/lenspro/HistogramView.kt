package com.rodyto.lenspro

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * HistogramView — render Compose del histograma de luminancia.
 *
 * Diseño minimalista glass: panel translúcido con barras finas, sin números.
 * El padre lo posiciona como overlay esquinado (top-right del preview).
 */
@Composable
fun HistogramView(
    bins: IntArray?,
    palette: GlassPalette,
    modifier: Modifier = Modifier
) {
    if (bins == null || bins.isEmpty()) return
    val max = (bins.max().coerceAtLeast(1)).toFloat()

    Box(
        modifier = modifier
            .width(112.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .liquidGlass(palette, RoundedCornerShape(12.dp), strong = true)
            .padding(horizontal = 6.dp, vertical = 5.dp)
    ) {
        Canvas(modifier = Modifier.matchParentSizePadding()) {
            val n = bins.size
            val barW = size.width / n
            for (i in 0 until n) {
                val h = (bins[i] / max) * size.height
                drawRect(
                    color = palette.accent.copy(alpha = 0.85f),
                    topLeft = Offset(i * barW, size.height - h),
                    size = Size(barW * 0.85f, h)
                )
            }
            // Línea base
            drawLine(
                color = palette.onGlassSecondary.copy(alpha = 0.35f),
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 0.8f
            )
        }
    }
}

// Helper para igualar el tamaño del Canvas al padre menos el padding
private fun Modifier.matchParentSizePadding(): Modifier = this
    .then(Modifier)
