package com.rodyto.lenspro

import android.content.Context
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CameraControlViewModel : ViewModel() {

    companion object {
        private const val TAG = "LensPro"
    }

    private val cameraMutex = Mutex()

    private var cameraDevice: CameraDevice? = null

    private var captureSession: CameraCaptureSession? = null

    private var imageReader: ImageReader? = null

    private var mediaRecorder: MediaRecorder? = null

    private var cameraManager: CameraManager? = null

    private var previewSurface: Surface? = null

    private var currentCameraId: String = "0"

    private var isStartingCamera = false

    private var isCameraActive = false

    private val _currentLens = MutableStateFlow("BACK")

    val currentLens: StateFlow<String> = _currentLens.asStateFlow()

    private val _isRecording = MutableStateFlow(false)

    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    fun setLens(lens: String) {
        _currentLens.value = lens
    }

    fun isCameraRunning(): Boolean {
        return isCameraActive
    }

    fun startCameraSession(
        context: Context,
        surface: Surface,
        lens: String
    ) {

        viewModelScope.launch(Dispatchers.IO) {

            cameraMutex.withLock {

                try {

                    if (isStartingCamera || isCameraActive) {
                        return@withLock
                    }

                    isStartingCamera = true

                    previewSurface = surface

                    cameraManager =
                        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

                    currentCameraId = when (lens) {
                        "FRONT" -> getFrontCameraId(cameraManager!!)
                        else -> getBackCameraId(cameraManager!!)
                    }

                    closeCamera()

                    cameraManager?.openCamera(
                        currentCameraId,
                        object : CameraDevice.StateCallback() {

                            override fun onOpened(camera: CameraDevice) {

                                try {

                                    cameraDevice = camera

                                    val targetSurface = previewSurface

                                    if (targetSurface == null || !targetSurface.isValid) {

                                        isStartingCamera = false
                                        isCameraActive = false

                                        Log.e(TAG, "Surface inválida")

                                        return
                                    }

                                    camera.createCaptureSession(
                                        listOf(targetSurface),
                                        object : CameraCaptureSession.StateCallback() {

                                            override fun onConfigured(
                                                session: CameraCaptureSession
                                            ) {

                                                try {

                                                    captureSession = session

                                                    val request =
                                                        camera.createCaptureRequest(
                                                            CameraDevice.TEMPLATE_PREVIEW
                                                        ).apply {

                                                            addTarget(targetSurface)

                                                            set(
                                                                CaptureRequest.CONTROL_MODE,
                                                                CameraMetadata.CONTROL_MODE_AUTO
                                                            )

                                                            set(
                                                                CaptureRequest.CONTROL_AF_MODE,
                                                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                                            )

                                                            set(
                                                                CaptureRequest.CONTROL_AE_MODE,
                                                                CaptureRequest.CONTROL_AE_MODE_ON
                                                            )
                                                        }

                                                    session.setRepeatingRequest(
                                                        request.build(),
                                                        null,
                                                        null
                                                    )

                                                    isCameraActive = true
                                                    isStartingCamera = false

                                                    Log.d(TAG, "Cámara iniciada correctamente")

                                                } catch (e: Exception) {

                                                    isCameraActive = false
                                                    isStartingCamera = false

                                                    Log.e(
                                                        TAG,
                                                        "Error configurando preview",
                                                        e
                                                    )
                                                }
                                            }

                                            override fun onConfigureFailed(
                                                session: CameraCaptureSession
                                            ) {

                                                isCameraActive = false
                                                isStartingCamera = false

                                                Log.e(
                                                    TAG,
                                                    "Falló configuración CameraCaptureSession"
                                                )
                                            }
                                        },
                                        null
                                    )

                                } catch (e: Exception) {

                                    isCameraActive = false
                                    isStartingCamera = false

                                    Log.e(TAG, "Error creando sesión", e)
                                }
                            }

                            override fun onDisconnected(camera: CameraDevice) {

                                try {
                                    camera.close()
                                } catch (_: Exception) {
                                }

                                isCameraActive = false
                                isStartingCamera = false

                                Log.e(TAG, "Cámara desconectada")
                            }

                            override fun onError(
                                camera: CameraDevice,
                                error: Int
                            ) {

                                try {
                                    camera.close()
                                } catch (_: Exception) {
                                }

                                isCameraActive = false
                                isStartingCamera = false

                                Log.e(TAG, "Error cámara: $error")
                            }

                        },
                        null
                    )

                } catch (e: SecurityException) {

                    isCameraActive = false
                    isStartingCamera = false

                    Log.e(TAG, "Sin permisos de cámara", e)

                } catch (e: Exception) {

                    isCameraActive = false
                    isStartingCamera = false

                    Log.e(TAG, "Error iniciando cámara", e)
                }
            }
        }
    }

    fun closeCamera() {

        try {

            captureSession?.stopRepeating()

        } catch (_: Exception) {
        }

        try {

            captureSession?.abortCaptures()

        } catch (_: Exception) {
        }

        try {

            captureSession?.close()

        } catch (_: Exception) {
        }

        captureSession = null

        try {

            cameraDevice?.close()

        } catch (_: Exception) {
        }

        cameraDevice = null

        try {

            imageReader?.close()

        } catch (_: Exception) {
        }

        imageReader = null

        try {

            mediaRecorder?.reset()
            mediaRecorder?.release()

        } catch (_: Exception) {
        }

        mediaRecorder = null

        isCameraActive = false
        isStartingCamera = false
    }

    fun startRecording() {

        if (_isRecording.value) {
            return
        }

        try {

            mediaRecorder?.start()

            _isRecording.value = true

        } catch (e: Exception) {

            Log.e(TAG, "Error iniciando grabación", e)
        }
    }

    fun stopRecording() {

        if (!_isRecording.value) {
            return
        }

        try {

            mediaRecorder?.stop()

        } catch (e: Exception) {

            Log.e(TAG, "Error deteniendo grabación", e)

        } finally {

            try {

                mediaRecorder?.reset()
                mediaRecorder?.release()

            } catch (_: Exception) {
            }

            mediaRecorder = null

            _isRecording.value = false
        }
    }

    private fun getBackCameraId(manager: CameraManager): String {

        return manager.cameraIdList.firstOrNull { id ->

            val chars = manager.getCameraCharacteristics(id)

            chars.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK

        } ?: "0"
    }

    private fun getFrontCameraId(manager: CameraManager): String {

        return manager.cameraIdList.firstOrNull { id ->

            val chars = manager.getCameraCharacteristics(id)

            chars.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT

        } ?: "1"
    }

    override fun onCleared() {
        super.onCleared()
        closeCamera()
    }
}