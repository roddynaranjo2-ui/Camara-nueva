package com.rodyto.lenspro

import android.annotation.SuppressLint import android.content.Context import android.graphics.ImageFormat import android.graphics.Rect import android.hardware.camera2.CameraCaptureSession import android.hardware.camera2.CameraCharacteristics import android.hardware.camera2.CameraDevice import android.hardware.camera2.CameraManager import android.hardware.camera2.CaptureRequest import android.hardware.camera2.CaptureResult import android.hardware.camera2.DngCreator import android.hardware.camera2.TotalCaptureResult import android.media.ImageReader import android.media.MediaRecorder import android.os.Build import android.os.Environment import android.util.Log import android.util.Range import android.util.Size import android.view.Surface import androidx.lifecycle.ViewModel import androidx.lifecycle.viewModelScope import kotlinx.coroutines.Dispatchers import kotlinx.coroutines.flow.MutableStateFlow import kotlinx.coroutines.flow.StateFlow import kotlinx.coroutines.flow.asStateFlow import kotlinx.coroutines.launch import kotlinx.coroutines.sync.Mutex import kotlinx.coroutines.sync.withLock import java.io.File import java.io.FileOutputStream import java.nio.ByteBuffer import java.text.SimpleDateFormat import java.util.Date import java.util.Locale

class CameraControlViewModel : ViewModel() {

companion object {
    private const val TAG = "RodytoLensPro"
}

private var cameraDevice: CameraDevice? = null
private var cameraCaptureSession: CameraCaptureSession? = null
private var imageReader: ImageReader? = null
private var mediaRecorder: MediaRecorder? = null

private var previewSurface: Surface? = null
private var cameraManager: CameraManager? = null

private var currentCaptureResult: TotalCaptureResult? = null

private var currentCameraId: String = "0"

private var isStartingCamera = false
private val cameraMutex = Mutex()
private var isCameraActive = false

private val _isRecording = MutableStateFlow(false)
val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

private val _zoomLevel = MutableStateFlow(1f)
val zoomLevel: StateFlow<Float> = _zoomLevel.asStateFlow()

private val _currentLens = MutableStateFlow("BACK")
val currentLens: StateFlow<String> = _currentLens.asStateFlow()

private val _flashEnabled = MutableStateFlow(false)
val flashEnabled: StateFlow<Boolean> = _flashEnabled.asStateFlow()

private val _hdrEnabled = MutableStateFlow(false)
val hdrEnabled: StateFlow<Boolean> = _hdrEnabled.asStateFlow()

private val _rawEnabled = MutableStateFlow(false)
val rawEnabled: StateFlow<Boolean> = _rawEnabled.asStateFlow()

fun isCameraRunning(): Boolean = isCameraActive

fun setLens(lens: String) {
    _currentLens.value = lens
}

fun toggleFlash() {
    _flashEnabled.value = !_flashEnabled.value
    updateRepeatingRequest()
}

fun toggleHdr() {
    _hdrEnabled.value = !_hdrEnabled.value
}

fun toggleRaw() {
    _rawEnabled.value = !_rawEnabled.value
}

fun setZoom(zoom: Float) {
    _zoomLevel.value = zoom
    updateRepeatingRequest()
}

@SuppressLint("MissingPermission")
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

                            cameraDevice = camera

                            try {
                                createPreviewSession()
                                isCameraActive = true
                                isStartingCamera = false
                            } catch (e: Exception) {
                                isCameraActive = false
                                isStartingCamera = false
                                Log.e(TAG, "Error creando preview session", e)
                            }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            isCameraActive = false
                            isStartingCamera = false
                            closeCamera()
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            isCameraActive = false
                            isStartingCamera = false
                            Log.e(TAG, "Error al abrir cámara: $error")
                            closeCamera()
                        }
                    },
                    null
                )

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

    val characteristics =
        cameraManager?.getCameraCharacteristics(currentCameraId)

    val map = characteristics?.get(
        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
    )

    val jpegSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull {
        it.width * it.height
    } ?: Size(1920, 1080)

    imageReader = ImageReader.newInstance(
        jpegSize.width,
        jpegSize.height,
        ImageFormat.JPEG,
        5
    )

    val targets = mutableListOf(surface)

    imageReader?.surface?.let {
        targets.add(it)
    }

    camera.createCaptureSession(
        targets,
        object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) {

                cameraCaptureSession = session

                updateRepeatingRequest()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {

                isCameraActive = false
                isStartingCamera = false

                Log.e(TAG, "Falló CameraCaptureSession")
            }
        },
        null
    )
}

private fun updateRepeatingRequest() {

    try {

        val camera = cameraDevice ?: return
        val session = cameraCaptureSession ?: return
        val surface = previewSurface ?: return

        val builder = camera.createCaptureRequest(
            CameraDevice.TEMPLATE_PREVIEW
        )

        builder.addTarget(surface)

        builder.set(
            CaptureRequest.CONTROL_MODE,
            CaptureRequest.CONTROL_MODE_AUTO
        )

        builder.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )

        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            if (_flashEnabled.value)
                CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            else
                CaptureRequest.CONTROL_AE_MODE_ON
        )

        applyZoom(builder)

        session.setRepeatingRequest(
            builder.build(),
            object : CameraCaptureSession.CaptureCallback() {

                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    currentCaptureResult = result
                }
            },
            null
        )

    } catch (e: Exception) {
        Log.e(TAG, "Error actualizando repeating request", e)
    }
}

private fun applyZoom(builder: CaptureRequest.Builder) {

    try {

        val manager = cameraManager ?: return

        val characteristics =
            manager.getCameraCharacteristics(currentCameraId)

        val rect = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
        ) ?: return

        val zoom = _zoomLevel.value.coerceAtLeast(1f)

        val cropW = (rect.width() / zoom).toInt()
        val cropH = (rect.height() / zoom).toInt()

        val cropX = (rect.width() - cropW) / 2
        val cropY = (rect.height() - cropH) / 2

        val zoomRect = Rect(
            cropX,
            cropY,
            cropX + cropW,
            cropY + cropH
        )

        builder.set(CaptureRequest.SCALER_CROP_REGION, zoomRect)

    } catch (e: Exception) {
        Log.e(TAG, "Error aplicando zoom", e)
    }
}

fun takePhoto(context: Context) {

    try {

        val camera = cameraDevice ?: return
        val session = cameraCaptureSession ?: return
        val imageReader = imageReader ?: return

        imageReader.setOnImageAvailableListener({ reader ->

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            try {

                val buffer: ByteBuffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)

                saveJpeg(bytes)

                if (_rawEnabled.value) {
                    saveRaw(image)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error procesando imagen", e)
            } finally {
                image.close()
            }

        }, null)

        val builder = camera.createCaptureRequest(
            CameraDevice.TEMPLATE_STILL_CAPTURE
        )

        builder.addTarget(imageReader.surface)

        builder.set(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )

        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            if (_flashEnabled.value)
                CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            else
                CaptureRequest.CONTROL_AE_MODE_ON
        )

        applyZoom(builder)

        session.capture(
            builder.build(),
            object : CameraCaptureSession.CaptureCallback() {},
            null
        )

    } catch (e: Exception) {
        Log.e(TAG, "Error tomando foto", e)
    }
}

private fun saveJpeg(bytes: ByteArray) {

    try {

        val dir = File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM
            ),
            "LensPro"
        )

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val fileName = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val file = File(dir, "IMG_$fileName.jpg")

        FileOutputStream(file).use {
            it.write(bytes)
        }

    } catch (e: Exception) {
        Log.e(TAG, "Error guardando JPEG", e)
    }
}

private fun saveRaw(image: android.media.Image) {

    try {

        val result = currentCaptureResult ?: return

        val manager = cameraManager ?: return

        val characteristics =
            manager.getCameraCharacteristics(currentCameraId)

        val dir = File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM
            ),
            "LensPro/RAW"
        )

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val fileName = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val file = File(dir, "RAW_$fileName.dng")

        DngCreator(characteristics, result).writeImage(
            FileOutputStream(file),
            image
        )

    } catch (e: Exception) {
        Log.e(TAG, "Error guardando RAW", e)
    }
}

fun startRecording(context: Context) {

    try {

        if (_isRecording.value) return

        val camera = cameraDevice ?: return
        val surface = previewSurface ?: return

        val dir = File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES
            ),
            "LensPro"
        )

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val fileName = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val videoFile = File(dir, "VID_$fileName.mp4")

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

        mediaRecorder?.apply {

            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)

            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            setOutputFile(videoFile.absolutePath)

            setVideoEncodingBitRate(30_000_000)
            setVideoFrameRate(60)
            setVideoSize(1920, 1080)

            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

            prepare()
        }

        val recorderSurface = mediaRecorder?.surface ?: return

        camera.createCaptureSession(
            listOf(surface, recorderSurface),
            object : CameraCaptureSession.StateCallback() {

                override fun onConfigured(session: CameraCaptureSession) {

                    cameraCaptureSession = session

                    try {

                        val builder = camera.createCaptureRequest(
                            CameraDevice.TEMPLATE_RECORD
                        )

                        builder.addTarget(surface)
                        builder.addTarget(recorderSurface)

                        session.setRepeatingRequest(
                            builder.build(),
                            null,
                            null
                        )

                        mediaRecorder?.start()

                        _isRecording.value = true

                    } catch (e: Exception) {
                        Log.e(TAG, "Error iniciando grabación", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Error configurando grabación")
                }
            },
            null
        )

    } catch (e: Exception) {
        Log.e(TAG, "Error startRecording", e)
    }
}

fun stopRecording() {

    try {

        if (!_isRecording.value) return

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error deteniendo grabación", e)
        }

        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) {
        }

        mediaRecorder = null

        _isRecording.value = false

        createPreviewSession()

    } catch (e: Exception) {
        Log.e(TAG, "Error stopRecording", e)
    }
}

fun closeCamera() {

    try {
        cameraCaptureSession?.stopRepeating()
    } catch (_: Exception) {
    }

    try {
        cameraCaptureSession?.abortCaptures()
    } catch (_: Exception) {
    }

    try {
        cameraCaptureSession?.close()
    } catch (_: Exception) {
    }

    cameraCaptureSession = null

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

    try {
        cameraDevice?.close()
    } catch (_: Exception) {
    }

    cameraDevice = null

    isCameraActive = false
    isStartingCamera = false
}

private fun getBackCameraId(manager: CameraManager): String {

    return manager.cameraIdList.firstOrNull {

        val characteristics = manager.getCameraCharacteristics(it)

        characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK

    } ?: "0"
}

private fun getFrontCameraId(manager: CameraManager): String {

    return manager.cameraIdList.firstOrNull {

        val characteristics = manager.getCameraCharacteristics(it)

        characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT

    } ?: "1"
}

override fun onCleared() {
    super.onCleared()
    closeCamera()
}

}