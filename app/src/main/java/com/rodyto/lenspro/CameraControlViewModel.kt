package com.rodyto.lenspro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
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
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import kotlin.math.max

class CameraControlViewModel : ViewModel() {

    companion object {
        private const val TAG = "RodytoLensPro"
        init {
            try { System.loadLibrary("rodytolenspro") } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Librería nativa no disponible (modo solo Kotlin)", e)
            }
        }
    }

    // Hook nativo (opcional). Si la lib no carga, no se invoca.
    private external fun getPhysicalCameraIdsNative(): Array<String>

    // ----- Estado expuesto a la UI -----
    private val _currentLens = MutableStateFlow("1x")
    val currentLens: StateFlow<String> = _currentLens.asStateFlow()

    private val _cameraMode = MutableStateFlow("FOTO")
    val cameraMode: StateFlow<String> = _cameraMode.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _focusLocked = MutableStateFlow(false)
    val focusLocked: StateFlow<Boolean> = _focusLocked.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _flashEnabled = MutableStateFlow(false)
    val flashEnabled: StateFlow<Boolean> = _flashEnabled.asStateFlow()

    private val _exposureLevel = MutableStateFlow(0)
    val exposureLevel: StateFlow<Int> = _exposureLevel.asStateFlow()

    private val _lastPhotoUri = MutableStateFlow<Uri?>(null)
    val lastPhotoUri: StateFlow<Uri?> = _lastPhotoUri.asStateFlow()

    private val _zoomLevel = MutableStateFlow(1f)
    val zoomLevel: StateFlow<Float> = _zoomLevel.asStateFlow()

    // ----- Recursos Camera2 -----
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null
    private var previewSurface: Surface? = null
    private var cameraManager: CameraManager? = null
    private var currentCameraId: String = "0"
    private var videoUri: Uri? = null
    private var videoFd: ParcelFileDescriptor? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val cameraMutex = Mutex()
    @Volatile private var isCameraActive = false
    @Volatile private var isStartingCamera = false

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("RodytoLensProCamera").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join(500) } catch (_: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null
    }

    fun isCameraRunning(): Boolean = isCameraActive

    fun toggleFlash() {
        _flashEnabled.value = !_flashEnabled.value
        updateRepeatingRequest()
    }

    fun setCameraMode(mode: String) {
        if (_isRecording.value) return
        _cameraMode.value = mode
    }

    fun toggleFocusLock() {
        _focusLocked.value = !_focusLocked.value
        updateRepeatingRequest()
    }

    fun toggleFrontCamera(context: Context) {
        if (_isRecording.value) return
        _isFrontCamera.value = !_isFrontCamera.value
        _flashEnabled.value = false
        _focusLocked.value = false
        val surface = previewSurface ?: return
        viewModelScope.launch(Dispatchers.IO) {
            cameraMutex.withLock { closeCameraInternal() }
            startCameraSession(context, surface, _currentLens.value)
        }
    }

    fun switchLens(context: Context, lens: String) {
        _currentLens.value = lens
        _zoomLevel.value = when (lens) {
            "0.5x" -> 1f
            "1x" -> 1f
            "2x" -> 2f
            else -> 1f
        }
        updateRepeatingRequest()
    }

    fun setExposure(level: Int) {
        val range = getExposureRange() ?: return
        // Coerción explícita y simétrica
        val clamped = level.coerceIn(range.lower, range.upper)
        if (clamped == _exposureLevel.value) return
        _exposureLevel.value = clamped
        updateRepeatingRequest()
    }

    fun getExposureRange(): Range<Int>? = try {
        cameraManager?.getCameraCharacteristics(currentCameraId)
            ?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    } catch (e: Exception) {
        Log.e(TAG, "Error getExposureRange", e); null
    }

    fun tapToFocus(x: Float, y: Float, screenWidth: Int, screenHeight: Int) {
        if (_focusLocked.value) return
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        val manager = cameraManager ?: return

        try {
            val ch = manager.getCameraCharacteristics(currentCameraId)
            val arr = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val sensorX = (y / screenHeight * arr.width()).toInt().coerceIn(0, arr.width() - 1)
            val sensorY = ((1 - x / screenWidth) * arr.height()).toInt().coerceIn(0, arr.height() - 1)
            val halfSize = max(arr.width(), arr.height()) / 20
            val region = MeteringRectangle(
                (sensorX - halfSize).coerceAtLeast(0),
                (sensorY - halfSize).coerceAtLeast(0),
                halfSize * 2, halfSize * 2,
                MeteringRectangle.METERING_WEIGHT_MAX - 1
            )

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            applyBaseSettings(builder)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), null, backgroundHandler)
            updateRepeatingRequest()
        } catch (e: Exception) {
            Log.e(TAG, "Error tapToFocus", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun startCameraSession(context: Context, surface: Surface, lens: String) {
        viewModelScope.launch(Dispatchers.IO) {
            cameraMutex.withLock {
                if (isStartingCamera || isCameraActive) return@withLock
                isStartingCamera = true
                previewSurface = surface

                try {
                    val mgr = context.applicationContext
                        .getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    cameraManager = mgr

                    currentCameraId = if (_isFrontCamera.value) getFrontCameraId(mgr)
                                      else getBackCameraId(mgr)

                    mgr.openCamera(currentCameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            cameraDevice = camera
                            isCameraActive = true
                            isStartingCamera = false
                            createPreviewSession()
                        }
                        override fun onDisconnected(camera: CameraDevice) {
                            isCameraActive = false
                            isStartingCamera = false
                            closeCameraInternal()
                        }
                        override fun onError(camera: CameraDevice, error: Int) {
                            Log.e(TAG, "Error abriendo cámara: $error")
                            isCameraActive = false
                            isStartingCamera = false
                            closeCameraInternal()
                        }
                    }, backgroundHandler)
                } catch (e: Exception) {
                    isCameraActive = false
                    isStartingCamera = false
                    Log.e(TAG, "Error iniciando cámara", e)
                }
            }
        }
    }

    private fun createPreviewSession() {
        val camera = cameraDevice ?: return
        val surface = previewSurface ?: return
        try {
            val ch = cameraManager?.getCameraCharacteristics(currentCameraId)
            val map = ch?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSize = map?.getOutputSizes(ImageFormat.JPEG)
                ?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)

            imageReader?.close()
            imageReader = ImageReader.newInstance(
                jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2
            )

            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(surface, imageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        updateRepeatingRequest()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Falló CaptureSession")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error createPreviewSession", e)
        }
    }

    private fun applyBaseSettings(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            if (_flashEnabled.value && !_isFrontCamera.value)
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
            else
                CaptureRequest.CONTROL_AE_MODE_ON
        )
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, _exposureLevel.value)
        builder.set(CaptureRequest.CONTROL_AE_LOCK, _focusLocked.value)
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, _focusLocked.value)
        applyZoom(builder)
    }

    private fun updateRepeatingRequest() {
        try {
            val camera = cameraDevice ?: return
            val session = captureSession ?: return
            val surface = previewSurface ?: return
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            applyBaseSettings(builder)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error updateRepeatingRequest", e)
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        try {
            val ch = cameraManager?.getCameraCharacteristics(currentCameraId) ?: return
            val rect = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val zoom = _zoomLevel.value.coerceAtLeast(1f)
            val cropW = (rect.width() / zoom).toInt()
            val cropH = (rect.height() / zoom).toInt()
            val cropX = (rect.width() - cropW) / 2
            val cropY = (rect.height() - cropH) / 2
            builder.set(
                CaptureRequest.SCALER_CROP_REGION,
                Rect(cropX, cropY, cropX + cropW, cropY + cropH)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error applyZoom", e)
        }
    }

    fun takePicture(storage: MediaStorageManager, context: Context) {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        val appContext = context.applicationContext

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer: ByteBuffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val uri = storage.saveJpeg(appContext, bytes)
                if (uri != null) _lastPhotoUri.value = uri
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando imagen", e)
            } finally {
                image.close()
            }
        }, backgroundHandler)

        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            applyBaseSettings(builder)
            builder.set(
                CaptureRequest.JPEG_ORIENTATION,
                if (_isFrontCamera.value) 270 else 90
            )
            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    updateRepeatingRequest()
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error takePicture", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun startVideoRecording(context: Context, storage: MediaStorageManager) {
        if (_isRecording.value) return
        val camera = cameraDevice ?: return
        val surface = previewSurface ?: return
        val appContext = context.applicationContext

        try {
            captureSession?.close()
            captureSession = null

            val uri = storage.createVideoUri(appContext) ?: return
            val fd = storage.openVideoFd(appContext, uri) ?: return
            videoUri = uri
            videoFd = fd

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(appContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(fd.fileDescriptor)
                setVideoEncodingBitRate(10_000_000)
                setVideoFrameRate(30)
                setVideoSize(1920, 1080)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOrientationHint(if (_isFrontCamera.value) 270 else 90)
                prepare()
            }

            val recorderSurface = mediaRecorder!!.surface

            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(surface, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            builder.addTarget(surface)
                            builder.addTarget(recorderSurface)
                            applyBaseSettings(builder)
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                            mediaRecorder?.start()
                            _isRecording.value = true
                        } catch (e: Exception) {
                            Log.e(TAG, "Error iniciando grabación", e)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Falló sesión de grabación")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error startVideoRecording", e)
            cleanupRecorder()
        }
    }

    fun stopVideoRecording(context: Context, storage: MediaStorageManager) {
        if (!_isRecording.value) return
        val appContext = context.applicationContext
        try {
            try { mediaRecorder?.stop() } catch (e: Exception) { Log.e(TAG, "stop()", e) }
            cleanupRecorder()
            videoUri?.let { storage.finalizeVideo(appContext, it) }
            videoUri = null
            _isRecording.value = false
            createPreviewSession()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopVideoRecording", e)
        }
    }

    private fun cleanupRecorder() {
        try { mediaRecorder?.reset() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        try { videoFd?.close() } catch (_: Exception) {}
        videoFd = null
    }

    fun closeCamera() {
        viewModelScope.launch(Dispatchers.IO) {
            cameraMutex.withLock { closeCameraInternal() }
        }
    }

    private fun closeCameraInternal() {
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        cleanupRecorder()
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        isCameraActive = false
        isStartingCamera = false
    }

    private fun getBackCameraId(manager: CameraManager): String =
        manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: "0"

    private fun getFrontCameraId(manager: CameraManager): String =
        manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: manager.cameraIdList.firstOrNull() ?: "1"

    override fun onCleared() {
        super.onCleared()
        closeCameraInternal()
        stopBackgroundThread()
    }
}
