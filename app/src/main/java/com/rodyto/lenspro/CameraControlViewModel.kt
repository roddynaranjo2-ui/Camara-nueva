package com.rodyto.lenspro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

class CameraControlViewModel : ViewModel() {

    init {
        // Carga la librería nativa compilada por CMake
        System.loadLibrary("rodytolenspro")
        startBackgroundThread()
    }

    // Función nativa expuesta desde C++
    external fun getPhysicalCameraIdsNative(): Array<String>

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraManager: CameraManager? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var currentSurface: Surface? = null

    private val _currentLens = MutableStateFlow("1x")
    val currentLens = _currentLens.asStateFlow()

    private val _focusLocked = MutableStateFlow(false)
    val focusLocked = _focusLocked.asStateFlow()

    private val _cameraMode = MutableStateFlow("FOTO")
    val cameraMode = _cameraMode.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera = _isFrontCamera.asStateFlow()

    private var cachedPhysicalIds: Map<String, String>? = null

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e("RodytoLensPro", "Error al detener el hilo", e)
        }
    }

    private fun detectPhysicalCameraIds(context: Context): Map<String, String> {
        if (cachedPhysicalIds != null) return cachedPhysicalIds!!
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val result = mutableMapOf<String, String>()

        var wideAngleId: String? = null
        var mainId: String? = null
        var telephotoId: String? = null
        var maxTeleFocalLength = 0f
        var minWideFocalLength = Float.MAX_VALUE

        try {
            // Intentamos usar la función nativa si está disponible
            val nativeIds = getPhysicalCameraIdsNative()
            if (nativeIds.isNotEmpty()) {
                Log.i("RodytoLensPro", "IDs nativos encontrados: ${nativeIds.joinToString()}")
            }

            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val focalLength = focalLengths?.firstOrNull() ?: continue

                if (focalLength < minWideFocalLength) {
                    minWideFocalLength = focalLength
                    wideAngleId = id
                }
                if (focalLength > maxTeleFocalLength) {
                    maxTeleFocalLength = focalLength
                    telephotoId = id
                }
            }

            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                if (id != wideAngleId && id != telephotoId) {
                    mainId = id
                    break
                }
            }
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error detectando IDs físicos", e)
        }

        if (mainId == null) mainId = "0"
        
        // Mapeo dinámico, evitando hardcodear "2" o "3" si es posible
        result["0.5x"] = wideAngleId ?: mainId
        result["1x"]   = mainId
        result["3x"]   = telephotoId ?: mainId

        cachedPhysicalIds = result
        return result
    }

    @SuppressLint("MissingPermission")
    fun startCameraSession(context: Context, surface: Surface, lensLabel: String) {
        currentSurface = surface
        _currentLens.value = lensLabel
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        if (cameraDevice != null) {
            createCaptureSession(surface, context)
            return
        }

        var logicalId = "0"
        if (_isFrontCamera.value) {
             for (id in cameraManager!!.cameraIdList) {
                 val chars = cameraManager!!.getCameraCharacteristics(id)
                 if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                     logicalId = id
                     break
                 }
             }
        }

        setupImageReader(context)

        try {
            cameraManager?.openCamera(logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(surface, context)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    closeCamera()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    closeCamera()
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("RodytoLensPro", "Error al abrir cámara", e)
        }
    }

    private fun setupImageReader(context: Context) {
        imageReader?.close()
        imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                viewModelScope.launch(Dispatchers.IO) {
                    MediaStorageManager.savePhoto(context.applicationContext, bytes)
                }
            }, backgroundHandler)
        }
    }

    private fun createCaptureSession(previewSurface: Surface, context: Context) {
        if (cameraDevice == null) return
        
        try {
            val previewOutputConfig = OutputConfiguration(previewSurface)
            val imageOutputConfig = imageReader?.surface?.let { OutputConfiguration(it) }

            if (!_isFrontCamera.value) {
                val physicalIds = detectPhysicalCameraIds(context)
                val targetPhysicalId = physicalIds[_currentLens.value] ?: "0"
                
                if (targetPhysicalId != "0" && targetPhysicalId != cameraDevice?.id) {
                    try {
                        previewOutputConfig.setPhysicalCameraId(targetPhysicalId)
                        imageOutputConfig?.setPhysicalCameraId(targetPhysicalId)
                    } catch (e: Exception) {
                        Log.w("RodytoLensPro", "Lente físico no soportado directamente: $targetPhysicalId")
                    }
                }
            }

            val outputConfigs = mutableListOf<OutputConfiguration>()
            outputConfigs.add(previewOutputConfig)
            imageOutputConfig?.let { outputConfigs.add(it) }

            val executor = Executor { command -> backgroundHandler?.post(command) }

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            previewRequestBuilder?.addTarget(previewSurface)
                            previewRequestBuilder?.let {
                                session.setRepeatingRequest(it.build(), null, backgroundHandler)
                            }
                        } catch (e: Exception) {
                            Log.e("RodytoLensPro", "Error al iniciar preview", e)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("RodytoLensPro", "Configuración de sesión fallida")
                    }
                }
            )
            cameraDevice?.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error crítico al crear sesión: ${e.message}")
        }
    }

    fun capturePhoto() {
        if (cameraDevice == null || captureSession == null || imageReader == null) return
        try {
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, 90) 

            captureSession?.capture(captureBuilder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("RodytoLensPro", "Error al capturar foto", e)
        }
    }

    fun switchLens(context: Context, lens: String) {
        if (_isFrontCamera.value || _currentLens.value == lens) return
        currentSurface?.let { startCameraSession(context, it, lens) }
    }

    fun toggleFrontCamera(context: Context) {
        _isFrontCamera.value = !_isFrontCamera.value
        closeCamera()
        currentSurface?.let { startCameraSession(context, it, "1x") }
    }

    fun setCameraMode(mode: String) {
        _cameraMode.value = mode
    }

    fun toggleFocusLock() {
        _focusLocked.value = !_focusLocked.value
    }

    fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    override fun onCleared() {
        super.onCleared()
        closeCamera()
        stopBackgroundThread()
        currentSurface = null
    }
}
