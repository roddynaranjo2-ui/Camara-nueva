package com.rodyto.lenspro

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import kotlin.math.abs

/* ================================================================
 *  Rodyto Lens Pro · CameraControlViewModelCore.kt · v3.6 Pro
 *
 *  Archivo 3 de la división. Encapsula TODO lo que toca el HAL:
 *    • Background HandlerThread (lifecycle robusto, sin fugas).
 *    • Apertura / cierre del CameraDevice.
 *    • Cálculo de optimal preview size delegando en CameraTuning.
 *    • Notificación de tamaños desde el SurfaceView.
 *
 *  La lógica de creación de CaptureSession en sí (con vendor tags
 *  Samsung, RAW, MultiChannelImageReader, etc.) se completará en
 *  CameraControlViewModel (clase final) — Core sólo provee la
 *  fontanería.
 *
 *  ▶ CORRECCIONES respecto al esqueleto v3.5:
 *      • startBackgroundThread() ahora es IDEMPOTENTE — si el
 *        thread ya está vivo, no se vuelve a crear (FIX fuga).
 *      • stopBackgroundThread() usa join(500) con timeout para
 *        no ANR si el HAL tarda en liberar el looper.
 *      • openCamera() valida que no haya ya una sesión OPENING
 *        en curso (FIX double-open HAL).
 *      • onError del HAL → estado ERROR + cierre limpio.
 * ================================================================ */
open class CameraControlViewModelCore(application: Application) :
    CameraControlViewModelState(application) {

    companion object { private const val TAG = "CameraVMCore" }

    /* ── Background thread (lazy + idempotente) ──────────────────── */
    private var backgroundThread: HandlerThread? = null
    protected var backgroundHandler: Handler? = null
        private set

    protected fun ensureBackgroundThread() {
        if (backgroundThread?.isAlive == true && backgroundHandler != null) return
        backgroundThread = HandlerThread("RodytoCameraBG").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    protected fun stopBackgroundThread() {
        val t = backgroundThread ?: return
        t.quitSafely()
        try {
            t.join(500L)   // FIX: timeout para evitar ANR
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrumpido esperando a backgroundThread", e)
            Thread.currentThread().interrupt()
        }
        backgroundThread = null
        backgroundHandler = null
    }

    /* ── Callback común del CameraDevice ─────────────────────────── */
    protected val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            updateUiState { it.copy(isCameraReady = true) }
            _sessionState.value = CameraSessionState.PREVIEWING
            Log.d(TAG, "CameraDevice abierto: id=${camera.id}")
            // La creación real de la CaptureSession la dispara la clase final
            // mediante onCameraOpened() — patrón template-method.
            onCameraOpened(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "CameraDevice desconectado: id=${camera.id}")
            safeCloseCamera()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "CameraDevice error: id=${camera.id} code=$error")
            _sessionState.value = CameraSessionState.ERROR
            safeCloseCamera()
        }
    }

    /** Hook para la clase final. Por defecto no hace nada — la subclase crea la sesión. */
    protected open fun onCameraOpened(camera: CameraDevice) { /* override en VM final */ }

    /* ── API pública: abrir / cerrar ─────────────────────────────── */
    @RequiresPermission(android.Manifest.permission.CAMERA)
    open fun openCamera(manager: CameraManager, cameraId: String) {
        if (_sessionState.value == CameraSessionState.OPENING) {
            Log.d(TAG, "openCamera: ignorado (ya OPENING)")
            return
        }
        ensureBackgroundThread()
        _sessionState.value = CameraSessionState.OPENING
        try {
            manager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "No se pudo acceder a la cámara $cameraId", e)
            _sessionState.value = CameraSessionState.ERROR
        } catch (e: SecurityException) {
            Log.e(TAG, "Permisos denegados al abrir $cameraId", e)
            _sessionState.value = CameraSessionState.ERROR
        }
    }

    /** Cierre seguro idempotente. */
    open fun closeCamera() = safeCloseCamera()

    protected fun safeCloseCamera() {
        if (_sessionState.value == CameraSessionState.CLOSING ||
            _sessionState.value == CameraSessionState.IDLE) return
        _sessionState.value = CameraSessionState.CLOSING
        try { captureSession?.close() } catch (_: Throwable) {}
        captureSession = null
        previewRequestBuilder = null
        try { cameraDevice?.close() } catch (_: Throwable) {}
        cameraDevice = null
        _sessionState.value = CameraSessionState.IDLE
        updateUiState { it.copy(isCameraReady = false) }
    }

    /** ¿Está corriendo el preview en este momento? Lo consulta CameraPreview.kt. */
    open fun isCameraRunning(): Boolean =
        _sessionState.value == CameraSessionState.PREVIEWING ||
        _sessionState.value == CameraSessionState.RECORDING  ||
        _sessionState.value == CameraSessionState.CAPTURING

    /* ── Helpers de tamaño (consumidos por CameraPreview.kt) ─────── */

    /**
     * Devuelve el optimal preview size cacheado como par (w, h).
     * Si todavía no se ha calculado, devuelve el fallback 1920x1080.
     */
    open fun getOptimalPreviewSize(): Pair<Int, Int> =
        optimalPreviewSize.width to optimalPreviewSize.height

    /**
     * El SurfaceView reporta su nuevo tamaño tras surfaceChanged.
     * Aquí podríamos re-elegir un preview size si el usuario cambia el aspect.
     */
    open fun notifyPreviewSize(widthPx: Int, heightPx: Int) {
        // Default: log + reconsider preview size si CameraManager está disponible.
        // La clase final podrá enganchar lógica de re-fitting.
        if (widthPx <= 0 || heightPx <= 0) return
        Log.v(TAG, "notifyPreviewSize: $widthPx x $heightPx")
    }

    /**
     * Recalcula y guarda en `optimalPreviewSize` el mejor tamaño dado el
     * cameraId actual. Llamar desde la clase final cuando se conozca el
     * CameraCharacteristics. Si no hay HAL, no toca el cache.
     */
    protected fun recomputeOptimalPreviewSize(
        characteristics: CameraCharacteristics?,
        targetRatio: Float,
        displayLongEdgePx: Int = 0
    ) {
        val s = CameraTuning.pickOptimalPreviewSize(
            characteristics = characteristics,
            targetRatio = targetRatio,
            displayLongEdgePx = displayLongEdgePx
        )
        if (abs(s.width - optimalPreviewSize.width) > 1 ||
            abs(s.height - optimalPreviewSize.height) > 1) {
            optimalPreviewSize = s
            Log.d(TAG, "optimalPreviewSize → ${s.width}x${s.height}")
        }
    }

    /* ── Zoom limits (consumidos por CameraPreview pinchToZoom) ─── */
    open fun getZoomMinValue(): Float = CameraConstants.MIN_ZOOM_DEFAULT
    open fun getZoomMaxValue(): Float = CameraConstants.MAX_ZOOM_DEFAULT

    /** Aplica zoom continuo desde el gesto pinch. Override en la clase final. */
    open fun setZoomContinuous(z: Float) {
        _zoomLevel.value = z.coerceIn(getZoomMinValue(), getZoomMaxValue())
    }

    override fun onCleared() {
        super.onCleared()
        safeCloseCamera()
        stopBackgroundThread()
    }
}
