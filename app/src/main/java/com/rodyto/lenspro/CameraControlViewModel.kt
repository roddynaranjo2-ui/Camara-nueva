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

/**
 * CameraControlViewModel optimizado para Snapdragon 888 y Android 16.
 * Implementa gestión avanzada de lentes físicos y flujos de estado reactivos.
 */
class CameraControlViewModel : ViewModel() {

    init {
        try {
            System.loadLibrary("rodytolenspro")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("RodytoLensPro", "Error cargando librería nativa", e)
        }
        startBackgroundThread()
    }

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
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
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
        cachedPhysicalIds?.let { return it }
        
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val result = mutableMapOf<String, String>()

        var wideAngleId: String? = null
        var mainId: String? = null
        var telephotoId: String? = null
        var maxTeleFocalLength = 0f
        var minWideFocalLength = Float.MAX_VALUE

        try {
            // Intento de obtener IDs nativos para optimización en Snapdragon
            val nativeIds = try { getPhysicalCameraIdsNative() } catch (e: Exception) { emptyArray<String>() }
            if (nativeIds.isNotEmpty()) {
                Log.i("RodytoLensPro", "IDs nativos detectados: ${nativeIds.joinToString()}")
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

            // El principal suele ser el que no es ni el más angular ni el más tele
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

        val safeMainId = mainId ?: "0"
        result["0.5x"] = wideAngleId ?: safeMainId
        result["1x"]   = safeMainId
        result["3x"]   = telephotoId ?: safeMainId

        cachedPhysicalIds = result
        return result
    }

    @SuppressLint("MissingPermission")
    fun startCameraSession(context: Context, surface: Surface, lensLabel: String) {
        currentSurface = surface
        _currentLens.value = lensLabel
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Si ya hay una cámara abierta, solo reconfiguramos la sesión si es necesario
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
                    Log.e("RodytoLensPro", "Error de cámara: $error")
                    closeCamera()
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("RodytoLensPro", "Error al abrir cámara", e)
        }
    }

    private fun setupImageReader(context: Context) {
        imageReader?.close()
        // Optimización: Usar resolución máxima permitida o 4K para Snapdragon 888
        imageReader = ImageReader.newInstance(3840, 2160, ImageFormat.JPEG, 2).apply {
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
        val device = cameraDevice ?: return
        
        try {
            val previewOutputConfig = OutputConfiguration(previewSurface)
            val imageOutputConfig = imageReader?.surface?.let { OutputConfiguration(it) }

            if (!_isFrontCamera.value) {
                val physicalIds = detectPhysicalCameraIds(context)
                val targetPhysicalId = physicalIds[_currentLens.value] ?: "0"
                
                // Configuración de lente físico específico para zoom óptico real
                if (targetPhysicalId != "0" && targetPhysicalId != device.id) {
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
                        updatePreview()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("RodytoLensPro", "Configuración de sesión fallida")
                    }
                }
            )
            device.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error crítico al crear sesión: ${e.message}")
        }
    }

    private fun updatePreview() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val surface = currentSurface ?: return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            
            // Optimización de AF/AE para fluidez
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            
            if (_focusLocked.value) {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                // Aquí se podrían fijar valores de exposición manual si se desea
            }

            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error al actualizar preview", e)
        }
    }

    fun capturePhoto() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return

        try {
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(reader.surface)
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            
            // Orientación automática (simplificada para este ejemplo)
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, 90) 

            // Bloquear AF antes de capturar para máxima nitidez
            captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)

            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    Log.d("RodytoLensPro", "Captura finalizada")
                    // Volver al estado de preview normal
                    updatePreview()
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("RodytoLensPro", "Error al capturar foto", e)
        }
    }

    fun switchLens(context: Context, lens: String) {
        if (_isFrontCamera.value || _currentLens.value == lens) return
        _currentLens.value = lens
        // Reiniciamos la sesión para aplicar el cambio de ID físico de cámara
        closeCamera()
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
        updatePreview()
    }

    fun closeCamera() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error al cerrar cámara", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeCamera()
        stopBackgroundThread()
        currentSurface = null
    }
}
