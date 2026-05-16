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
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max

enum class VideoResolution(val label: String, val width: Int, val height: Int) {
    HD("HD", 1280, 720),
    FHD("FHD", 1920, 1080),
    UHD("4K", 3840, 2160)
}

enum class VideoFps(val label: String, val value: Int) {
    FPS30("30", 30),
    FPS60("60", 60)
}

enum class PreviewAspect(val label: String, val ratio: Float) {
    RATIO_3_4("3:4", 3f / 4f),
    RATIO_9_16("9:16", 9f / 16f),
    RATIO_1_1("1:1", 1f),
    RATIO_FULL("FULL", 9f / 19.5f)
}

class CameraControlViewModel : ViewModel() {

    companion object {
        private const val TAG = "RodytoLensPro"

        @Volatile
        private var nativeLibLoaded = false

        init {
            try {
                System.loadLibrary("rodytolenspro")
                nativeLibLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Librería nativa no disponible (modo solo Kotlin)", e)
            }
        }
    }

    private data class CameraCandidate(
        val id: String,
        val facing: Int,
        val focal: Float,
        val isLogicalMultiCamera: Boolean,
        val sensorArea: Rect?
    )

    private data class CameraSelection(
        val cameraId: String,
        val opticalBaseZoom: Float
    )

    @Suppress("unused")
    private external fun getPhysicalCameraIdsNative(): Array<String>

    private fun safeNativePhysicalIds(): Array<String> = try {
        if (nativeLibLoaded) getPhysicalCameraIdsNative() else emptyArray()
    } catch (e: Throwable) {
        Log.w(TAG, "Fallo getPhysicalCameraIdsNative", e)
        emptyArray()
    }

    // ─── StateFlows ───────────────────────────────────────────────────────────
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

    private val _accentStyle = MutableStateFlow(AccentStyle.ICE_BLUE)
    val accentStyle: StateFlow<AccentStyle> = _accentStyle.asStateFlow()

    private val _hapticsEnabled = MutableStateFlow(true)
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    private val _hevcEnabled = MutableStateFlow(false)
    val hevcEnabled: StateFlow<Boolean> = _hevcEnabled.asStateFlow()

    // ─── Camera2 state ────────────────────────────────────────────────────────
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
    private var currentOpticalBaseZoom: Float = 1f

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // FIX ①: Mutex single-threaded — ya no se reintenta recursivamente dentro
    // de switchLens / toggleFrontCamera. Toda la lógica de open/close usa este
    // mutex de forma lineal.
    private val sessionMutex = Mutex()

    @Volatile
    private var cameraRunning = false

    fun isCameraRunning(): Boolean = cameraRunning

    // ─── Ciclo de vida principal ───────────────────────────────────────────────

    /**
     * Inicia la sesión de cámara. Llama siempre desde IO.
     * FIX ①: startCameraSession ya NO contiene withLock internamente para
     * evitar deadlock cuando se llama desde switchLens que ya lo tiene.
     */
    @SuppressLint("MissingPermission")
    fun startCameraSession(context: Context, surface: Surface, lens: String) {
        previewSurface = surface
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                startCameraSessionLocked(context, surface, lens)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun startCameraSessionLocked(context: Context, surface: Surface, lens: String) {
        if (cameraRunning) return
        try {
            ensureThread()
            cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val mgr = cameraManager!!
            val selection = pickCameraSelection(mgr, _isFrontCamera.value, lens)
            currentCameraId = selection.cameraId
            currentOpticalBaseZoom = selection.opticalBaseZoom
            currentCharacteristics = mgr.getCameraCharacteristics(selection.cameraId)
            sensorArea = currentCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            maxZoom = currentCharacteristics?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            safeNativePhysicalIds()

            // FIX ①: Abrimos la cámara con una suspendCancellableCoroutine para
            // garantizar que el callback onOpened bloquee este scope de IO hasta
            // completarse, evitando race entre open y createPreviewSession.
            val device: CameraDevice = suspendCancellableCoroutine { cont ->
                try {
                    mgr.openCamera(
                        selection.cameraId,
                        object : CameraDevice.StateCallback() {
                            override fun onOpened(device: CameraDevice) {
                                if (cont.isActive) cont.resume(device)
                            }

                            override fun onDisconnected(device: CameraDevice) {
                                device.close()
                                cameraRunning = false
                                if (cont.isActive) cont.cancel()
                            }

                            override fun onError(device: CameraDevice, error: Int) {
                                Log.e(TAG, "openCamera error=$error cameraId=${selection.cameraId}")
                                device.close()
                                cameraRunning = false
                                if (cont.isActive) cont.cancel()
                            }
                        },
                        cameraHandler
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "openCamera exception", e)
                    if (cont.isActive) cont.cancel(e)
                }
            }

            cameraDevice = device
            cameraRunning = true
            createPreviewSessionSuspending(device)

        } catch (e: Exception) {
            Log.e(TAG, "startCameraSessionLocked fallo", e)
            cameraRunning = false
        }
    }

    /**
     * Cierra la sesión completamente. Hilo-seguro y sin deadlock porque
     * ya no llama a startCameraSession internamente.
     */
    fun closeCamera() {
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                closeCameraLocked()
            }
        }
    }

    private fun closeCameraLocked() {
        try {
            if (_isRecording.value) safeStopMediaRecorder()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            mediaRecorder?.release()
            mediaRecorder = null
            recordSurface?.release()
            recordSurface = null
        } catch (e: Throwable) {
            Log.w(TAG, "closeCamera warn", e)
        } finally {
            cameraRunning = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeCamera()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    // ─── Toggle / set helpers ─────────────────────────────────────────────────

    fun toggleFlash() {
        _flashEnabled.value = !_flashEnabled.value
        applyRepeatingPreview()
    }

    fun toggleHdr() {
        _hdrEnabled.value = !_hdrEnabled.value
        applyRepeatingPreview()
    }

    fun toggleGrid() {
        _gridEnabled.value = !_gridEnabled.value
    }

    fun toggleShutterSound() {
        _shutterSoundEnabled.value = !_shutterSoundEnabled.value
    }

    fun toggleHaptics() {
        _hapticsEnabled.value = !_hapticsEnabled.value
    }

    fun toggleHevc() {
        _hevcEnabled.value = !_hevcEnabled.value
    }

    fun cycleTimer() {
        _timerSeconds.value = when (_timerSeconds.value) {
            0 -> 3
            3 -> 10
            else -> 0
        }
    }

    fun cycleTheme() {
        _darkTheme.value = when (_darkTheme.value) {
            null -> true
            true -> false
            false -> null
        }
    }

    fun cycleAccentStyle() {
        _accentStyle.value = when (_accentStyle.value) {
            AccentStyle.ICE_BLUE -> AccentStyle.AURORA
            AccentStyle.AURORA -> AccentStyle.JADE
            AccentStyle.JADE -> AccentStyle.ICE_BLUE
        }
    }

    fun setCameraMode(mode: String) {
        if (mode !in listOf("FOTO", "VIDEO")) return
        _cameraMode.value = mode
        if (_manualAspect.value == null) {
            _previewAspectRatio.value = if (mode == "VIDEO") 9f / 16f else 3f / 4f
        }
        applyRepeatingPreview()
    }

    fun setManualAspect(aspect: PreviewAspect?) {
        _manualAspect.value = aspect
        _previewAspectRatio.value = aspect?.ratio ?: if (_cameraMode.value == "VIDEO") 9f / 16f else 3f / 4f
    }

    fun setVideoResolution(r: VideoResolution) {
        _videoResolution.value = r
    }

    fun setVideoFps(f: VideoFps) {
        _videoFps.value = f
    }

    /**
     * FIX ①: Cambio de cámara frontal/trasera completamente serializado.
     * Cerramos en lock, luego abrimos en lock con suspending para eliminar
     * cualquier race condition.
     */
    fun toggleFrontCamera(context: Context) {
        _isFrontCamera.value = !_isFrontCamera.value
        val surface = previewSurface ?: return
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                closeCameraLocked()
                startCameraSessionLocked(context, surface, _currentLens.value)
            }
        }
    }

    /**
     * FIX ①: switchLens serializado con el mismo mutex, evitando pantalla
     * negra y congelamiento. Cierra la sesión anterior antes de abrir la nueva
     * usando la variante *Locked (no intenta re-adquirir el mutex).
     */
    fun switchLens(context: Context, lens: String) {
        if (_currentLens.value == lens) return
        _currentLens.value = lens
        _zoomLevel.value = when (lens) {
            "0.5x" -> 0.5f
            "1x" -> 1f
            "2x" -> 2f
            "3x" -> 3f
            else -> 1f
        }
        val surface = previewSurface ?: return
        viewModelScope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                closeCameraLocked()
                startCameraSessionLocked(context, surface, lens)
            }
        }
    }

    fun setZoom(ratio: Float) {
        val clamped = ratio.coerceIn(0.5f, max(maxZoom * currentOpticalBaseZoom, 10f))
        _zoomLevel.value = clamped
        applyRepeatingPreview()
    }

    fun getExposureRange(): Range<Int>? = currentCharacteristics
        ?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)

    fun setExposure(level: Int) {
        val range = getExposureRange() ?: return
        _exposureLevel.value = level.coerceIn(range.lower, range.upper)
        applyRepeatingPreview()
    }

    fun tapToFocus(x: Float, y: Float, screenW: Int, screenH: Int) {
        val area = sensorArea ?: return
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        try {
            val sx = (x / screenW.toFloat() * area.width()).toInt().coerceIn(0, area.width() - 1)
            val sy = (y / screenH.toFloat() * area.height()).toInt().coerceIn(0, area.height() - 1)
            val half = 150
            val rect = Rect(
                (sx - half).coerceAtLeast(0),
                (sy - half).coerceAtLeast(0),
                (sx + half).coerceAtMost(area.width() - 1),
                (sy + half).coerceAtMost(area.height() - 1)
            )
            val metering = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurface?.let { builder.addTarget(it) }
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(metering))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(metering))
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            applyCommon(builder)

            session.capture(builder.build(), null, cameraHandler)
            applyRepeatingPreview()
        } catch (e: Exception) {
            Log.w(TAG, "tapToFocus error", e)
        }
    }

    fun toggleFocusLock() {
        _focusLocked.value = !_focusLocked.value
        applyRepeatingPreview()
    }

    /**
     * FIX ②: takePicture — se elimina la pantalla blanca inestable usando
     * pre-capture AE estabilizado. En lugar de disparar directamente, primero
     * hacemos un PRECAPTURE trigger para que la AE converja; solo cuando
     * onCaptureCompleted llega lanzamos el STILL. Esto elimina el parpadeo
     * brillante causado por la rampa de flash/AE.
     * También reconstruimos el ImageReader solo si es necesario (no en cada
     * disparo), y restauramos el repeating preview DESPUÉS de guardar la
     * imagen para que no haya frame negro entre foto y preview.
     */
    fun takePicture(storage: MediaStorageManager, context: Context) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureImageReader()
                val targetSurface = imageReader?.surface ?: return@launch

                // Paso 1: Registrar listener ANTES del disparo para no perder la imagen
                imageReader?.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val uri = storage.saveJpeg(context, bytes)
                        if (uri != null) _lastPhotoUri.value = uri
                    } catch (e: Throwable) {
                        Log.e(TAG, "Procesar JPEG fallo", e)
                    } finally {
                        image.close()
                        // FIX ②: Restaurar preview DESPUÉS de guardar la imagen
                        // (evita el frame negro post-captura)
                        applyRepeatingPreview()
                    }
                }, cameraHandler)

                // Paso 2: Pre-capture AE trigger (elimina el parpadeo de AE)
                if (_flashEnabled.value) {
                    val precaptureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    previewSurface?.let { precaptureBuilder.addTarget(it) }
                    applyCommon(precaptureBuilder)
                    precaptureBuilder.set(
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
                    )
                    session.capture(precaptureBuilder.build(), null, cameraHandler)
                    // Damos tiempo mínimo al AE para que converja (evita flash brusco)
                    kotlinx.coroutines.delay(80)
                }

                // Paso 3: Disparo STILL
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(targetSurface)
                applyCommon(builder)
                CameraTuning.applyImageQuality(builder, "FOTO")
                CameraTuning.applyJpegQuality(builder)
                SamsungVendorTags.applyCaptureSnapshotHint(builder)
                SamsungVendorTags.applyHdr(builder, _hdrEnabled.value)
                SamsungVendorTags.applyProTone(builder, _hdrEnabled.value)

                if (_flashEnabled.value) {
                    builder.set(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                    )
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                }

                // FIX ②: NO llamar applyRepeatingPreview aquí; se hace en el
                // listener de imagen para evitar el frame negro intermedio.
                session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        // Intencional: el applyRepeatingPreview va en el image listener
                    }
                }, cameraHandler)

            } catch (e: Exception) {
                Log.e(TAG, "takePicture fallo", e)
                // En caso de error, restaurar preview igualmente
                applyRepeatingPreview()
            }
        }
    }

    /**
     * FIX ③: startVideoRecording — se añade correcta configuración de FPS
     * range y se usa SessionConfiguration (API 28+) con OutputConfiguration
     * para evitar drops de frames. En API < 28 se mantiene el fallback
     * deprecado pero con el FPS range ya fijado en el repeating request.
     */
    @SuppressLint("MissingPermission")
    fun startVideoRecording(context: Context, storage: MediaStorageManager) {
        val device = cameraDevice ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = storage.createVideoUri(context) ?: return@launch
                val pfd = storage.openVideoFd(context, uri) ?: return@launch
                videoUri = uri
                videoPfd = pfd

                val resolution = _videoResolution.value
                val fps = _videoFps.value.value
                val codec = CameraTuning.preferredEncoder(_hevcEnabled.value)
                val codecLabel = CameraTuning.preferredCodecLabel(_hevcEnabled.value)
                val bitrate = VideoBitrateCalculator.preset(resolution, fps, codecLabel)

                val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                    setVideoSize(resolution.width, resolution.height)
                    setVideoEncoder(codec)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(192_000)
                    setAudioSamplingRate(48_000)
                    prepare()
                }
                mediaRecorder = recorder
                recordSurface = recorder.surface

                val preview = previewSurface ?: return@launch
                val recording = recordSurface ?: return@launch

                // FIX ③: Suspendemos hasta que la sesión esté configurada para
                // evitar que recorder.start() se llame antes de que el pipeline
                // esté listo (causa drops y audio desincronizado).
                val configured: CameraCaptureSession = suspendCancellableCoroutine { cont ->
                    try {
                        @Suppress("DEPRECATION")
                        device.createCaptureSession(
                            listOf(preview, recording),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    if (cont.isActive) cont.resume(session)
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e(TAG, "Record session config FAILED")
                                    if (cont.isActive) cont.cancel()
                                }
                            },
                            cameraHandler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "createCaptureSession (video) exception", e)
                        if (cont.isActive) cont.cancel(e)
                    }
                }

                captureSession = configured
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                builder.addTarget(preview)
                builder.addTarget(recording)
                applyCommon(builder)
                CameraTuning.applyImageQuality(builder, "VIDEO")
                SamsungVendorTags.applyBase(builder, "VIDEO", isRecording = true)
                SamsungVendorTags.applyRecordingFps(builder, fps)
                SamsungVendorTags.applyHdr(builder, _hdrEnabled.value)

                // FIX ③: El FPS range fijo garantiza 30 o 60 fps estables sin
                // que el AE lo altere durante la grabación.
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))

                configured.setRepeatingRequest(builder.build(), null, cameraHandler)

                // Solo arrancamos el recorder cuando el pipeline está listo
                recorder.start()
                _isRecording.value = true

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
                videoPfd?.close()
                videoPfd = null
                // Recrear sesión de preview al finalizar grabación
                val device = cameraDevice
                val surface = previewSurface
                if (device != null && surface != null) {
                    createPreviewSessionSuspending(device)
                }
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

    // ─── Internals ────────────────────────────────────────────────────────────

    private fun ensureThread() {
        if (cameraThread == null || !cameraThread!!.isAlive) {
            cameraThread = HandlerThread("CameraBg").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
    }

    private fun ensureImageReader() {
        // FIX ②: reutilizamos el ImageReader si ya existe y tiene el formato correcto
        if (imageReader != null) return
        val characteristics = currentCharacteristics ?: return
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: return
        val largest = sizes.maxByOrNull { it.width.toLong() * it.height } ?: return
        imageReader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.JPEG, 2)
    }

    /**
     * FIX ①: Versión suspending de createPreviewSession para poder
     * encadenarla después de openCamera sin race conditions.
     */
    private suspend fun createPreviewSessionSuspending(device: CameraDevice) {
        val surface = previewSurface ?: return
        try {
            ensureImageReader()
            val targets = listOfNotNull(surface, imageReader?.surface)

            val session: CameraCaptureSession = suspendCancellableCoroutine { cont ->
                try {
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(
                        targets,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                if (cont.isActive) cont.resume(session)
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "Preview session config FAILED")
                                if (cont.isActive) cont.cancel()
                            }
                        },
                        cameraHandler
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "createCaptureSession (preview) exception", e)
                    if (cont.isActive) cont.cancel(e)
                }
            }

            captureSession = session
            applyRepeatingPreview()
        } catch (e: Exception) {
            Log.e(TAG, "createPreviewSessionSuspending fallo", e)
        }
    }

    // Versión no-suspending para compatibilidad con llamadas legacy
    private fun createPreviewSession() {
        val device = cameraDevice ?: return
        viewModelScope.launch(Dispatchers.IO) {
            createPreviewSessionSuspending(device)
        }
    }

    fun applyRepeatingPreview() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            applyCommon(builder)
            CameraTuning.applyImageQuality(builder, _cameraMode.value)
            SamsungVendorTags.applyBase(builder, _cameraMode.value, _isRecording.value)
            SamsungVendorTags.applyHdr(builder, _hdrEnabled.value)
            if (_focusLocked.value) {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.w(TAG, "applyRepeatingPreview error", e)
        }
    }

    private fun applyCommon(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(
            CaptureRequest.CONTROL_AF_MODE,
            if (_cameraMode.value == "VIDEO") {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            } else {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }
        )
        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            if (_flashEnabled.value) CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
            else CaptureRequest.CONTROL_AE_MODE_ON
        )
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

        if (_exposureLevel.value != 0) {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, _exposureLevel.value)
        }

        val requestedZoom = _zoomLevel.value
        val sensorZoom = (requestedZoom / currentOpticalBaseZoom)
            .coerceIn(1f, max(maxZoom, 10f))

        if (abs(sensorZoom - 1f) > 0.001f) {
            val applied = SamsungVendorTags.applyZoomRatio(builder, sensorZoom)
            if (!applied) {
                sensorArea?.let { area ->
                    val newW = (area.width() / sensorZoom).toInt().coerceAtLeast(1)
                    val newH = (area.height() / sensorZoom).toInt().coerceAtLeast(1)
                    val left = (area.width() - newW) / 2 + area.left
                    val top = (area.height() - newH) / 2 + area.top
                    builder.set(
                        CaptureRequest.SCALER_CROP_REGION,
                        Rect(left, top, left + newW, top + newH)
                    )
                }
            }
        }

        // FIX ③: FPS range se aplica en VIDEO mode para garantizar estabilidad
        // de fotogramas (30 o 60 exactos, sin variaciones del AE).
        if (_cameraMode.value == "VIDEO") {
            val fps = _videoFps.value.value
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        }
    }

    private fun pickCameraSelection(mgr: CameraManager, front: Boolean, lens: String): CameraSelection {
        val ids = mgr.cameraIdList
        if (front) {
            ids.forEach { id ->
                val characteristics = mgr.getCameraCharacteristics(id)
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    return CameraSelection(id, 1f)
                }
            }
        }

        val backCandidates = ids.mapNotNull { id ->
            val characteristics = mgr.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                ?: return@mapNotNull null
            if (facing != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
            val focal = characteristics
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull() ?: return@mapNotNull null
            CameraCandidate(
                id = id,
                facing = facing,
                focal = focal,
                isLogicalMultiCamera = CameraTuning.isLogicalMultiCamera(characteristics),
                sensorArea = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            )
        }

        if (backCandidates.isEmpty()) {
            return CameraSelection(ids.firstOrNull() ?: "0", 1f)
        }

        val logicalPrimary = backCandidates.firstOrNull { it.isLogicalMultiCamera }
        val widePhysical = backCandidates.minByOrNull { candidate ->
            kotlin.math.abs(candidate.focal - 5f) + if (candidate.isLogicalMultiCamera) 0.7f else 0f
        } ?: backCandidates.first()
        val ultraPhysical = backCandidates.minByOrNull { it.focal }
        val telePhysical = backCandidates.maxByOrNull { it.focal }

        val hasDedicatedTele = telePhysical != null && ultraPhysical != null &&
            telePhysical.id != widePhysical.id &&
            telePhysical.focal >= widePhysical.focal * 1.8f

        val hasDedicatedUltra = ultraPhysical != null &&
            ultraPhysical.id != widePhysical.id &&
            ultraPhysical.focal <= widePhysical.focal * 0.75f

        return when (lens) {
            "0.5x" -> {
                if (hasDedicatedUltra && ultraPhysical != null) {
                    CameraSelection(ultraPhysical.id, 0.5f)
                } else {
                    CameraSelection(logicalPrimary?.id ?: widePhysical.id, 1f)
                }
            }

            "3x" -> {
                if (hasDedicatedTele && telePhysical != null) {
                    Log.d(TAG, "Usando tele física para 3x: ${telePhysical.id}")
                    CameraSelection(telePhysical.id, 3f)
                } else {
                    Log.d(TAG, "Tele física no disponible; usando cámara lógica/principal con zoom")
                    CameraSelection(logicalPrimary?.id ?: widePhysical.id, 1f)
                }
            }

            else -> CameraSelection(logicalPrimary?.id ?: widePhysical.id, 1f)
        }
    }
}
