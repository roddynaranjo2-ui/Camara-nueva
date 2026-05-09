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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CameraControlViewModel : ViewModel() {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraManager: CameraManager? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageReader: ImageReader? = null

    private val _currentLens = MutableStateFlow("1x")
    val currentLens = _currentLens.asStateFlow()

    private val _focusLocked = MutableStateFlow(false)
    val focusLocked = _focusLocked.asStateFlow()

    private val _cameraMode = MutableStateFlow("FOTO")
    val cameraMode = _cameraMode.asStateFlow()

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

    // Detecta automáticamente los IDs físicos reales del S21 FE por longitud focal
    private fun detectPhysicalCameraIds(context: Context): Map<String, String> {
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

        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
            if (id != wideAngleId && id != telephotoId) {
                mainId = id
                break
            }
        }

        if (mainId == null) mainId = telephotoId

        result["0.5x"] = wideAngleId ?: "2"
        result["1x"]   = mainId      ?: "0"
        result["3x"]   = telephotoId ?: "3"

        Log.d("RodytoLensPro", "Mapa de lentes detectado: $result")
        return result
    }

    @SuppressLint("MissingPermission")
    fun startCameraSession(context: Context, surface: Surface, lensLabel: String) {
        // Si la cámara ya está abierta y solo cambiamos de lente,
        // no cerramos el dispositivo. Solo reiniciamos la sesión.
        if (cameraDevice != null && activeSurface == surface && _currentLens.value != lensLabel) {
            _currentLens.value = lensLabel
            createCaptureSession(surface, lensLabel, context)
            return
        }

        closeCamera()
        activeSurface = surface
        _currentLens.value = lensLabel
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Configurar ImageReader para captura JPEG en resolución alta
        imageReader = ImageReader.newInstance(4000, 3000, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    MediaStorageManager.savePhoto(context, bytes)
                    Log.d("RodytoLensPro", "Foto guardada correctamente")
                } catch (e: Exception) {
                    Log.e("RodytoLensPro", "Error procesando imagen", e)
                } finally {
                    image.close()
                }
            }, backgroundHandler)
        }

        // En Samsung One UI siempre abrimos el ID lógico "0" (agrupa todas las traseras)
        val logicalId = "0"

        try {
            cameraManager?.openCamera(logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(surface, lensLabel, context)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    closeCamera()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e("RodytoLensPro", "Error crítico en cámara: $error")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("RodytoLensPro", "Error al abrir cámara lógica", e)
        }
    }

    private fun createCaptureSession(previewSurface: Surface, lensLabel: String, context: Context) {
        try {
            val physicalIds = detectPhysicalCameraIds(context)
            val targetPhysicalId = physicalIds[lensLabel] ?: "0"

            Log.d("RodytoLensPro", "Enrutando a sensor físico ID=$targetPhysicalId para lente=$lensLabel")

            // Configurar salidas con enrutamiento al sensor físico específico
            val previewOutputConfig = OutputConfiguration(previewSurface)
            val imageSurface = imageReader?.surface
            val imageOutputConfig = imageSurface?.let { OutputConfiguration(it) }

            // La magia: forzar el ISP del Snapdragon 888 a usar el lente físico correcto
            if (targetPhysicalId != "0") {
                previewOutputConfig.setPhysicalCameraId(targetPhysicalId)
                imageOutputConfig?.setPhysicalCameraId(targetPhysicalId)
            }

            val outputConfigs = mutableListOf(previewOutputConfig)
            imageOutputConfig?.let { outputConfigs.add(it) }

            val previewRequestBuilder = cameraDevice
                ?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                ?.apply { addTarget(previewSurface) }

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                { it.run() }, // Executor en el hilo actual
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        previewRequestBuilder?.let {
                            session.setRepeatingRequest(it.build(), null, backgroundHandler)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("RodytoLensPro", "Fallo al configurar sesión para lente $lensLabel")
                    }
                }
            )

            cameraDevice?.createCaptureSession(sessionConfig)

        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error al crear sesión de captura: ${e.message}")
        }
    }

    fun capturePhoto() {
        if (cameraDevice == null || captureSession == null || imageReader == null) {
            Log.w("RodytoLensPro", "capturePhoto llamado antes de que la cámara esté lista")
            return
        }
        try {
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.surface)
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, 90) // Orientación portrait S21 FE

            captureSession?.capture(captureBuilder.build(), null, backgroundHandler)
            Log.d("RodytoLensPro", "Captura disparada")
        } catch (e: CameraAccessException) {
            Log.e("RodytoLensPro", "Error al capturar foto", e)
        }
    }

    fun switchLens(context: Context, lens: String) {
        val surface = activeSurface ?: return
        startCameraSession(context, surface, lens)
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
        backgroundThread?.quitSafely()
    }
}