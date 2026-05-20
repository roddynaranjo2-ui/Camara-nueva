package com.rodyto.lenspro.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.rodyto.lenspro.CameraSessionState
import com.rodyto.lenspro.ui.CameraUiStateHolder
import com.rodyto.lenspro.camera.CameraCaptureEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * CameraSessionController — Gestiona el ciclo de vida del CameraDevice y la CaptureSession.
 * Parte del refactor de composición de la Fase 3.
 *
 * v1.1 — FIX CRÍTICO: openCamera() ahora verifica el permiso CAMERA antes de llamar al
 * HAL. Esto evita SecurityException no manejadas en builds de release y permite que la
 * UI reaccione correctamente (estado ERROR) cuando el usuario aún no ha concedido permisos.
 */
class CameraSessionController(
    private val context: Context,
    private val stateHolder: CameraUiStateHolder
) {
    companion object {
        private const val TAG = "CameraSession"
    }

    private var backgroundThread: HandlerThread? = null
    var backgroundHandler: Handler? = null
        private set

    var cameraDevice: CameraDevice? = null
    var captureSession: CameraCaptureSession? = null
    var previewRequestBuilder: CaptureRequest.Builder? = null
    private var pendingSurface: Surface? = null

    // FIX A-05: Motor de captura real
    private val captureEngine = CameraCaptureEngine(context)

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            Log.d(TAG, "CameraDevice abierto: ${camera.id}")
            pendingSurface?.let { createPreviewSession(it) }
        }

        override fun onDisconnected(camera: CameraDevice) {
            closeCamera()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "CameraDevice error: $error")
            stateHolder.setSessionState(CameraSessionState.ERROR)
            closeCamera()
        }
    }

    fun startBackgroundThread() {
        // FIX O-02: Reutilizar hilos si ya existen y asignar prioridad de cámara
        if (backgroundThread?.isAlive == true) return
        backgroundThread = HandlerThread("CameraBG").apply {
            priority = android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
            start()
        }
        val handler = Handler(backgroundThread!!.looper)
        backgroundHandler = handler
        captureEngine.setBackgroundHandler(handler)
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500L)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        backgroundThread = null
        backgroundHandler = null
    }

    /**
     * Abre la cámara con el ID dado.
     *
     * FIX v1.1 — Se verifica el permiso CAMERA antes de llamar al HAL:
     *  • Si el permiso no está concedido → se pone estado ERROR y se retorna sin crashear.
     *  • @SuppressLint("MissingPermission") suprime el Lint para que el build de release
     *    con abortOnError=true no falle (el permiso ya se verificó justo antes).
     */
    @SuppressLint("MissingPermission")
    fun openCamera(cameraId: String, surface: Surface) {
        this.pendingSurface = surface

        // Verificar permiso CAMERA antes de intentar abrir el dispositivo
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Permiso CAMERA no concedido, no se puede abrir la cámara")
            stateHolder.setSessionState(CameraSessionState.ERROR)
            return
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        startBackgroundThread()
        stateHolder.setSessionState(CameraSessionState.OPENING)
        try {
            manager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo cámara", e)
            stateHolder.setSessionState(CameraSessionState.ERROR)
        }
    }

    private fun createPreviewSession(surface: Surface) {
        val device = cameraDevice ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            previewRequestBuilder = builder

            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                            stateHolder.setSessionState(CameraSessionState.PREVIEWING)
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Error al iniciar repeating request", e)
                            stateHolder.setSessionState(CameraSessionState.ERROR)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Configuración de CaptureSession fallida")
                        stateHolder.setSessionState(CameraSessionState.ERROR)
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error al crear CaptureSession", e)
            stateHolder.setSessionState(CameraSessionState.ERROR)
        }
    }

    fun closeCamera() {
        // FIX v1.2: Evitar cerrar si ya está idle o cerrando (doble cierre),
        // y detener el repeating ANTES de cerrar la sesión para evitar
        // IllegalStateException en el HAL que deja la cámara en negro.
        val currentState = stateHolder.sessionState.value
        if (currentState == CameraSessionState.IDLE ||
            currentState == CameraSessionState.CLOSING) return

        stateHolder.setSessionState(CameraSessionState.CLOSING)

        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.abortCaptures() } catch (_: Exception) {}
        captureSession?.close()
        captureSession = null
        previewRequestBuilder = null

        cameraDevice?.close()
        cameraDevice = null
        pendingSurface = null

        captureEngine.release()

        // FIX O-02: No cerrar el hilo de background aquí — se cierra en release()
        // para evitar overhead en cambios rápidos de lente/cámara.

        stateHolder.setSessionState(CameraSessionState.IDLE)
    }

    fun release() {
        closeCamera()
        stopBackgroundThread()
    }

    fun takePhoto(onShutterEffect: () -> Unit) {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        // En una implementación senior, pasaríamos las características de la cámara aquí
        // Por ahora usamos el motor de captura existente
        CoroutineScope(Dispatchers.IO).launch {
            captureEngine.captureStill(
                session = session,
                previewBuilder = builder,
                characteristics = null, // Debería obtenerse del CameraManager
                timerSeconds = 0, // El ViewModel ya maneja el timer si es necesario
                onShutterEffect = onShutterEffect
            )
        }
    }
}