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

    // Estados de la interfaz
    private val _currentLens = MutableStateFlow("1x")
    val currentLens = _currentLens.asStateFlow()

    private val _focusLocked = MutableStateFlow(false)
    val focusLocked = _focusLocked.asStateFlow()

    init {
        // Carga la librería nativa de C++
        System.loadLibrary("rodytolenspro")
        startBackgroundThread()
    }

    // Enlace JNI con el código C++ (NDK) para obtener IDs físicos
    private external fun getPhysicalCameraIdsNative(): Array<String>?

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    @SuppressLint("MissingPermission")
    fun startCameraSession(context: Context, surface: Surface, lensLabel: String) {
        closeCamera() // Resetear antes de cambiar de sensor físico
        _currentLens.value = lensLabel
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Invocamos al NDK para saltar la abstracción lógica y buscar el hardware real
        val physicalIds = getPhysicalCameraIdsNative()
        
        // Mapeo de IDs físicos basado en los sensores del S21 FE (Snapdragon)
        val targetId = when (lensLabel) {
            "0.5x" -> physicalIds?.getOrNull(2) ?: "2" // Ultra Gran Angular
            "3x" -> physicalIds?.getOrNull(1) ?: "3"   // Teleobjetivo 3x óptico
            else -> physicalIds?.getOrNull(0) ?: "0"   // Sensor Principal
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

        cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                previewRequestBuilder?.let {
                    session.setRepeatingRequest(it.build(), null, backgroundHandler)
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e("RodytoLensPro", "Fallo en la configuración de la sesión de captura")
            }
        }, backgroundHandler)
    }

    fun switchLens(lens: String) {
        _currentLens.value = lens
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
