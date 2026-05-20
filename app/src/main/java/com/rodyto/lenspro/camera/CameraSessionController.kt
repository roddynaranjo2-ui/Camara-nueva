package com.rodyto.lenspro.camera

import android.content.Context
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.rodyto.lenspro.CameraSessionState
import com.rodyto.lenspro.ui.CameraUiStateHolder

/**
 * CameraSessionController — Gestiona el ciclo de vida del CameraDevice y la CaptureSession.
 * Parte del refactor de composición de la Fase 3.
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

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            stateHolder.setSessionState(CameraSessionState.PREVIEWING)
            Log.d(TAG, "CameraDevice abierto: ${camera.id}")
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
        if (backgroundThread?.isAlive == true) return
        backgroundThread = HandlerThread("CameraBG").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
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

    fun openCamera(cameraId: String) {
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

    fun closeCamera() {
        stateHolder.setSessionState(CameraSessionState.CLOSING)
        captureSession?.close()
        captureSession = null
        previewRequestBuilder = null
        cameraDevice?.close()
        cameraDevice = null
        stateHolder.setSessionState(CameraSessionState.IDLE)
    }
}
