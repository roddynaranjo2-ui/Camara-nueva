package com.rodyto.lenspro

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.lensProDataStore by preferencesDataStore(name = "lenspro_settings")

/**
 * SettingsRepository v3.6 Pro — persistencia completa vía DataStore.
 *
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║  FIX v3.6 (críticos sobre v3.5 — corrige cámara negra + lag):        ║
 * ║                                                                      ║
 * ║   • useCameraXAnalysis  DEFAULT = false                              ║
 * ║     (antes true → ABRÍA la misma cámara que Camera2 en paralelo,     ║
 * ║      provocando ERROR_MAX_CAMERAS_IN_USE en la mayoría de HALs y     ║
 * ║      dejando el preview en NEGRO. Ahora opt-in desde Settings)       ║
 * ║                                                                      ║
 * ║   • forceTelePhysicalId DEFAULT = false                              ║
 * ║     (antes true → intentaba abrir un id físico "52" exclusivo de     ║
 * ║      Samsung S21 FE; en otros dispositivos fallaba y el switch de    ║
 * ║      lente "3x" quedaba lento o no abría la cámara. Ahora opt-in)    ║
 * ║                                                                      ║
 * ║   • Resto de claves intactas — persistencia bidireccional 100%.      ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 *
 * NOVEDADES heredadas v3.5:
 *  • KEY_USE_CAMERAX_ANALYSIS, KEY_FORCE_TELE_PHYSICAL_ID,
 *    KEY_TELE_PHYSICAL_ID, KEY_PRO_VENDOR_TAGS, ISO/WB/Shutter manuales.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        // ── Composición / overlays ────────────────────────────────────────
        val KEY_HISTOGRAM      = booleanPreferencesKey("histogram_enabled")
        val KEY_HORIZON        = booleanPreferencesKey("horizon_enabled")
        val KEY_FOCUS_PEAK     = booleanPreferencesKey("focus_peaking")
        val KEY_GRID           = booleanPreferencesKey("grid_enabled")

        // ── Captura ───────────────────────────────────────────────────────
        val KEY_RAW            = booleanPreferencesKey("raw_capture")
        val KEY_HDR            = booleanPreferencesKey("hdr_enabled")
        val KEY_FLASH_MODE     = stringPreferencesKey("flash_mode")   // "OFF"/"AUTO"/"ON"
        val KEY_TIMER          = intPreferencesKey("timer_seconds")    // 0/3/10
        val KEY_HEVC           = booleanPreferencesKey("hevc_enabled")

        // ── Sonido / vibración ────────────────────────────────────────────
        val KEY_SOUND          = booleanPreferencesKey("shutter_sound")
        val KEY_HAPTICS        = booleanPreferencesKey("haptics_enabled")

        // ── Vídeo ─────────────────────────────────────────────────────────
        val KEY_VIDEO_RES      = stringPreferencesKey("video_resolution") // HD/FHD/UHD
        val KEY_VIDEO_FPS      = intPreferencesKey("video_fps")           // 30/60
        val KEY_VIDEO_60FPS    = booleanPreferencesKey("video_60fps_default") // legacy

        // ── Zoom / lente ──────────────────────────────────────────────────
        val KEY_SMOOTH_ZOOM    = booleanPreferencesKey("smooth_zoom")
        val KEY_LAST_LENS      = stringPreferencesKey("last_lens")  // "0.5x"/"1x"/"3x"

        // ── Aspect ────────────────────────────────────────────────────────
        val KEY_MANUAL_ASPECT  = stringPreferencesKey("manual_aspect") // null/3:4/9:16/1:1/FULL

        // ── Archivos ──────────────────────────────────────────────────────
        val KEY_ORG_BY_DATE    = booleanPreferencesKey("organize_by_date")

        // ── Apariencia ────────────────────────────────────────────────────
        val KEY_ACCENT_INDEX   = intPreferencesKey("accent_index")
        val KEY_THEME_MODE     = stringPreferencesKey("theme_mode") // system/dark/light

        // ── v3.5+ arquitectura híbrida ────────────────────────────────────
        val KEY_USE_CAMERAX_ANALYSIS    = booleanPreferencesKey("use_camerax_analysis")
        val KEY_FORCE_TELE_PHYSICAL_ID  = booleanPreferencesKey("force_tele_physical_id")
        val KEY_TELE_PHYSICAL_ID        = stringPreferencesKey("tele_physical_id")
        val KEY_PRO_VENDOR_TAGS         = booleanPreferencesKey("pro_vendor_tags")
        val KEY_ISO_MANUAL              = intPreferencesKey("iso_manual")        // 0 = AUTO
        val KEY_SHUTTER_MANUAL_NS       = stringPreferencesKey("shutter_manual_ns") // "" = AUTO
        val KEY_WB_MANUAL_K             = intPreferencesKey("wb_manual_kelvin") // 0 = AUTO
    }

    // ─── Flows reactivos (lectura) ────────────────────────────────────────

    val histogramEnabled: Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_HISTOGRAM]   ?: false }
    val horizonEnabled:   Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_HORIZON]     ?: false }
    val focusPeaking:     Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_FOCUS_PEAK]  ?: false }
    val gridEnabled:      Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_GRID]        ?: false }

    val rawCapture:       Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_RAW]         ?: false }
    val hdrEnabled:       Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_HDR]         ?: false }
    val flashMode:        Flow<String>  = context.lensProDataStore.data.map { it[KEY_FLASH_MODE]  ?: "OFF" }
    val timerSeconds:     Flow<Int>     = context.lensProDataStore.data.map { it[KEY_TIMER]       ?: 0 }
    val hevcEnabled:      Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_HEVC]        ?: false }

    val shutterSound:     Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_SOUND]       ?: true }
    val hapticsEnabled:   Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_HAPTICS]     ?: true }

    val videoResolution:  Flow<String>  = context.lensProDataStore.data.map { it[KEY_VIDEO_RES]   ?: "FHD" }
    val videoFps:         Flow<Int>     = context.lensProDataStore.data.map { it[KEY_VIDEO_FPS]   ?: 30 }
    val video60fpsDefault: Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_VIDEO_60FPS] ?: false }

    val smoothZoom:       Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_SMOOTH_ZOOM] ?: true }
    val lastLens:         Flow<String>  = context.lensProDataStore.data.map { it[KEY_LAST_LENS]   ?: "1x" }

    val manualAspect:     Flow<String?> = context.lensProDataStore.data.map { it[KEY_MANUAL_ASPECT] }

    val organizeByDate:   Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_ORG_BY_DATE] ?: true }

    val accentIndex:      Flow<Int>     = context.lensProDataStore.data.map { it[KEY_ACCENT_INDEX] ?: 0 }
    val themeMode:        Flow<String>  = context.lensProDataStore.data.map { it[KEY_THEME_MODE]   ?: "system" }

    // ─── v3.6 — defaults SEGUROS para evitar conflictos HAL ──────────────
    // CRÍTICO: useCameraXAnalysis y forceTelePhysicalId ahora DESACTIVADOS
    // por defecto. El usuario los puede activar manualmente desde Ajustes
    // avanzados si conoce su HAL (S21 FE etc.).
    val useCameraXAnalysis:   Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_USE_CAMERAX_ANALYSIS] ?: false }
    val forceTelePhysicalId:  Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_FORCE_TELE_PHYSICAL_ID] ?: false }
    val telePhysicalId:       Flow<String>  = context.lensProDataStore.data.map { it[KEY_TELE_PHYSICAL_ID]   ?: "52" }
    val proVendorTags:        Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_PRO_VENDOR_TAGS]    ?: true }
    val isoManual:            Flow<Int>     = context.lensProDataStore.data.map { it[KEY_ISO_MANUAL]         ?: 0 }
    val shutterManualNs:      Flow<String>  = context.lensProDataStore.data.map { it[KEY_SHUTTER_MANUAL_NS]  ?: "" }
    val wbManualKelvin:       Flow<Int>     = context.lensProDataStore.data.map { it[KEY_WB_MANUAL_K]        ?: 0 }

    // ─── Setters genéricos (compat hacia atrás) ───────────────────────────

    suspend fun set(key: Preferences.Key<Boolean>, value: Boolean) {
        context.lensProDataStore.edit { it[key] = value }
    }
    suspend fun setInt(key: Preferences.Key<Int>, value: Int) {
        context.lensProDataStore.edit { it[key] = value }
    }
    suspend fun setString(key: Preferences.Key<String>, value: String) {
        context.lensProDataStore.edit { it[key] = value }
    }
    /** Versión nullable para keys String que pueden borrarse (ej. manualAspect "Auto") */
    suspend fun setStringOrRemove(key: Preferences.Key<String>, value: String?) {
        context.lensProDataStore.edit {
            if (value == null) it.remove(key) else it[key] = value
        }
    }

    // ─── Setters tipados (azúcar sintáctico) ──────────────────────────────

    suspend fun setHistogram(v: Boolean) = set(KEY_HISTOGRAM, v)
    suspend fun setHorizon(v: Boolean)   = set(KEY_HORIZON, v)
    suspend fun setGrid(v: Boolean)      = set(KEY_GRID, v)
    suspend fun setRaw(v: Boolean)       = set(KEY_RAW, v)
    suspend fun setHdr(v: Boolean)       = set(KEY_HDR, v)
    suspend fun setHevc(v: Boolean)      = set(KEY_HEVC, v)
    suspend fun setSound(v: Boolean)     = set(KEY_SOUND, v)
    suspend fun setHaptics(v: Boolean)   = set(KEY_HAPTICS, v)
    suspend fun setSmoothZoom(v: Boolean) = set(KEY_SMOOTH_ZOOM, v)
    suspend fun setVideo60Default(v: Boolean) = set(KEY_VIDEO_60FPS, v)
    suspend fun setOrganizeByDate(v: Boolean) = set(KEY_ORG_BY_DATE, v)
    suspend fun setFocusPeaking(v: Boolean)  = set(KEY_FOCUS_PEAK, v)

    suspend fun setTimer(seconds: Int)    = setInt(KEY_TIMER, seconds)
    suspend fun setVideoFps(fps: Int)     = setInt(KEY_VIDEO_FPS, fps)
    suspend fun setAccentIndex(idx: Int)  = setInt(KEY_ACCENT_INDEX, idx)

    suspend fun setFlashMode(mode: String)      = setString(KEY_FLASH_MODE, mode)
    suspend fun setVideoResolution(res: String) = setString(KEY_VIDEO_RES, res)
    suspend fun setThemeMode(mode: String)      = setString(KEY_THEME_MODE, mode)
    suspend fun setLastLens(lens: String)       = setString(KEY_LAST_LENS, lens)
    suspend fun setManualAspect(label: String?) = setStringOrRemove(KEY_MANUAL_ASPECT, label)

    // ── v3.5 setters ─────────────────────────────────────────────────────
    suspend fun setUseCameraXAnalysis(v: Boolean)   = set(KEY_USE_CAMERAX_ANALYSIS, v)
    suspend fun setForceTelePhysicalId(v: Boolean)  = set(KEY_FORCE_TELE_PHYSICAL_ID, v)
    suspend fun setTelePhysicalId(id: String)       = setString(KEY_TELE_PHYSICAL_ID, id)
    suspend fun setProVendorTags(v: Boolean)        = set(KEY_PRO_VENDOR_TAGS, v)
    suspend fun setIsoManual(iso: Int)              = setInt(KEY_ISO_MANUAL, iso)
    suspend fun setShutterManualNs(ns: Long?)       =
        setStringOrRemove(KEY_SHUTTER_MANUAL_NS, ns?.toString())
    suspend fun setWbManualKelvin(k: Int)           = setInt(KEY_WB_MANUAL_K, k)

    // ─── Conversiones tipo enum ↔ string/int ──────────────────────────────

    fun accentFromIndex(idx: Int): AccentStyle =
        AccentStyle.entries.getOrElse(idx) { AccentStyle.ICE_BLUE }

    fun indexOfAccent(style: AccentStyle): Int =
        AccentStyle.entries.indexOf(style).coerceAtLeast(0)

    fun themeFromString(s: String): Boolean? = when (s) {
        "dark" -> true; "light" -> false; else -> null
    }
    fun themeToString(v: Boolean?): String = when (v) {
        true -> "dark"; false -> "light"; null -> "system"
    }

    fun flashFromString(s: String): FlashMode = try {
        FlashMode.valueOf(s)
    } catch (_: Throwable) { FlashMode.OFF }
    fun flashToString(m: FlashMode): String = m.name

    fun videoResFromString(s: String): VideoResolution = try {
        VideoResolution.valueOf(s)
    } catch (_: Throwable) { VideoResolution.FHD }
    fun videoResToString(r: VideoResolution): String = r.name

    fun videoFpsFromInt(v: Int): VideoFps = when (v) {
        60 -> VideoFps.FPS60; else -> VideoFps.FPS30
    }
    fun videoFpsToInt(f: VideoFps): Int = f.value

    fun aspectFromLabel(label: String?): PreviewAspect? = when (label) {
        "3:4"  -> PreviewAspect.RATIO_3_4
        "9:16" -> PreviewAspect.RATIO_9_16
        "1:1"  -> PreviewAspect.RATIO_1_1
        "FULL" -> PreviewAspect.RATIO_FULL
        else   -> null
    }
    fun aspectToLabel(a: PreviewAspect?): String? = when (a) {
        PreviewAspect.RATIO_3_4  -> "3:4"
        PreviewAspect.RATIO_9_16 -> "9:16"
        PreviewAspect.RATIO_1_1  -> "1:1"
        PreviewAspect.RATIO_FULL -> "FULL"
        null -> null
    }

    fun shutterNsFromString(s: String): Long? = try {
        if (s.isBlank()) null else s.toLong().coerceAtLeast(0L).takeIf { it > 0L }
    } catch (_: Throwable) { null }
}
