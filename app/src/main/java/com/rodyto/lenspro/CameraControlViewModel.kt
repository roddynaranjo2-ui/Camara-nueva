package com.rodyto.lenspro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/**
 * ViewModel de cámara endurecido para producción.
 * - Nunca intenta abrir physical camera IDs directamente.
 * - Usa una cámara trasera lógica/standalone válida.
 * - Evita carreras de apertura al recrear la Surface.
 * - Usa tamaños JPEG soportados por la cámara.
 */
class CameraControlViewModel : ViewModel() {

    init {
        try {
            System.loadLibrary("rodytolenspro")
        } catch (e: UnsatisfiedLinkError) {
            Log.w("RodytoLensPro", "Librería nativa opcional no disponible", e)
        }
        startBackgroundThread()
    }

    private data class CameraIds(
        val backCameraId: String,
        val frontCameraId: String?
    )

    private data class CameraConfig(
        val jpegSize: Size,
        val sensorRect: Rect?,
        val zoomRatioRange: Range<Float>?,
        val maxDigitalZoom: Float
    )

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraManager: CameraManager? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var currentSurface: Surface? = null
    private var currentCameraId: String? = null
    private var currentCameraConfig: CameraConfig? = null
    private var cachedCameraIds: CameraIds? = null
    private var isStartingCamera = false

    private val _currentLens = MutableStateFlow("1x")
    val currentLens = _currentLens.asStateFlow()

    private val _focusLocked = MutableStateFlow(false)
    val focusLocked = _focusLocked.asStateFlow()

    private val _cameraMode = MutableStateFlow("FOTO")
    val cameraMode = _cameraMode.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera = _isFrontCamera.asStateFlow()

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
        } catch (e: InterruptedException) {
            Log.e("RodytoLensPro", "Error al detener el hilo de cámara", e)
        } finally {
            backgroundThread = null
            backgroundHandler = null
        }
    }

    private fun resolveCameraIds(context: Context): CameraIds {
        cachedCameraIds?.let { return it }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var bestBackCameraId: String? = null
        var bestBackIsLogical = false
        var frontCameraId: String? = null

        for (cameraId in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> {
                    if (frontCameraId == null) {
                        frontCameraId = cameraId
                    }
                }

                CameraCharacteristics.LENS_FACING_BACK -> {
                    val capabilities = characteristics
                        .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                        .orEmpty()

                    val isLogical = capabilities.contains(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                    )

                    if (bestBackCameraId == null || (!bestBackIsLogical && isLogical)) {
                        bestBackCameraId = cameraId
                        bestBackIsLogical = isLogical
                    }
                }
            }
        }

        val resolved = CameraIds(
            backCameraId = bestBackCameraId ?: manager.cameraIdList.first(),
            frontCameraId = frontCameraId
        )
        cachedCameraIds = resolved
        return resolved
    }

    private fun getCameraConfig(manager: CameraManager, cameraId: String): CameraConfig {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val jpegSize = streamMap
            ?.getOutputSizes(ImageFormat.JPEG)
            ?.maxByOrNull { it.width * it.height }
            ?: Size(1920, 1080)

        val zoomRatioRange =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } else {
                null
            }

        val maxDigitalZoom =
            characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

        return CameraConfig(
            jpegSize = jpegSize,
            sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            zoomRatioRange = zoomRatioRange,
            maxDigitalZoom = maxDigitalZoom
        )
    }

    private fun lensToZoomRatio(lens: String): Float {
        return when (lens) {
            "0.5x" -> 0.5f
            "2x" -> 2f
            else -> 1f
        }
    }

    private fun getClampedZoomRatio(): Float {
        val config = currentCameraConfig ?: return 1f
        val requested = if (_isFrontCamera.value) 1f else lensToZoomRatio(_currentLens.value)
        val minZoom = config.zoomRatioRange?.lower ?: 1f
        val maxZoom = config.zoomRatioRange?.upper ?: max(1f, config.maxDigitalZoom)
        return requested.coerceIn(minZoom, maxZoom)
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        val config = currentCameraConfig ?: return
        val zoomRatio = getClampedZoomRatio()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && config.zoomRatioRange != null) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
            return
        }

        val sensorRect = config.sensorRect ?: return
        val safeZoom = zoomRatio.coerceAtLeast(1f)

        val cropWidth = (sensorRect.width() / safeZoom).toInt()
        val cropHeight = (sensorRect.height() / safeZoom).toInt()
        val left = (sensorRect.width() - cropWidth) / 2
        val top = (sensorRect.height() - cropHeight) / 2

        builder.set(
            CaptureRequest.SCALER_CROP_REGION,
            Rect(left, top, left + cropWidth, top + cropHeight)
        )
    }

    @SuppressLint("MissingPermission")
    fun startCameraSession(
        context: Context,
        surface: Surface,
        lensId: String = _currentLens.value
    ) {
        _currentLens.value = lensId
        currentSurface = surface

        if (isStartingCamera) return
        isStartingCamera = true

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraManager = manager

        val ids = resolveCameraIds(context)
        val targetCameraId = if (_isFrontCamera.value) ids.frontCameraId ?: ids.backCameraId else ids.backCameraId

        if (cameraDevice != null && currentCameraId == targetCameraId) {
            updatePreview()
            isStartingCamera = false
            return
        }

        closeCamera()

        try {
            currentCameraId = targetCameraId
            currentCameraConfig = getCameraConfig(manager, targetCameraId)

            manager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                    isStartingCamera = false
                }

                override fun onDisconnected(camera: CameraDevice) {
                    closeCamera()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    closeCamera()
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error al abrir cámara $targetCameraId", e)
            isStartingCamera = false
        }
    }

    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val surface = currentSurface ?: return
        val config = currentCameraConfig ?: return

        try {
            imageReader = ImageReader.newInstance(
                config.jpegSize.width,
                config.jpegSize.height,
                ImageFormat.JPEG,
                2
            )

            val targets = listOf(surface, imageReader!!.surface)
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    updatePreview()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("RodytoLensPro", "Configuración de sesión fallida")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error creando sesión de captura", e)
        }
    }

    private fun updatePreview() {
        val session = captureSession ?: return
        val surface = currentSurface ?: return

        try {
            val builder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)

            applyZoom(builder)

            if (_focusLocked.value) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error actualizando preview", e)
        }
    }

    fun takePicture(storage: MediaStorageManager, context: Context) {
        val session = captureSession ?: return
        val reader = imageReader ?: return

        try {
            val builder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            applyZoom(builder)

            reader.setOnImageAvailableListener({
                val image = it.acquireLatestImage()
                storage.saveImage(context, image)
            }, backgroundHandler)

            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    Log.e("RodytoLensPro", "Fallo en captura de foto")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error al tomar foto", e)
        }
    }

    fun switchLens(context: Context, lens: String) {
        _currentLens.value = lens
        if (captureSession != null && cameraDevice != null) {
            updatePreview()
        } else {
            currentSurface?.takeIf { it.isValid }?.let { startCameraSession(context, it, lens) }
        }
    }

    fun toggleFrontCamera(context: Context) {
        _isFrontCamera.value = !_isFrontCamera.value
        currentSurface?.takeIf { it.isValid }?.let {
            startCameraSession(context, it, _currentLens.value)
        }
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
        } catch (_: Exception) {
        }

        try {
            captureSession?.abortCaptures()
        } catch (_: Exception) {
        }

        try {
            captureSession?.close()
            cameraDevice?.close()
            imageReader?.close()
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error al cerrar cámara", e)
        } finally {
            captureSession = null
            cameraDevice = null
            imageReader = null
            currentCameraId = null
            currentCameraConfig = null
            isStartingCamera = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeCamera()
        stopBackgroundThread()
        currentSurface = null
    }
}
