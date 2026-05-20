package com.rodyto.lenspro

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/* ================================================================
 *  LensSelector.kt · v4.0
 *
 *  El nuevo selector de lentes vive en MainActivityHelpers.kt
 *  como `LensDial`. Mantenemos este stub para que cualquier
 *  referencia legacy a LensSelectorRow no rompa el build.
 * ================================================================ */
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
    // No-op en v4.0
    Box(modifier = modifier)
}
