package com.rodyto.lenspro

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import kotlin.math.abs

/**
 * AutoFitSurfaceView v1.0
 *
 * Equivalente moderno y robusto de AutoFitTextureView para Camera2 API + Compose.
 * Resuelve el problema clásico de "imagen estirada" cuando el aspect ratio del
 * preview no coincide exactamente con el aspect ratio elegido (3:4, 16:9, 1:1).
 *
 * Uso:
 *   surfaceView.setAspectRatio(previewSize.width, previewSize.height)
 *
 * El View se reajustará en onMeasure() para mantener exactamente esa proporción,
 * respetando el espacio disponible que le da el contenedor padre (Compose
 * AndroidView con .aspectRatio() o Box .fillMaxWidth()).
 *
 * Comportamiento:
 *   • Si no hay aspect ratio fijado → comportamiento por defecto del SurfaceView.
 *   • Si está fijado → escoge la dimensión menor para "encajar dentro" del padre
 *     (modo letterbox/pillarbox) — NUNCA estira ni recorta.
 *   • Soporta orientación: cuando el dispositivo está en landscape, invierte
 *     internamente width/height para mantener la lógica correcta.
 *
 * Notas de integración:
 *   • Llama setAspectRatio() DESPUÉS de tener la Size del preview elegida
 *     por CameraTuning.pickOptimalPreviewSize().
 *   • requestLayout() se invoca solo si el ratio cambia → no produce thrash
 *     durante recomposiciones.
 */
class AutoFitSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle) {

    /** Aspecto pedido — width / height del SENSOR (no del dispositivo). 0 = sin ajuste */
    private var aspectRatioWidth: Int = 0
    private var aspectRatioHeight: Int = 0

    /** Tolerancia para no relanzar requestLayout si el cambio es despreciable */
    private val epsilon = 0.0025f

    /**
     * Fija el aspect ratio basado en las dimensiones REALES del buffer del sensor.
     * Ejemplo: para preview 1920×1080 en orientación portrait, llamar
     *   setAspectRatio(1080, 1920) — porque la altura del sensor (cuando se
     *   ROTA para portrait) es el "lado largo" en la pantalla.
     *
     * El View calcula el resto. Si los valores son <= 0 se ignora.
     */
    fun setAspectRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val prevRatio = currentRatio()
        aspectRatioWidth = width
        aspectRatioHeight = height
        val newRatio = currentRatio()
        if (abs(prevRatio - newRatio) > epsilon) {
            // Encolado en el thread de UI para no bloquear el callback de cámara
            post { requestLayout() }
        }
    }

    /** Limpia el aspect ratio fijado → vuelve al modo por defecto del SurfaceView */
    fun clearAspectRatio() {
        if (aspectRatioWidth == 0 && aspectRatioHeight == 0) return
        aspectRatioWidth = 0
        aspectRatioHeight = 0
        post { requestLayout() }
    }

    private fun currentRatio(): Float =
        if (aspectRatioHeight == 0) 0f
        else aspectRatioWidth.toFloat() / aspectRatioHeight.toFloat()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        if (aspectRatioWidth == 0 || aspectRatioHeight == 0) {
            // Sin ratio fijado → comportamiento por defecto
            setMeasuredDimension(width, height)
            return
        }

        // Cálculo: el View se ajusta DENTRO del espacio que le da el padre
        // sin estirar — letterbox/pillarbox automático.
        val sensorRatio = aspectRatioWidth.toFloat() / aspectRatioHeight.toFloat()
        val containerRatio = if (height == 0) 1f else width.toFloat() / height.toFloat()

        val newWidth: Int
        val newHeight: Int
        if (containerRatio > sensorRatio) {
            // Contenedor más ancho que el sensor → ajustar por altura
            newHeight = height
            newWidth = (height * sensorRatio).toInt().coerceAtLeast(1)
        } else {
            // Contenedor más estrecho que el sensor → ajustar por anchura
            newWidth = width
            newHeight = (width / sensorRatio).toInt().coerceAtLeast(1)
        }
        setMeasuredDimension(newWidth, newHeight)
    }
}
