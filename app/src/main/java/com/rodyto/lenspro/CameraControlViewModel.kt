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
import kotlin.math.abs
import kotlin.math.max

/* ================================================================
 *  ENUMS de configuración
 * ================================================================ */

enum class VideoResolution(val label: String, val width: Int, val height: Int) {
    HD ("HD" , 1280,  720),
    FHD("FHD", 1920, 1080),
    UHD("4K" , 3840, 2160);
}

enum class VideoFps(val label: String, val value: Int) {
    FPS30("30", 30),
    FPS60("60", 60);
}

/**
 * Modos de relación de aspecto del preview.
 * - RATIO_3_4  → predeterminado en FOTO
 * - RATIO_9_16 → predeterminado en VIDEO
 * - RATIO_1_1  → cuadrado
 * - RATIO_FULL → fill (sensor completo, puede recortar)
 */
enum class PreviewAspect(val label: String, val ratio: Float) {
    RATIO_3_4 ("3:4",  3f  / 4f),
    RATIO_9_16("9:16", 9f  / 16f),
    RATIO_1_1 ("1:1",  1f),
    RATIO_FULL("FULL", 9f  / 19.5f);
}

/* ================================================================
 *  CameraControlViewModel
 *  Motor Camera2 + Samsung HAL hybrid (S21 FE — Snapdragon 888)
 * ================================================================ */
class CameraControlViewModel : ViewModel() {

    companion object {
        private const val TAG = "RodytoLensPro"
        @Volatile private var nativeLibLoaded = false
        init {
            try {
                System.loadLibrary("rodytolenspro")
                nativeLibLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Librería nativa no disponible (modo solo Kotlin)", e)
            }
        }
    }

    @Suppress("unused")
    private external fun getPhysicalCameraIdsNative(): Array<String>

    private fun safeNativePhysicalIds(): Array<String> = try {
        if (nativeLibLoaded) getPhysicalCameraIdsNative() else emptyArray()
    } catch (e: Throwable) {
        Log.w(TAG, "Fallo getPhysicalCameraIdsNative", e); emptyArray()
    }

    // ---------------- Estado UI expuesto ----------------
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

    private val _hdrEnabled = MutableStateFlow(false)
    val hdrEnabled: StateFlow<Boolean> = _hdrEnabled.asStateFlow()

    private val _gridEnabled = MutableStateFlow(false)
    val gridEnabled: StateFlow<Boolean> = _gridEnabled.asStateFlow()

    private val _timerSeconds = MutableStateFlow(0)
    val timerSeconds: StateFlow<Int> = _timerSeconds.asStateFlow()

    private val _shutterSoundEnabled = MutableStateFlow(true)
    val shutterSoundEnabled: StateFlow<Boolean> = _shutterSoundEnabled.asStateFlow()

    private val _videoResolution = MutableStateFlow(VideoResolution.FHD)
    val videoResolution: StateFlow<VideoResolution> = _videoResolution.asStateFlow()

    private val _videoFps = MutableStateFlow(VideoFps.FPS30)
    val videoFps: StateFlow<VideoFps> = _videoFps.asStateFlow()

    private val _manualAspect = MutableStateFlow<PreviewAspect?>(null)
    val manualAspect: StateFlow<PreviewAspect?> = _manualAspect.asStateFlow()

    private val _previewAspectRatio = MutableStateFlow(3f / 4f)
    val previewAspectRatio: StateFlow<Float> = _previewAspectRatio.asStateFlow()

    private val _darkTheme = MutableStateFlow<Boolean?>(null)
    val darkTheme: StateFlow<Boolean?> = _darkTheme.asStateFlow()

    private val _hapticsEnabled = MutableStateFlow(true)
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    private val _hevcEnabled = MutableStateFlow(false)
    val hevcEnabled: StateFlow<Boolean> = _hevcEnabled.asStateFlow()

    // ---------------- Estado interno Camera2 ----------------
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null
    private var previewSurface: Surface? = null
    private var recordSurface: Surface? = null
    private var videoUri: Uri? = null
    private var videoPfd: ParcelFileDescriptor? = null
    private var currentCharacteristics: CameraCharacteristics? = null
    private var currentCameraId: String = ""
    private var sensorArea: Rect? = null
    private var maxZoom: Float = 1f

    // Thread dedicado para Camera2 (evita bloqueos en el main thread)
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private val sessionMutex = Mutex()
    @Volatile private var cameraRunning = false

    // ============================================================
    //   API PÚBLICA — control de cámara
    // ============================================================

    fun isCameraRunning(): Boolean = cameraRunning

    @SuppressLint("MissingPermission")
    fun startCameraSession(context: Context, surface: Surface, lens: String) {
        previewSurface = surface
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                if (cameraRunning) return@withLock
                try {
                    ensureThread()
                    cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val cameraId = pickCameraId(cameraManager!!, _isFrontCamera.value, lens)
                    currentCameraId = cameraId
                    currentCharacteristics = cameraManager!!.getCameraCharacteristics(cameraId)
                    sensorArea = currentCharacteristics
                        ?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    maxZoom = currentCharacteristics
                        ?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

                    cameraManager!!.openCamera(
                        cameraId,
                        object : CameraDevice.StateCallback() {
                            override fun onOpened(device: CameraDevice) {
                                cameraDevice = device
                                createPreviewSession()
                            }
                            override fun onDisconnected(device: CameraDevice) {
                                device.close(); cameraDevice = null; cameraRunning = false
                            }
                            override fun onError(device: CameraDevice, error: Int) {
                                Log.e(TAG, "openCamera error=$error")
                                device.close(); cameraDevice = null; cameraRunning = false
                            }
                        },
                        cameraHandler
                    )
                    cameraRunning = true
                } catch (e: Exception) {
                    Log.e(TAG, "startCameraSession fallo", e)
                    cameraRunning = false
                }
            }
        }
    }

    fun closeCamera() {
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                try {
                    if (_isRecording.value) safeStopMediaRecorder()
                    captureSession?.close(); captureSession = null
                    cameraDevice?.close();    cameraDevice = null
                    imageReader?.close();     imageReader = null
                    mediaRecorder?.release(); mediaRecorder = null
                    recordSurface?.release(); recordSurface = null
                } catch (e: Throwable) {
                    Log.w(TAG, "closeCamera warn", e)
                } finally {
                    cameraRunning = false
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeCamera()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    // ============================================================
    //   Toggles UI
    // ============================================================

    fun toggleFlash()         { _flashEnabled.value = !_flashEnabled.value; applyRepeatingPreview() }
    fun toggleHdr()           { _hdrEnabled.value   = !_hdrEnabled.value;   applyRepeatingPreview() }
    fun toggleGrid()          { _gridEnabled.value  = !_gridEnabled.value }
    fun toggleShutterSound()  { _shutterSoundEnabled.value = !_shutterSoundEnabled.value }
    fun toggleHaptics()       { _hapticsEnabled.value = !_hapticsEnabled.value }
    fun toggleHevc()          { _hevcEnabled.value = !_hevcEnabled.value }

    fun cycleTimer() {
        _timerSeconds.value = when (_timerSeconds.value) {
            0 -> 3; 3 -> 10; else -> 0
        }
    }

    fun cycleTheme() {
        _darkTheme.value = when (_darkTheme.value) {
            null  -> true
            true  -> false
            false -> null
        }
    }

    fun setCameraMode(mode: String) {
        if (mode !in listOf("FOTO", "VIDEO")) return
        _cameraMode.value = mode
        if (_manualAspect.value == null) {
            _previewAspectRatio.value = if (mode == "VIDEO") 9f/16f else 3f/4f
        }
        applyRepeatingPreview()
    }

    fun setManualAspect(aspect: PreviewAspect?) {
        _manualAspect.value = aspect
        _previewAspectRatio.value = aspect?.ratio
            ?: if (_cameraMode.value == "VIDEO") 9f/16f else 3f/4f
    }

    fun setVideoResolution(r: VideoResolution) { _videoResolution.value = r }
    fun setVideoFps(f: VideoFps)               { _videoFps.value = f }

    fun toggleFrontCamera(context: Context) {
        _isFrontCamera.value = !_isFrontCamera.value
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                val s = previewSurface ?: return@withLock
                captureSession?.close(); captureSession = null
                cameraDevice?.close();   cameraDevice = null
                cameraRunning = false
                startCameraSession(context, s, _currentLens.value)
            }
        }
    }

    fun switchLens(context: Context, lens: String) {
        if (_currentLens.value == lens) return
        _currentLens.value = lens
        _zoomLevel.value = when (lens) {
            "0.5x" -> 0.5f; "1x" -> 1f; "2x" -> 2f; "3x" -> 3f
            else -> 1f
        }
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                val s = previewSurface ?: return@withLock
                captureSession?.close(); captureSession = null
                cameraDevice?.close();   cameraDevice = null
                cameraRunning = false
                startCameraSession(context, s, lens)
            }
        }
    }

    /** Zoom continuo (usado por ZoomDial). */
    fun setZoom(ratio: Float) {
        val clamped = ratio.coerceIn(0.5f, max(maxZoom, 10f))
        _zoomLevel.value = clamped
        applyRepeatingPreview()
    }

    // ============================================================
    //   Exposición
    // ============================================================

    fun getExposureRange(): Range<Int>? = currentCharacteristics
        ?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)

    fun setExposure(level: Int) {
        val r = getExposureRange() ?: return
        _exposureLevel.value = level.coerceIn(r.lower, r.upper)
        applyRepeatingPreview()
    }

    // ============================================================
    //   Tap to focus + lock
    // ============================================================

    fun tapToFocus(x: Float, y: Float, screenW: Int, screenH: Int) {
        val area = sensorArea ?: return
        val session = captureSession ?: return
        val device  = cameraDevice  ?: return
        try {
            // Map viewport coords → sensor coords
            val sx = (x / screenW.toFloat() * area.width()).toInt().coerceIn(0, area.width() - 1)
            val sy = (y / screenH.toFloat() * area.height()).toInt().coerceIn(0, area.height() - 1)
            val half = 150
            val rect = Rect(
                (sx - half).coerceAtLeast(0),
                (sy - half).coerceAtLeast(0),
                (sx + half).coerceAtMost(area.width()  - 1),
                (sy + half).coerceAtMost(area.height() - 1)
            )
            val mr = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)

            val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurface?.let { b.addTarget(it) }
            b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(mr))
            b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(mr))
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            applyCommon(b)

            session.capture(b.build(), null, cameraHandler)
            // Volver a repeating tras un instante
            applyRepeatingPreview()
        } catch (e: Exception) {
            Log.w(TAG, "tapToFocus error", e)
        }
    }

    fun toggleFocusLock() {
        _focusLocked.value = !_focusLocked.value
        applyRepeatingPreview()
    }

    // ============================================================
    //   Captura JPEG
    // ============================================================

    fun takePicture(storage: MediaStorageManager, context: Context) {
        val device  = cameraDevice  ?: return
        val session = captureSession ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureImageReader()
                val targetSurface = imageReader?.surface ?: return@launch
                val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(targetSurface)
                applyCommon(b)
                CameraTuning.applyImageQuality(b, "FOTO")
                CameraTuning.applyJpegQuality(b)
                SamsungVendorTags.applyCaptureSnapshotHint(b)
                SamsungVendorTags.applyHdr(b, _hdrEnabled.value)
                SamsungVendorTags.applyProTone(b, _hdrEnabled.value)

                if (_flashEnabled.value) {
                    b.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                }

                imageReader?.setOnImageAvailableListener({ reader ->
                    val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val plane = img.planes[0]
                        val buf = plane.buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        val uri = storage.saveJpeg(context, bytes)
                        if (uri != null) _lastPhotoUri.value = uri
                    } catch (e: Throwable) {
                        Log.e(TAG, "Procesar JPEG fallo", e)
                    } finally {
                        img.close()
                    }
                }, cameraHandler)

                session.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        // Restaurar preview
                        applyRepeatingPreview()
                    }
                }, cameraHandler)
            } catch (e: Exception) {
                Log.e(TAG, "takePicture fallo", e)
            }
        }
    }

    // ============================================================
    //   Grabación de video
    // ============================================================

    @SuppressLint("MissingPermission")
    fun startVideoRecording(context: Context, storage: MediaStorageManager) {
        val device = cameraDevice ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = storage.createVideoUri(context) ?: return@launch
                val pfd = storage.openVideoFd(context, uri) ?: return@launch
                videoUri = uri
                videoPfd = pfd

                val res = _videoResolution.value
                val fps = _videoFps.value.value
                val codec = CameraTuning.preferredEncoder(_hevcEnabled.value)
                val codecLabel = CameraTuning.preferredCodecLabel(_hevcEnabled.value)
                val bitrate = VideoBitrateCalculator.preset(res, fps, codecLabel)

                // FIX: MediaRecorder(Context) fue introducido en API 29 (Q).
                // En APIs 26-28 (Oreo / Pie) se usa el constructor sin argumento
                // (deprecated pero funcional). En API 29+ se prefiere el constructor
                // con Context para una gestión de recursos más correcta.
                val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }.apply {
                    setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(pfd.fileDescriptor)
                    setVideoEncodingBitRate(bitrate)
                    setVideoFrameRate(fps)
                    setVideoSize(res.width, res.height)
                    setVideoEncoder(codec)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(192_000)
                    setAudioSamplingRate(48_000)
                    prepare()
                }
                mediaRecorder = rec
                recordSurface = rec.surface

                val previewS = previewSurface ?: return@launch
                val recS = recordSurface ?: return@launch

                val outputs = listOf(previewS, recS)

                @Suppress("DEPRECATION")
                device.createCaptureSession(
                    outputs,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            val b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            b.addTarget(previewS); b.addTarget(recS)
                            applyCommon(b)
                            CameraTuning.applyImageQuality(b, "VIDEO")
                            SamsungVendorTags.applyBase(b, "VIDEO", isRecording = true)
                            SamsungVendorTags.applyRecordingFps(b, fps)
                            SamsungVendorTags.applyHdr(b, _hdrEnabled.value)
                            try {
                                session.setRepeatingRequest(b.build(), null, cameraHandler)
                                rec.start()
                                _isRecording.value = true
                            } catch (e: Exception) {
                                Log.e(TAG, "Repeating record fail", e)
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Record session config FAILED")
                        }
                    },
                    cameraHandler
                )
            } catch (e: Exception) {
                Log.e(TAG, "startVideoRecording fallo", e)
            }
        }
    }

    fun stopVideoRecording(context: Context, storage: MediaStorageManager) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                safeStopMediaRecorder()
                _isRecording.value = false
                videoUri?.let { storage.finalizeVideo(context, it) }
                videoPfd?.close(); videoPfd = null
                // Reabrir preview-only
                createPreviewSession()
            } catch (e: Exception) {
                Log.e(TAG, "stopVideoRecording fallo", e)
            }
        }
    }

    private fun safeStopMediaRecorder() {
        try {
            mediaRecorder?.let {
                try { it.stop() } catch (_: Throwable) {}
                it.reset()
                it.release()
            }
        } catch (_: Throwable) {}
        mediaRecorder = null
        recordSurface?.release()
        recordSurface = null
    }

    // ============================================================
    //   Helpers internos
    // ============================================================

    private fun ensureThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("CameraBg").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
    }

    private fun ensureImageReader() {
        if (imageReader != null) return
        val ch = currentCharacteristics ?: return
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: return
        val largest = sizes.maxByOrNull { it.width.toLong() * it.height } ?: return
        imageReader = ImageReader.newInstance(
            largest.width, largest.height, ImageFormat.JPEG, 2
        )
    }

    private fun createPreviewSession() {
        val device = cameraDevice ?: return
        val s = previewSurface ?: return
        try {
            ensureImageReader()
            val outputs = listOfNotNull(s, imageReader?.surface)
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                outputs,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        applyRepeatingPreview()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Preview session config FAILED")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "createPreviewSession fallo", e)
        }
    }

    private fun applyRepeatingPreview() {
        val device  = cameraDevice  ?: return
        val session = captureSession ?: return
        val s = previewSurface ?: return
        try {
            val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(s)
            applyCommon(b)
            CameraTuning.applyImageQuality(b, _cameraMode.value)
            SamsungVendorTags.applyBase(b, _cameraMode.value, _isRecording.value)
            SamsungVendorTags.applyHdr(b, _hdrEnabled.value)
            if (_focusLocked.value) {
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
            session.setRepeatingRequest(b.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.w(TAG, "applyRepeatingPreview error", e)
        }
    }

    private fun applyCommon(b: CaptureRequest.Builder) {
        // AE / AF / AWB modes
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE,
            if (_cameraMode.value == "VIDEO")
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            else
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        b.set(CaptureRequest.CONTROL_AE_MODE,
            if (_flashEnabled.value)
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
            else
                CaptureRequest.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

        // Exposición
        if (_exposureLevel.value != 0) {
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, _exposureLevel.value)
        }

        // Zoom: preferimos vendor tag Samsung; si no, crop region
        val zoom = _zoomLevel.value
        if (abs(zoom - 1f) > 0.001f) {
            val applied = SamsungVendorTags.applyZoomRatio(b, zoom)
            if (!applied) {
                sensorArea?.let { area ->
                    val newW = (area.width()  / zoom).toInt().coerceAtLeast(1)
                    val newH = (area.height() / zoom).toInt().coerceAtLeast(1)
                    val left = (area.width()  - newW) / 2 + area.left
                    val top  = (area.height() - newH) / 2 + area.top
                    b.set(CaptureRequest.SCALER_CROP_REGION,
                        Rect(left, top, left + newW, top + newH))
                }
            }
        }

        // FPS range coherente con video
        if (_cameraMode.value == "VIDEO") {
            val fps = _videoFps.value.value
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        }
    }

    /**
     * Selecciona la cámara física más adecuada para el lens solicitado.
     * Estrategia:
     *  - Front cámara → primer ID con LENS_FACING_FRONT
     *  - 0.5x  → cámara con focal mínima (ultra-wide)
     *  - 3x    → cámara con focal máxima (tele)
     *  - 1x/2x → cámara lógica principal (BACKWARD_COMPATIBLE primaria)
     */
    private fun pickCameraId(mgr: CameraManager, front: Boolean, lens: String): String {
        val ids = mgr.cameraIdList
        if (front) {
            for (id in ids) {
                val ch = mgr.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    return id
                }
            }
        }

        val backIds = ids.filter {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        if (backIds.isEmpty()) return ids.firstOrNull() ?: "0"

        // En caso de multi-cámara lógica, devolvemos la principal y aplicamos zoom ratio
        val logical = backIds.firstOrNull {
            val ch = mgr.getCameraCharacteristics(it)
            CameraTuning.isLogicalMultiCamera(ch)
        }
        if (logical != null) {
            _zoomLevel.value = when (lens) {
                "0.5x" -> 0.5f
                "2x"   -> 2f
                "3x"   -> 3f
                else   -> 1f
            }
            return logical
        }

        // Sin multi-cámara lógica, picking heurístico por focal
        val sorted = backIds.sortedBy { id ->
            val ch = mgr.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        }
        return when (lens) {
            "0.5x" -> sorted.firstOrNull() ?: backIds.first()
            "3x"   -> sorted.lastOrNull()  ?: backIds.first()
            else   -> backIds.first()
        }
    }
}
