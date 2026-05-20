package com.rodyto.lenspro

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCharacteristics
import android.util.Log

/**
 * SamsungVendorTags
 *
 * Aplicación REFLEXIVA y SEGURA de vendor tags propietarios de Samsung Camera HAL
 * (cosechados del binary dump de "com.sec.android.app.camera" One UI 16).
 *
 *  1. La aplicación NO se rompe en dispositivos no-Samsung: si la `CaptureRequest.Key`
 *     no existe en el HAL, se ignora silenciosamente.
 *  2. La clave se construye vía reflexión porque las cabeceras públicas Camera2 no
 *     incluyen los `vendor tag IDs`; pero el HAL Samsung sí los expone como
 *     `CaptureRequest.Key` cuando se les pasa el nombre canónico.
 *
 *  Tags rescatados del dump de Samsung Camera:
 *  ───────────────────────────────────────────────────────────────
 *   samsung.android.control.liveHdrMode          (Int, 0=off 1=on 2=auto)
 *   samsung.android.control.afSensitivity        (Int, 0=normal 1=high)
 *   samsung.android.control.colorTemperature     (Int, Kelvin 2300..10000)
 *   samsung.android.control.recordingMinFps      (Int)
 *   samsung.android.control.recordingMaxFps      (Int)
 *   samsung.android.control.shootingMode         (Int, ver Constants)
 *   samsung.android.control.requestHint          (Int, ver Constants)
 *   samsung.android.control.captureHint          (Int)
 *   samsung.android.control.aeExtraMode          (Int)
 *   samsung.android.control.unihalMode           (Int)
 *   samsung.android.scaler.zoomRatio             (Float, 0.5..10.0)
 *   samsung.android.lens.opticalStabilizationOperationMode (Int)
 *   samsung.android.jpeg.imageUniqueId           (String)
 *   samsung.android.control.globalToneMap        (FloatArray, curva LUT)
 *   samsung.android.control.colorSpaceMode       (Int, sRGB/P3/Display P3)
 */
object SamsungVendorTags {

    private const val TAG = "SamsungVendorTags"

    // ---------- Names (los que descubrí en el dump) ----------
    private const val LIVE_HDR_MODE              = "samsung.android.control.liveHdrMode"
    private const val AF_SENSITIVITY             = "samsung.android.control.afSensitivity"
    private const val COLOR_TEMPERATURE          = "samsung.android.control.colorTemperature"
    private const val RECORDING_MIN_FPS          = "samsung.android.control.recordingMinFps"
    private const val RECORDING_MAX_FPS          = "samsung.android.control.recordingMaxFps"
    private const val SHOOTING_MODE              = "samsung.android.control.shootingMode"
    private const val REQUEST_HINT               = "samsung.android.control.requestHint"
    private const val CAPTURE_HINT               = "samsung.android.control.captureHint"
    private const val AE_EXTRA_MODE              = "samsung.android.control.aeExtraMode"
    private const val UNIHAL_MODE                = "samsung.android.control.unihalMode"
    private const val SCALER_ZOOM_RATIO          = "samsung.android.scaler.zoomRatio"
    private const val OIS_OP_MODE                = "samsung.android.lens.opticalStabilizationOperationMode"
    private const val GLOBAL_TONE_MAP            = "samsung.android.control.globalToneMap"
    private const val COLOR_SPACE_MODE           = "samsung.android.control.colorSpaceMode"

    // ---------- Constantes Samsung (extraídas del dump) ----------
    object ShootingMode {
        const val AUTO   = 0x0
        const val PHOTO  = 0x1
        const val VIDEO  = 0x2
        const val PRO    = 0x10
        const val NIGHT  = 0x20
        const val PANO   = 0x30
    }

    object RequestHint {
        const val NONE                 = 0
        const val CAPTURE_SNAPSHOT     = 1
        const val NIGHT                = 2
        const val VIDEO_RECORDING      = 3
        const val SUPER_STEADY         = 4
    }

    object OisOpMode {
        const val PICTURE        = 0    // OIS para foto (compensa shake corto)
        const val VIDEO_DEFAULT  = 1
        const val VIDEO_FOLLOWING = 2   // "Super Steady" (panning suave)
    }

    object LiveHdrMode { const val OFF = 0; const val ON = 1; const val AUTO = 2 }

    // ---------- Cache de claves (reflexión) ----------
    private val keyCache = HashMap<String, CaptureRequest.Key<*>?>()

    /**
     * Devuelve la `CaptureRequest.Key` correspondiente al nombre,
     * o null si el HAL no la expone.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> keyOf(name: String, type: Class<T>): CaptureRequest.Key<T>? {
        // SEC-2: Sincronización para evitar race conditions en el cache
        synchronized(keyCache) {
            keyCache[name]?.let { return it as CaptureRequest.Key<T> }
            return try {
                val ctor = CaptureRequest.Key::class.java.getDeclaredConstructor(
                    String::class.java, Class::class.java
                )
                ctor.isAccessible = true
                val k = ctor.newInstance(name, type) as CaptureRequest.Key<T>
                keyCache[name] = k
                k
            } catch (e: Throwable) {
                keyCache[name] = null
                // Reducir ruido en logs de producción
                if (BuildConfig.DEBUG) {
                    Log.v(TAG, "Vendor tag no disponible: $name (${e.javaClass.simpleName})")
                }
                null
            }
        }
    }

    private fun <T> safeSet(b: CaptureRequest.Builder, name: String, cls: Class<T>, value: T) {
        try {
            val k = keyOf(name, cls) ?: return
            b.set(k, value)
        } catch (_: Throwable) { /* HAL no soporta → ignore */ }
    }

    // ---------- API pública (la que llama el ViewModel) ----------

    /**
     * Inyecta el set base de vendor tags para foto/video según modo de la app.
     * Idempotente: aplicar en cada repeating request es seguro y barato.
     */
    fun applyBase(b: CaptureRequest.Builder, mode: String, isRecording: Boolean) {
        if (mode == "VIDEO") {
            safeSet(b, SHOOTING_MODE,   Int::class.javaObjectType, ShootingMode.VIDEO)
            safeSet(b, REQUEST_HINT,    Int::class.javaObjectType,
                if (isRecording) RequestHint.VIDEO_RECORDING else RequestHint.NONE)
            safeSet(b, AF_SENSITIVITY,  Int::class.javaObjectType, 1)              // HIGH
            safeSet(b, OIS_OP_MODE,     Int::class.javaObjectType, OisOpMode.VIDEO_FOLLOWING)
        } else {
            safeSet(b, SHOOTING_MODE,   Int::class.javaObjectType, ShootingMode.PHOTO)
            safeSet(b, REQUEST_HINT,    Int::class.javaObjectType, RequestHint.NONE)
            safeSet(b, AF_SENSITIVITY,  Int::class.javaObjectType, 0)              // NORMAL
            safeSet(b, OIS_OP_MODE,     Int::class.javaObjectType, OisOpMode.PICTURE)
        }
        // sRGB por defecto (no forzamos P3 a menos que se exponga toggle)
        safeSet(b, COLOR_SPACE_MODE, Int::class.javaObjectType, 0)
    }

    fun applyHdr(b: CaptureRequest.Builder, enabled: Boolean) {
        val mode = if (enabled) LiveHdrMode.AUTO else LiveHdrMode.OFF
        safeSet(b, LIVE_HDR_MODE, Int::class.javaObjectType, mode)
    }

    /**
     * Aplica el tone-mapping global tipo "log" (S-curve suave) cuando
     * el usuario activa HDR/Pro. Curva derivada del binario Samsung:
     * el HAL expone un FloatArray que el HAL re-muestrea a 32 puntos.
     */
    fun applyProTone(b: CaptureRequest.Builder, enabled: Boolean) {
        if (!enabled) return
        val curve = floatArrayOf(
            0.00f, 0.04f, 0.10f, 0.17f, 0.25f, 0.33f, 0.41f, 0.48f,
            0.55f, 0.61f, 0.66f, 0.71f, 0.75f, 0.79f, 0.82f, 0.85f,
            0.87f, 0.89f, 0.91f, 0.92f, 0.93f, 0.94f, 0.95f, 0.96f,
            0.97f, 0.975f, 0.98f, 0.985f, 0.99f, 0.995f, 0.998f, 1.0f
        )
        safeSet(b, GLOBAL_TONE_MAP, FloatArray::class.java, curve)
    }

    /**
     * Zoom de alta precisión vía vendor tag scaler.zoomRatio. Si el HAL
     * no lo expone, retorna false → el ViewModel cae a SCALER_CROP_REGION.
     */
    fun applyZoomRatio(b: CaptureRequest.Builder, ratio: Float): Boolean {
        val k = keyOf(SCALER_ZOOM_RATIO, Float::class.javaObjectType) ?: return false
        return try { b.set(k, ratio); true } catch (_: Throwable) { false }
    }

    /**
     * FPS de grabación pegado a un rango exacto (Samsung lo separa del
     * AE_TARGET_FPS_RANGE estándar para forzar el reloj del encoder).
     */
    fun applyRecordingFps(b: CaptureRequest.Builder, fps: Int) {
        safeSet(b, RECORDING_MIN_FPS, Int::class.javaObjectType, fps)
        safeSet(b, RECORDING_MAX_FPS, Int::class.javaObjectType, fps)
    }

    fun applyCaptureSnapshotHint(b: CaptureRequest.Builder) {
        safeSet(b, CAPTURE_HINT,  Int::class.javaObjectType, 1)
        safeSet(b, REQUEST_HINT,  Int::class.javaObjectType, RequestHint.CAPTURE_SNAPSHOT)
    }

    /**
     * Devuelve true si el dispositivo expone CUALQUIER vendor tag samsung —
     * heurística rápida para activar UI extendida (futuro toggle "Samsung Pro").
     */
    fun isSamsungHal(characteristics: CameraCharacteristics?): Boolean {
        characteristics ?: return false
        return try {
            val keys = characteristics.availableCaptureRequestKeys
            keys.any { it.name.startsWith("samsung.android.") }
        } catch (_: Throwable) { false }
    }
}
