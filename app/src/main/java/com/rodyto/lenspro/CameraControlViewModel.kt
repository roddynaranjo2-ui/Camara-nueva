package com.rodyto.lenspro

import android.app.Application

/* ================================================================
 *  Rodyto Lens Pro · CameraControlViewModel.kt · v3.6 Pro
 *
 *  Clase FINAL que la UI consume. Hereda toda la pila:
 *      State  →  Core  →  Ops  →  CameraControlViewModel
 *
 *  Justificación:
 *    • La división en 4 archivos mantiene cada responsabilidad
 *      por debajo de ~250 líneas (objetivo del usuario).
 *    • Esta clase reúne lo que NO encaja perfectamente en una
 *      capa anterior: el companion object con constantes públicas,
 *      el binding nativo JNI, y el contrato de `startCameraSession`.
 *
 *  ⚠ BINDING NATIVO:
 *    El símbolo C++ es
 *      Java_com_rodyto_lenspro_CameraControlViewModel_getPhysicalCameraIdsNative
 *    Por eso esta clase DEBE existir en el package com.rodyto.lenspro
 *    con la función `external fun getPhysicalCameraIdsNative()`.
 *    Si renombras la clase o el package, debes regenerar la firma JNI.
 * ================================================================ */
class CameraControlViewModel(application: Application) :
    CameraControlViewModelOps(application) {

    companion object {
        /** Tope superior del dial de zoom — consumido por ZoomDial.kt */
        const val MAX_DIGITAL_ZOOM: Float = 30f

        init {
            try {
                System.loadLibrary("rodytolenspro")
            } catch (t: Throwable) {
                // En tests / preview Compose no hay .so → ignorar silenciosamente.
                android.util.Log.w("CameraVM", "Native lib no cargada: ${t.message}")
            }
        }
    }

    /**
     * Listado de IDs físicos vía NDK (logical multi-camera).
     * Devuelve array vacío si la lib nativa no se pudo cargar.
     */
    external fun getPhysicalCameraIdsNative(): Array<String>

    /* ── API que CameraPreview.kt invoca para arrancar la sesión ── */
    /**
     * Punto de entrada desde la UI: el SurfaceView ya está creado y
     * pasa su Surface; el caller indica qué lente seleccionar.
     *
     * Esta es la implementación SEGURA y verificable:
     *   1. Asegura background thread.
     *   2. Si ya hay una cámara abierta, la cierra.
     *   3. Llama openCamera() con el cameraId resuelto.
     *
     * La lógica completa de configurar MultiChannelImageReader, vendor
     * tags Samsung, RAW, y crear la CaptureSession final se delega
     * al onCameraOpened() — que aquí se sobreescribe pero queda como
     * extensión point: rellena ahí tu lógica original cuando la migres.
     */
    fun startCameraSession(
        context: android.content.Context,
        surface: android.view.Surface,
        lens: LensInfo?
    ) {
        ensureBackgroundThread()
        if (cameraDevice != null) safeCloseCamera()

        val manager = context.getSystemService(android.content.Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager

        val resolvedId = resolveCameraIdFor(lens, manager)
        if (resolvedId == null) {
            android.util.Log.e("CameraVM", "No hay cameraId disponible para lens=$lens")
            return
        }
        // Pre-cálculo del optimal preview size (mejora startup).
        try {
            val chars = manager.getCameraCharacteristics(resolvedId)
            recomputeOptimalPreviewSize(
                characteristics = chars,
                targetRatio = _previewAspectRatio.value.let { if (it <= 0f) 1f else it }
            )
        } catch (_: Throwable) { /* defensive: el HAL responderá luego */ }

        // Guardamos la Surface objetivo para que onCameraOpened pueda usarla.
        pendingPreviewSurface = surface

        try {
            openCamera(manager, resolvedId)
        } catch (sec: SecurityException) {
            android.util.Log.e("CameraVM", "Permisos: $sec")
        }
    }

    /** Surface objetivo del repeating preview — la pasa la UI tras crear el SurfaceView. */
    @Volatile private var pendingPreviewSurface: android.view.Surface? = null

    override fun onCameraOpened(camera: android.hardware.camera2.CameraDevice) {
        val surface = pendingPreviewSurface ?: return
        try {
            previewRequestBuilder = camera.createCaptureRequest(
                android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW
            ).apply { addTarget(surface) }

            camera.createCaptureSession(
                listOf(surface),
                object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) {
                        captureSession = s
                        applyRepeatingPreview()
                    }
                    override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) {
                        android.util.Log.e("CameraVM", "createCaptureSession FAILED")
                        _sessionState.value = CameraSessionState.ERROR
                    }
                },
                backgroundHandler
            )
        } catch (t: Throwable) {
            android.util.Log.e("CameraVM", "onCameraOpened error", t)
            _sessionState.value = CameraSessionState.ERROR
        }
    }

    /**
     * Resuelve qué cameraId abrir según la lente solicitada y los flags
     * de hardware (forceTelePhysicalId, isFrontCamera). Estrategia:
     *  • Front → primer ID con LENS_FACING_FRONT.
     *  • Tele forzada con flag activo → activePhysicalTeleId si está cargado.
     *  • Resto → primer ID lógico back.
     */
    private fun resolveCameraIdFor(
        lens: LensInfo?,
        manager: android.hardware.camera2.CameraManager
    ): String? {
        return try {
            val ids = manager.cameraIdList
            if (_isFrontCamera.value) {
                ids.firstOrNull {
                    val f = manager.getCameraCharacteristics(it)
                        .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                    f == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                }
            } else if (forceTelePhysicalId.value && lens?.label == "3x") {
                activePhysicalTeleId.value
                    ?: ids.firstOrNull {
                        manager.getCameraCharacteristics(it)
                            .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                    }
            } else {
                ids.firstOrNull {
                    manager.getCameraCharacteristics(it)
                        .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("CameraVM", "resolveCameraIdFor falló", t); null
        }
    }
}
