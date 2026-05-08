package com.rodyto.lenspro

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CameraControlViewModel : ViewModel() {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraManager: CameraManager? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val _currentLens = MutableStateFlow("1x")
    val currentLens = _currentLens.asStateFlow()

    private val _focusLocked = MutableStateFlow(false)
    val focusLocked = _focusLocked.asStateFlow()

    // Surface activo guardado para poder reutilizarlo al cambiar de lente
    private var activeSurface: Surface? = null

    init {
        System.loadLibrary("rodytolenspro")
        startBackgroundThread()
    }

    private external fun getPhysicalCameraIdsNative(): Array<String>?

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    @SuppressLint("MissingPermission")
    fun startCameraSession(context: Context, surface: Surface, lensLabel: String) {
        closeCamera()
        activeSurface = surface
        _currentLens.value = lensLabel
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val physicalIds = getPhysicalCameraIdsNative()

        val targetId = when (lensLabel) {
            "0.5x" -> physicalIds?.getOrNull(2) ?: "2"
            "3x"   -> physicalIds?.getOrNull(1) ?: "3"
            else   -> physicalIds?.getOrNull(0) ?: "0"
        }

        try {
            cameraManager?.openCamera(targetId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(surface)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    closeCamera()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e("RodytoLensPro", "Error crítico al abrir cámara: $error")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("RodytoLensPro", "Acceso denegado a la cámara", e)
        }
    }

    private fun createCaptureSession(surface: Surface) {
        val previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewRequestBuilder?.addTarget(surface)

        cameraDevice?.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    previewRequestBuilder?.let {
                        session.setRepeatingRequest(it.build(), null, backgroundHandler)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("RodytoLensPro", "Fallo en la configuración de la sesión de captura")
                }
            },
            backgroundHandler
        )
    }

    // Ahora switchLens sí reinicia la sesión con el nuevo sensor físico
    fun switchLens(context: Context, lens: String) {
        val surface = activeSurface ?: return
        startCameraSession(context, surface, lens)
    }

    fun toggleFocusLock() {
        _focusLocked.value = !_focusLocked.value
    }

    fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    override fun onCleared() {
        super.onCleared()
        closeCamera()
        backgroundThread?.quitSafely()
    }
}