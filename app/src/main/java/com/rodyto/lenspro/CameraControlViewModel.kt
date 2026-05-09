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

    // Detecta automáticamente qué cámara es gran angular, principal y teleobjetivo
    // Funciona en cualquier dispositivo, no solo en el S21 FE
    private fun detectCameraIds(context: Context): Map<String, String> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val result = mutableMapOf<String, String>()

        var wideAngleId: String? = null
        var mainId: String? = null
        var telephotoId: String? = null
        var maxTeleFocalLength = 0f
        var minWideFocalLength = Float.MAX_VALUE

        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val focalLength = focalLengths?.firstOrNull() ?: continue

            Log.d("RodytoLensPro", "Cámara ID=$id FocalLength=$focalLength")

            if (focalLength < minWideFocalLength) {
                minWideFocalLength = focalLength
                wideAngleId = id
            }

            if (focalLength > maxTeleFocalLength) {
                maxTeleFocalLength = focalLength
                telephotoId = id
            }
        }

        // La principal (1x) es la que queda entre las dos extremas
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
            if (id != wideAngleId && id != telephotoId) {
                mainId = id
                break
            }
        }

        // Si solo hay 2 cámaras traseras, la principal es el teleobjetivo
        if (mainId == null) mainId = telephotoId

        result["0.5x"] = wideAngleId ?: "2"
        result["1x"]   = mainId      ?: "0"
        result["3x"]   = telephotoId ?: "3"

        Log.d("RodytoLensPro", "Mapa de lentes: $result")
        return result
    }

    @SuppressLint("MissingPermission")
    fun startCameraSession(context: Context, surface: Surface, lensLabel: String) {
        closeCamera()
        activeSurface = surface
        _currentLens.value = lensLabel
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val cameraIds = detectCameraIds(context)
        val targetId = cameraIds[lensLabel] ?: "0"

        Log.d("RodytoLensPro", "Abriendo cámara ID=$targetId para lente=$lensLabel")

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