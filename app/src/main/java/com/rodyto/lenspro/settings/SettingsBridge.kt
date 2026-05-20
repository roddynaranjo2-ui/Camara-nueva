package com.rodyto.lenspro.settings

import com.rodyto.lenspro.CameraControlViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * SettingsBridge — Centraliza la sincronización entre el repositorio de ajustes y el ViewModel.
 * Resuelve BUG-F8, F9, F10 e INCO-4/6.
 */
class SettingsBridge(
    private val repository: SettingsRepository,
    private val viewModel: CameraControlViewModel,
    private val scope: CoroutineScope
) {
    fun wire() {
        scope.launch {
            repository.gridEnabled.collect { viewModel.setGridEnabled(it) }
        }
        scope.launch {
            repository.soundEnabled.collect { viewModel.setSoundEnabled(it) }
        }
        scope.launch {
            repository.hapticsEnabled.collect { viewModel.setHapticsEnabled(it) }
        }
        // Añadir aquí más sincronizaciones según sea necesario
    }
}
