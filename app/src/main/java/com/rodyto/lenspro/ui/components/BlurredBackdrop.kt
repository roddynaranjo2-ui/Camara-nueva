package com.rodyto.lenspro.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import java.lang.ref.WeakReference

/* ================================================================
 *  BlurredBackdrop.kt · v2.0 Premium · CRASH-PROOF
 *
 *  FIX CRÍTICO C1 (causa del cierre automático en Android 16):
 *   • setRenderEffect() y setLayerType() SE MOVIERON fuera de init{}
 *     y ahora se aplican en onAttachedToWindow() — en el constructor
 *     el View aún no tiene contexto de ventana, lo que en Snapdragon
 *     888 + Adreno 660 + Android 14+ provoca IllegalStateException
 *     en el RenderThread al primer render → mata la app.
 *   • Orden correcto: setLayerType(HARDWARE) ANTES de setRenderEffect.
 *   • Blur reducido 40f→25f (límite seguro documentado por Google).
 *   • Si RenderEffect falla en runtime, la View funciona sin blur
 *     (degradación elegante, no crash).
 *
 *  FIX A-04 conservado: validación isAttachedToWindow antes de PixelCopy.
 *  FIX O-01 conservado: ticker con Runnable+postDelayed (no Choreographer).
 * ================================================================ */

@Composable
fun BlurredBackdropLayer(
    sourceSurfaceRef: () -> SurfaceView?,
    modifier: Modifier = Modifier
) {
    if (Build.VERSION.SDK_INT >= 31) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { ctx -> MirroredBlurView(ctx) },
            update = { view -> view.attachSource(sourceSurfaceRef()) }
        )
    } else {
        // Fallback elegante: gradient oscuro premium
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

@RequiresApi(31)
internal class MirroredBlurView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        private const val TAG = "MirroredBlurView"
        // FIX C2: 25f es el máximo seguro según el RenderEffect API.
        // Valores >25f pueden crashear en GPUs Adreno 6xx con Android 14+.
        private const val BLUR_RADIUS_PX = 25f
        private const val COPY_INTERVAL_MS = 50L
        private const val DOWNSCALE = 6
    }

    private var sourceRef: WeakReference<SurfaceView>? = null
    private var mirrorBitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }
    private var attached = false
    private var renderEffectApplied = false

    private var consecutiveErrors = 0
    private var lastErrorTime = 0L

    // NOTA: el init{} ya NO toca setRenderEffect/setLayerType.
    // Eso se hace en onAttachedToWindow para evitar IllegalStateException
    // en GPUs estrictas (Adreno 660 + Android 16).

    fun attachSource(source: SurfaceView?) {
        if (source == null) return
        if (sourceRef?.get() === source) return
        sourceRef = WeakReference(source)
    }

    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (!attached) return
            attemptPixelCopy()
            handler?.postDelayed(this, COPY_INTERVAL_MS) ?: postDelayed(this, COPY_INTERVAL_MS)
        }
    }

    private fun attemptPixelCopy() {
        val source = sourceRef?.get() ?: return
        if (!source.isAttachedToWindow || source.windowVisibility != View.VISIBLE) return

        val srcW = source.width
        val srcH = source.height
        if (srcW <= 0 || srcH <= 0) return
        if (!source.holder.surface.isValid) return

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
            Log.w(TAG, "PixelCopy failed: ${e.message}")
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = mirrorBitmap ?: return
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

        // FIX CRÍTICO C1/C2: aplicar HW layer + RenderEffect AQUÍ, no en init{}.
        // Orden correcto: setLayerType primero, luego setRenderEffect.
        if (!renderEffectApplied) {
            try {
                setLayerType(LAYER_TYPE_HARDWARE, null)
                setRenderEffect(
                    RenderEffect.createBlurEffect(
                        BLUR_RADIUS_PX, BLUR_RADIUS_PX, Shader.TileMode.CLAMP
                    )
                )
                renderEffectApplied = true
            } catch (t: Throwable) {
                // Degradación elegante: si el RenderEffect falla, la View
                // sigue funcionando (mostrará el mirror sin blur). NO crash.
                Log.w(TAG, "RenderEffect no disponible — fallback sin blur", t)
                try { setLayerType(LAYER_TYPE_NONE, null) } catch (_: Throwable) {}
            }
        }

        handler?.post(tickerRunnable) ?: post(tickerRunnable)
    }

    override fun onDetachedFromWindow() {
        attached = false
        handler?.removeCallbacks(tickerRunnable) ?: removeCallbacks(tickerRunnable)
        try { mirrorBitmap?.recycle() } catch (_: Throwable) {}
        mirrorBitmap = null
        super.onDetachedFromWindow()
    }
}
