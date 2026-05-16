package com.rodyto.lenspro

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.lensProDataStore by preferencesDataStore(name = "lenspro_settings")

/**
 * SettingsRepository — persistencia ligera vía DataStore.
 * Conserva flags premium que NO viven en el ViewModel:
 *   • histograma activo / horizonte / focus peaking
 *   • carpeta de organización (date / project)
 *   • estilo de paleta (override) + tema
 *   • formato preferido JPG / RAW
 */
class SettingsRepository(private val context: Context) {

    companion object {
        val KEY_HISTOGRAM = booleanPreferencesKey("histogram_enabled")
        val KEY_HORIZON = booleanPreferencesKey("horizon_enabled")
        val KEY_FOCUS_PEAK = booleanPreferencesKey("focus_peaking")
        val KEY_RAW = booleanPreferencesKey("raw_capture")
        val KEY_ORG_BY_DATE = booleanPreferencesKey("organize_by_date")
        val KEY_ACCENT_INDEX = intPreferencesKey("accent_index")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode") // "system" / "dark" / "light"
        val KEY_SMOOTH_ZOOM = booleanPreferencesKey("smooth_zoom")
        val KEY_VIDEO_60FPS = booleanPreferencesKey("video_60fps_default")
    }

    val histogramEnabled: Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_HISTOGRAM] ?: false }
    val horizonEnabled: Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_HORIZON] ?: false }
    val focusPeaking: Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_FOCUS_PEAK] ?: false }
    val rawCapture: Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_RAW] ?: false }
    val organizeByDate: Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_ORG_BY_DATE] ?: true }
    val accentIndex: Flow<Int> = context.lensProDataStore.data.map { it[KEY_ACCENT_INDEX] ?: 0 }
    val themeMode: Flow<String> = context.lensProDataStore.data.map { it[KEY_THEME_MODE] ?: "system" }
    val smoothZoom: Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_SMOOTH_ZOOM] ?: true }
    val video60fpsDefault: Flow<Boolean> = context.lensProDataStore.data.map { it[KEY_VIDEO_60FPS] ?: false }

    suspend fun set(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        context.lensProDataStore.edit { it[key] = value }
    }
    suspend fun setInt(key: androidx.datastore.preferences.core.Preferences.Key<Int>, value: Int) {
        context.lensProDataStore.edit { it[key] = value }
    }
    suspend fun setString(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        context.lensProDataStore.edit { it[key] = value }
    }
}
