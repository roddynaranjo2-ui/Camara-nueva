package com.rodyto.lenspro.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import java.lang.ref.WeakReference

/* ================================================================
 *  BlurredBackdrop.kt · v1.0 Premium
 *
 *  Crea el efecto "letterbox blureado en vivo":
 *  Las áreas superior e inferior (fuera del recuadro 3:4 del preview)
 *  muestran una versión MUY DESENFOCADA, en tiempo real, del preview.
 *
 *  Implementación robusta:
 *   • API 31+ : RenderEffect.createBlurEffect (HW-accelerated, sin coste de CPU)
 *               aplicado a un TextureView que replica el contenido del SurfaceView
 *               via PixelCopy ticker (~20 fps).
 *   • API <31 : Fallback con gradient oscuro estilo letterbox premium —
 *               aún se ve elegante (no se queda en "negro plano").
 *
 *  El ticker corre SOLO mientras la View está adjunta al árbol Compose,
 *  con DisposableEffect garantizando liberación de recursos.
 * ================================================================ */

/**
 * Composable público — coloca esto detrás del SurfaceView principal.
 * Se encarga internamente de mirror+blur de los frames de cámara
 * usando un WeakReference al SurfaceView principal.
 */
@Composable
fun BlurredBackdropLayer(
    sourceSurfaceRef: () -> SurfaceView?,
    modifier: Modifier = Modifier
) {
    if (Build.VERSION.SDK_INT >= 31) {
        // Camino HW: TextureView espejo + RenderEffect blur 40px
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { ctx -> MirroredBlurView(ctx) },
            update = { view -> view.attachSource(sourceSurfaceRef()) }
        )
    } else {
        // Fallback elegante: gradient oscuro premium (no parece "roto")
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color(0xFF0A0A0A),
                        0.5f to Color(0xFF1A1A1A),
                        1.0f to Color(0xFF0A0A0A)
                    )
                )
        )
    }
}

/**
 * View nativa que captura periódicamente el contenido del SurfaceView
 * principal vía `PixelCopy` y lo dibuja sobre sí misma con RenderEffect.blur.
 * Eficiente: copia ~20 fps (cada 50ms), buffer reutilizado.
 */
@RequiresApi(31)
internal class MirroredBlurView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var sourceRef: WeakReference<SurfaceView>? = null
    private var mirrorBitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }
    private var attached = false
    private val COPY_INTERVAL_MS = 50L      // ~20 fps copy
    private val DOWNSCALE = 6                // 6x más pequeño → blur rápido
    
    private var consecutiveErrors = 0
    private var lastErrorTime = 0L

    init {
        // RenderEffect blur 40px — heavy and beautiful
        setRenderEffect(
            RenderEffect.createBlurEffect(
                40f, 40f, Shader.TileMode.CLAMP
            )
        )
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun attachSource(source: SurfaceView?) {
        if (source == null) return
        if (sourceRef?.get() === source) return
        sourceRef = WeakReference(source)
    }

    // FIX O-01: Usar Runnable con postDelayed en lugar de Choreographer para reducir overhead
    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (!attached) return
            attemptPixelCopy()
            handler?.postDelayed(this, COPY_INTERVAL_MS) ?: postDelayed(this, COPY_INTERVAL_MS)
        }
    }

    private fun attemptPixelCopy() {
        val source = sourceRef?.get() ?: return
        
        // FIX A-04: Validar que la vista esté adjunta y visible
        if (!source.isAttachedToWindow || source.windowVisibility != View.VISIBLE) return
        
        val srcW = source.width
        val srcH = source.height
        if (srcW <= 0 || srcH <= 0) return
        if (!source.holder.surface.isValid) return

        // Backoff exponencial si hay errores persistentes
        if (consecutiveErrors >= 3) {
            val now = System.currentTimeMillis()
            if (now - lastErrorTime < 2000) return
        }

        val targetW = (srcW / DOWNSCALE).coerceAtLeast(64)
        val targetH = (srcH / DOWNSCALE).coerceAtLeast(64)

        val bmp = mirrorBitmap?.takeIf { it.width == targetW && it.height == targetH }
            ?: Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888).also {
                mirrorBitmap = it
            }

        try {
            android.view.PixelCopy.request(
                source,
                bmp,
                { result ->
                    if (result == android.view.PixelCopy.SUCCESS) {
                        consecutiveErrors = 0
                        invalidate()
                    } else {
                        consecutiveErrors++
                        lastErrorTime = System.currentTimeMillis()
                    }
                },
                handler ?: android.os.Handler(android.os.Looper.getMainLooper())
            )
        } catch (e: Exception) {
            consecutiveErrors++
            lastErrorTime = System.currentTimeMillis()
            android.util.Log.w("MirroredBlurView", "PixelCopy failed: ${e.message}")
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = mirrorBitmap ?: return
        // Stretch upscale → over-blur effect natural
        canvas.drawBitmap(
            bmp,
            null,
            android.graphics.Rect(0, 0, width, height),
            paint
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        handler?.post(tickerRunnable) ?: post(tickerRunnable)
    }

    override fun onDetachedFromWindow() {
        attached = false
        handler?.removeCallbacks(tickerRunnable) ?: removeCallbacks(tickerRunnable)
        mirrorBitmap?.recycle()
        mirrorBitmap = null
        super.onDetachedFromWindow()
    }
}
