package com.rodyto.lenspro.settings

import com.rodyto.lenspro.CameraControlViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * SettingsBridge · v5.0
 *
 * Cambios v5.0:
 *  • FIX BUG-A4: única fuente de sincronización repo→VM (los LaunchedEffect
 *    duplicados de MainActivityCore fueron eliminados).
 *  • FIX BUG-M5: se cablea proVendorTags al ViewModel.
 *  • distinctUntilChanged() en cada flow para evitar trabajo redundante.
 */
class SettingsBridge(
    private val repository: SettingsRepository,
    private val viewModel: CameraControlViewModel,
    private val scope: CoroutineScope
) {
    fun wire() {
        scope.launch { repository.gridEnabled.distinctUntilChanged().collect    { viewModel.setGridEnabled(it) } }
        scope.launch { repository.shutterSound.distinctUntilChanged().collect   { viewModel.setSoundEnabled(it) } }
        scope.launch { repository.hapticsEnabled.distinctUntilChanged().collect { viewModel.setHapticsEnabled(it) } }
        scope.launch { repository.flashMode.distinctUntilChanged().collect      { viewModel.setFlashMode(repository.flashFromString(it)) } }
        scope.launch { repository.timerSeconds.distinctUntilChanged().collect   { viewModel.setTimerSeconds(it) } }
        scope.launch { repository.proVendorTags.distinctUntilChanged().collect  { viewModel.setProVendorTags(it) } }
    }
}
