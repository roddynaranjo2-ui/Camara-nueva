package com.rodyto.lenspro

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
import androidx.compose.ui.unit.dp

/**
 * HistogramView — render Compose del histograma de luminancia.
 *
 * FIX C3: El Canvas ahora usa BoxScope.matchParentSize() (API nativa de Compose)
 * en lugar de la función vacía `matchParentSizePadding()`. Antes el Canvas se
 * quedaba con tamaño 0×0 y NO se dibujaba nada — el histograma estaba "muerto"
 * silenciosamente. Ahora se dibuja correctamente dentro del glass card.
 *
 * FIX M2: Removido import `Stroke` no usado.
 *
 * Diseño: panel translúcido glass con barras finas. El padre lo posiciona como
 * overlay esquinado (top-right del preview).
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
        // FIX C3: matchParentSize() es API OFICIAL de BoxScope: hace el Canvas del
        // mismo tamaño que el Box padre (después de aplicar su padding). Antes
        // se usaba una función vacía `matchParentSizePadding()` que solo devolvía
        // `this.then(Modifier)` → Canvas sin tamaño → nada se dibujaba.
        Canvas(modifier = Modifier.matchParentSize()) {
            val n = bins.size
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            val barW = size.width / n
            for (i in 0 until n) {
                val h = (bins[i] / max) * size.height
                drawRect(
                    color = palette.accent.copy(alpha = 0.85f),
                    topLeft = Offset(i * barW, size.height - h),
                    size = Size(barW * 0.85f, h)
                )
            }
            // Línea base sutil
            drawLine(
                color = palette.onGlassSecondary.copy(alpha = 0.35f),
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 0.8f
            )
        }
    }
}
