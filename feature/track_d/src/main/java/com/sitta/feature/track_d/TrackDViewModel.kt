package com.sitta.feature.track_d

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sitta.core.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TrackDViewModel(private val settingsRepository: SettingsRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(TrackDState())
    val uiState: StateFlow<TrackDState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.autoCaptureEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(autoCaptureEnabled = enabled)
            }
        }
        viewModelScope.launch {
            settingsRepository.debugOverlayEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(debugOverlayEnabled = enabled)
            }
        }
    }

    fun setAutoCaptureEnabled(enabled: Boolean) {
        settingsRepository.setAutoCaptureEnabled(enabled)
    }

    fun setDebugOverlayEnabled(enabled: Boolean) {
        settingsRepository.setDebugOverlayEnabled(enabled)
    }
}

data class TrackDState(
    val autoCaptureEnabled: Boolean = true,
    val debugOverlayEnabled: Boolean = false,
)
