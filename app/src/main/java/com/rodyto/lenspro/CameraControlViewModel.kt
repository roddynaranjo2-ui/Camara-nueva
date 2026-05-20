package com.rodyto.lenspro

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import android.view.Surface
import java.lang.ref.WeakReference

/* ================================================================
 *  Rodyto Lens Pro · CameraControlViewModel.kt · v4.0 Premium
 *
 *  v4.0 — Cambios sobre v3.8:
 *   • CameraCaptureEngine integrado — ImageReader JPEG real.
 *   • CaptureSession ahora incluye DOS targets: preview surface + reader surface.
 *   • onFrontBackSwitchRequested() override → reapertura inmediata sin
 *     esperar a que la Surface se recree (fix B1).
 *   • currentCharacteristics cacheadas para zoom + JPEG orientation.
 *   • applyJpegSize() detecta automáticamente el mayor JPEG soportado.
 * ================================================================ */
class CameraControlViewModel(application: Application) :
    CameraControlViewModelOps(application) {

    companion object {
        const val MAX_DIGITAL_ZOOM: Float = 30f

        init {
            try {
                System.loadLibrary("rodytolenspro")
            } catch (t: Throwable) {
                Log.w("CameraVM", "Native lib no cargada: ${t.message}")
            }
        }
    }

    external fun getPhysicalCameraIdsNative(): Array<String>

    @Volatile private var pendingPreviewSurface: Surface? = null
    @Volatile private var lastPreviewContext: WeakReference<Context>? = null

    fun startCameraSession(
        context: Context,
        surface: Surface,
        lens: LensInfo?
    ) {
        ensureBackgroundThread()
        if (cameraDevice != null) safeCloseCamera()

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val resolvedId = resolveCameraIdFor(lens, manager)
        if (resolvedId == null) {
            Log.e("CameraVM", "No hay cameraId disponible para lens=$lens"); return
        }
        try {
            val chars = manager.getCameraCharacteristics(resolvedId)
            currentCharacteristics = chars
            recomputeOptimalPreviewSize(
                characteristics = chars,
                targetRatio = _previewAspectRatio.value.let { if (it <= 0f) 1f else it }
            )
            // Detectar soporte de flash
            val flashAvail = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            isFlashSupported.value = flashAvail

            // Inicializar CaptureEngine con context + handler
            if (captureEngine == null) {
                captureEngine = CameraCaptureEngine(
                    context = context.applicationContext,
                    backgroundHandler = backgroundHandler
                )
            }
        } catch (_: Throwable) { }

        pendingPreviewSurface = surface
        lastPreviewContext = WeakReference(context.applicationContext ?: context)

        if (lens != null) setCurrentLensInternal(lens)

        try {
            openCamera(manager, resolvedId)
        } catch (sec: SecurityException) {
            Log.e("CameraVM", "Permisos: $sec")
        }
    }

    override fun onCameraOpened(camera: CameraDevice) {
        val surface = pendingPreviewSurface ?: return
        val chars = currentCharacteristics
        try {
            // ─── ImageReader JPEG
            val jpegSize: Size = chars
                ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                ?.maxByOrNull { it.width.toLong() * it.height }
                ?: Size(1920, 1080)

            val readerSurface = captureEngine?.prepareReader(jpegSize)

            previewRequestBuilder = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            ).apply { addTarget(surface) }

            val outputs = listOfNotNull(surface, readerSurface)

            camera.createCaptureSession(
                outputs,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        captureSession = s
                        applyRepeatingPreview()
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e("CameraVM", "createCaptureSession FAILED")
                        _sessionState.value = CameraSessionState.ERROR
                    }
                },
                backgroundHandler
            )
        } catch (t: Throwable) {
            Log.e("CameraVM", "onCameraOpened error", t)
            _sessionState.value = CameraSessionState.ERROR
        }
    }

    /* ─── Override: cambio de lente ─────────────────────────────── */
    override fun onLensSwitchRequested(lens: LensInfo) {
        reopenWithCurrentLens(lens)
    }

    /* ─── Override: switch FRONT/BACK (fix B1) ──────────────────── */
    override fun onFrontBackSwitchRequested() {
        reopenWithCurrentLens(_currentLens.value)
    }

    private fun reopenWithCurrentLens(lens: LensInfo?) {
        val ctx = lastPreviewContext?.get()
        val surface = pendingPreviewSurface
        if (ctx == null || surface == null || !surface.isValid) {
            Log.w("CameraVM", "reopenWithCurrentLens: surface/context inválido — esperando surface recreate")
            return
        }
        startCameraSession(ctx, surface, lens)
    }

    private fun resolveCameraIdFor(
        lens: LensInfo?,
        manager: CameraManager
    ): String? {
        return try {
            val ids = manager.cameraIdList
            if (_isFrontCamera.value) {
                ids.firstOrNull {
                    manager.getCameraCharacteristics(it)
                        .get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT
                }
            } else if (forceTelePhysicalId.value && lens?.label == "3x") {
                activePhysicalTeleId.value
                    ?: ids.firstOrNull {
                        manager.getCameraCharacteristics(it)
                            .get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_BACK
                    }
            } else {
                ids.firstOrNull {
                    manager.getCameraCharacteristics(it)
                        .get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
                }
            }
        } catch (t: Throwable) {
            Log.e("CameraVM", "resolveCameraIdFor falló", t); null
        }
    }

    override fun onCleared() {
        lastPreviewContext = null
        pendingPreviewSurface = null
        captureEngine?.release()
        captureEngine = null
        super.onCleared()
    }
}
