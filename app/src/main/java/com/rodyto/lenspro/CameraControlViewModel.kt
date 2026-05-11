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
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        val maxDigitalZoom: Float,
        val exposureRange: Range<Int>?
    )

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraManager: CameraManager? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null
    private var videoParcelFileDescriptor: ParcelFileDescriptor? = null
    private var currentVideoUri: Uri? = null
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

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _flashEnabled = MutableStateFlow(false)
    val flashEnabled = _flashEnabled.asStateFlow()

    private val _exposureLevel = MutableStateFlow(0)
    val exposureLevel = _exposureLevel.asStateFlow()

    private val _lastPhotoUri = MutableStateFlow<Uri?>(null)
    val lastPhotoUri = _lastPhotoUri.asStateFlow()

    private external fun getPhysicalCameraIdsNative(): Array<String>?

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
                    if (frontCameraId == null) frontCameraId = cameraId
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

        val zoomRatioRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else null

        val maxDigitalZoom =
            characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

        val exposureRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)

        return CameraConfig(
            jpegSize = jpegSize,
            sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            zoomRatioRange = zoomRatioRange,
            maxDigitalZoom = maxDigitalZoom,
            exposureRange = exposureRange
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

    private fun applyFlash(builder: CaptureRequest.Builder) {
        if (_flashEnabled.value && !_isFrontCamera.value) {
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        } else {
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
    }

    private fun applyExposure(builder: CaptureRequest.Builder) {
        val config = currentCameraConfig ?: return
        val range = config.exposureRange ?: return
        val clamped = _exposureLevel.value.coerceIn(range.lower, range.upper)
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clamped)
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
                    createPreviewSession()
                    isStartingCamera = false
                }
                override fun onDisconnected(camera: CameraDevice) { closeCamera() }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e("RodytoLensPro", "Error al abrir cámara: $error")
                    closeCamera()
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error al abrir cámara $targetCameraId", e)
            isStartingCamera = false
        }
    }

    private fun createPreviewSession() {
        val device = cameraDevice ?: return
        val surface = currentSurface ?: return
        val config = currentCameraConfig ?: return

        try {
            imageReader?.close()
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
            Log.e("RodytoLensPro", "Error creando sesión de vista previa", e)
        }
    }

    private fun updatePreview() {
        val session = captureSession ?: return
        val surface = currentSurface ?: return

        try {
            val builder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            applyZoom(builder)
            applyFlash(builder)
            applyExposure(builder)

            if (_focusLocked.value) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error actualizando vista previa", e)
        }
    }

    fun tapToFocus(x: Float, y: Float, screenWidth: Int, screenHeight: Int) {
        val session = captureSession ?: return
        val surface = currentSurface ?: return
        val config = currentCameraConfig ?: return
        val sensorRect = config.sensorRect ?: return

        try {
            val halfSize = 150
            val sensorX = (x / screenWidth.toFloat() * sensorRect.width()).toInt()
                .coerceIn(0, sensorRect.width())
            val sensorY = (y / screenHeight.toFloat() * sensorRect.height()).toInt()
                .coerceIn(0, sensorRect.height())

            val left = maxOf(0, sensorX - halfSize)
            val top = maxOf(0, sensorY - halfSize)
            val right = minOf(sensorRect.width(), sensorX + halfSize)
            val bottom = minOf(sensorRect.height(), sensorY + halfSize)
            val focusWidth = maxOf(1, right - left)
            val focusHeight = maxOf(1, bottom - top)

            val focusRect = MeteringRectangle(
                left, top, focusWidth, focusHeight,
                MeteringRectangle.METERING_WEIGHT_MAX
            )

            // Paso 1: cancelar AF en curso
            val cancelBuilder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            cancelBuilder.addTarget(surface)
            cancelBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            session.capture(cancelBuilder.build(), null, backgroundHandler)

            // Paso 2: disparar AF hacia el punto tocado
            val focusBuilder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            focusBuilder.addTarget(surface)
            focusBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            focusBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRect))
            focusBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusRect))
            focusBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            applyZoom(focusBuilder)
            applyExposure(focusBuilder)
            applyFlash(focusBuilder)
            session.capture(focusBuilder.build(), null, backgroundHandler)

            // Paso 3: repeating con AF bloqueado en la región
            val repeatingBuilder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            repeatingBuilder.addTarget(surface)
            repeatingBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            repeatingBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRect))
            repeatingBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusRect))
            applyZoom(repeatingBuilder)
            applyExposure(repeatingBuilder)
            applyFlash(repeatingBuilder)
            session.setRepeatingRequest(repeatingBuilder.build(), null, backgroundHandler)

        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error en tapToFocus", e)
        }
    }

    fun takePicture(storage: MediaStorageManager, context: Context) {
        val session = captureSession ?: return
        val reader = imageReader ?: return

        try {
            val builder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            applyZoom(builder)
            builder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            builder.set(CaptureRequest.JPEG_ORIENTATION, if (_isFrontCamera.value) 270 else 90)

            if (_flashEnabled.value && !_isFrontCamera.value) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            }

            reader.setOnImageAvailableListener({ imageReader ->
                val image = imageReader.acquireLatestImage()
                val uri = storage.saveImage(context, image)
                uri?.let { _lastPhotoUri.value = it }
            }, backgroundHandler)

            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Log.e("RodytoLensPro", "Fallo en captura de foto")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error al tomar foto", e)
        }
    }

    fun startVideoRecording(context: Context, storage: MediaStorageManager) {
        if (_isRecording.value) return
        val surface = currentSurface ?: return
        val device = cameraDevice ?: return

        try {
            val videoUri = storage.createVideoUri(context) ?: return
            currentVideoUri = videoUri

            val pfd = context.contentResolver.openFileDescriptor(videoUri, "w") ?: return
            videoParcelFileDescriptor = pfd

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(10_000_000)
                setVideoFrameRate(30)
                setVideoSize(1920, 1080)
                setOutputFile(pfd.fileDescriptor)
                prepare()
            }

            mediaRecorder = recorder
            val recorderSurface = recorder.surface

            // Cerrar sesión actual (sin cerrar el dispositivo)
            try {
                captureSession?.stopRepeating()
                captureSession?.abortCaptures()
                captureSession?.close()
                captureSession = null
            } catch (e: Exception) {
                Log.w("RodytoLensPro", "Error cerrando sesión previa al video", e)
            }

            // Crear nueva sesión solo con preview + recorder (sin imageReader)
            val targets = listOf(surface, recorderSurface)
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val builder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        builder.addTarget(surface)
                        builder.addTarget(recorderSurface)
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        applyZoom(builder)
                        applyFlash(builder)
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        recorder.start()
                        _isRecording.value = true
                        Log.d("RodytoLensPro", "Grabación de video iniciada")
                    } catch (e: Exception) {
                        Log.e("RodytoLensPro", "Error iniciando request de grabación", e)
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e("RodytoLensPro", "Fallo configurando sesión de video")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error iniciando grabación de video", e)
            mediaRecorder?.release()
            mediaRecorder = null
            videoParcelFileDescriptor?.close()
            videoParcelFileDescriptor = null
        }
    }

    fun stopVideoRecording(context: Context, storage: MediaStorageManager) {
        if (!_isRecording.value) return
        _isRecording.value = false

        try {
            captureSession?.stopRepeating()
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error deteniendo grabación", e)
        }

        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null

            videoParcelFileDescriptor?.close()
            videoParcelFileDescriptor = null

            currentVideoUri?.let { uri ->
                storage.finalizeVideoSave(context, uri)
                currentVideoUri = null
            }
        } catch (e: Exception) {
            Log.e("RodytoLensPro", "Error finalizando video", e)
        }

        // Reiniciar sesión de preview
        val surface = currentSurface
        if (surface != null && surface.isValid) {
            createPreviewSession()
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

    fun toggleFlash() {
        _flashEnabled.value = !_flashEnabled.value
        updatePreview()
    }

    fun setExposure(level: Int) {
        val config = currentCameraConfig ?: return
        val range = config.exposureRange ?: return
        _exposureLevel.value = level.coerceIn(range.lower, range.upper)
        updatePreview()
    }

    fun getExposureRange(): Range<Int>? = currentCameraConfig?.exposureRange

    fun closeCamera() {
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.abortCaptures() } catch (_: Exception) {}
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