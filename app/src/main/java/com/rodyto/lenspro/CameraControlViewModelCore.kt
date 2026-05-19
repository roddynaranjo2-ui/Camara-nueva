package com.rodyto.rodyto_lens_pro

import android.app.Application
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * Archivo 3: Núcleo de Hardware y Sesiones.
 * Hereda de State para tener acceso a las variables y flujos.
 */
open class CameraControlViewModelCore(application: Application) : CameraControlViewModelState(application) {

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Callbacks del dispositivo de cámara
    protected val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            // Aquí iría tu lógica original de creación de sesión
            updateUiState { it.copy(isCameraReady = true) }
            Log.d("RodytoLens", "Cámara abierta correctamente")
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
        }
    }

    protected fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    protected fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e("RodytoLens", "Error deteniendo el hilo: ${e.message}")
        }
    }

    // Lógica para abrir cámara (manteniendo tu implementación de IDs físicos)
    open fun openCamera(manager: CameraManager, cameraId: String) {
        try {
            startBackgroundThread()
            // Se usa el ID que determinaste en tus pruebas técnicas (ej. ID 52)
            manager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("RodytoLens", "No se pudo acceder a la cámara: ${e.message}")
        } catch (e: SecurityException) {
            Log.e("RodytoLens", "Permisos denegados: ${e.message}")
        }
    }

    // Función para cerrar la sesión actual sin perder el estado
    protected fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        stopBackgroundThread()
    }

    override fun onCleared() {
        super.onCleared()
        closeCamera()
    }
}
