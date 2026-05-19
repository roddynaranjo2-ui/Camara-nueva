package com.rodyto.lenspro

import android.app.Application

/* ================================================================
 *  Rodyto Lens Pro · CameraControlViewModel.kt · v3.8 Pro
 *
 *  NOVEDADES v3.8 (sobre v3.7):
 *   • override onLensSwitchRequested(lens) — implementa el cambio
 *     real de cámara: guarda la lente, cierra la sesión, y reabre
 *     con el nuevo cameraId usando la Surface actual.
 *     Esto cierra el bug B-03 (cambio de lente no surtía efecto).
 *
 *   • startCameraSession() ya no retiene `pendingPreviewSurface`
 *     indefinidamente; tras pasarla a onCameraOpened() se mantiene
 *     accesible (porque la sesión sigue vinculada a ella), pero un
 *     futuro startCameraSession() la sobreescribe sin fuga.
 *
 *   • lastPreviewContext guardado de forma debil — necesario para
 *     que setLens pueda reabrir la cámara sin que la UI tenga que
 *     llamar a startCameraSession explícitamente otra vez.
 *
 *  ⚠ BINDING NATIVO inalterado:
 *    Java_com_rodyto_lenspro_CameraControlViewModel_getPhysicalCameraIdsNative
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

    /** Surface objetivo del repeating preview — la pasa la UI tras crear el SurfaceView. */
    @Volatile private var pendingPreviewSurface: android.view.Surface? = null

    /**
     * v3.8: referencia débil al último Context usado, para que un
     * cambio de lente desde la UI (vía setLens → onLensSwitchRequested)
     * pueda reabrir la cámara sin requerir que la UI reenvíe el context.
     * Se limpia explícitamente en onCleared() para evitar leaks de Activity.
     */
    @Volatile private var lastPreviewContext: java.lang.ref.WeakReference<android.content.Context>? = null

    /**
     * Punto de entrada desde la UI: el SurfaceView ya está creado y
     * pasa su Surface; el caller indica qué lente seleccionar.
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

        // Guardamos la Surface objetivo + context (weakref) para futuros switches.
        pendingPreviewSurface = surface
        lastPreviewContext = java.lang.ref.WeakReference(context.applicationContext ?: context)

        // Reflejar la lente solicitada en el StateFlow (la UI lo necesita inmediatamente)
        if (lens != null) setCurrentLensInternal(lens)

        try {
            openCamera(manager, resolvedId)
        } catch (sec: SecurityException) {
            android.util.Log.e("CameraVM", "Permisos: $sec")
        }
    }

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

    /* ── v3.8: override del hook de cambio de lente (fix B-03) ──── */

    /**
     * Implementación REAL del cambio de lente:
     *  1. Recupera el último context usado para abrir la cámara.
     *  2. Recupera la Surface actual del preview.
     *  3. Cierra limpiamente la sesión existente.
     *  4. Reabre con el nuevo cameraId resuelto a partir de `lens`.
     *
     * Si falta cualquiera de las dependencias (context o surface),
     * la UI tendrá que disparar el reopen llamando a startCameraSession
     * explícitamente cuando el SurfaceView esté listo (esto sucede en
     * ON_RESUME / surfaceCreated por el Lifecycle observer ya existente).
     */
    override fun onLensSwitchRequested(lens: LensInfo) {
        val ctx = lastPreviewContext?.get()
        val surface = pendingPreviewSurface
        if (ctx == null || surface == null || !surface.isValid) {
            android.util.Log.w("CameraVM", "onLensSwitchRequested: sin context/surface vivos — diferido")
            // No bloqueamos: el StateFlow ya refleja la lente; cuando el
            // SurfaceView se recree, startCameraSession() leerá la lente
            // actual desde currentLens.value en la UI.
            return
        }
        // Reabrir con la nueva lente.
        startCameraSession(context = ctx, surface = surface, lens = lens)
    }

    /**
     * Resuelve qué cameraId abrir según la lente solicitada y los flags
     * de hardware (forceTelePhysicalId, isFrontCamera).
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

    /**
     * v3.8: limpieza explícita de la weak-ref al context, para que ningún
     * componente externo pueda mantener una referencia indirecta a la
     * Activity tras destrucción.
     */
    override fun onCleared() {
        lastPreviewContext = null
        pendingPreviewSurface = null
        super.onCleared()
    }
}
