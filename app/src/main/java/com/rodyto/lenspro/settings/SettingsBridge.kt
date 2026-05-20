package com.rodyto.lenspro.settings

import com.rodyto.lenspro.CameraControlViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * SettingsBridge — Centraliza la sincronización entre el repositorio de ajustes y el ViewModel.
 * v4.2 — Corregidos imports y referencias.
 */
class SettingsBridge(
    private val repository: SettingsRepository,
    private val viewModel: CameraControlViewModel,
    private val scope: CoroutineScope
) {
    fun wire() {
        scope.launch {
            repository.gridEnabled.collect { enabled -> viewModel.setGridEnabled(enabled) }
        }
        scope.launch {
            repository.shutterSound.collect { sound -> viewModel.setSoundEnabled(sound) }
        }
        scope.launch {
            repository.hapticsEnabled.collect { haptics -> viewModel.setHapticsEnabled(haptics) }
        }
        scope.launch {
            repository.flashMode.collect { flash -> viewModel.setFlashMode(repository.flashFromString(flash)) }
        }
        scope.launch {
            repository.timerSeconds.collect { seconds -> viewModel.setTimerSeconds(seconds) }
        }
    }
}
