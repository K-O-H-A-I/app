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
            settingsRepository.livenessEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(livenessEnabled = enabled)
            }
        }
    }

    fun setLivenessEnabled(enabled: Boolean) {
        settingsRepository.setLivenessEnabled(enabled)
    }
}

data class TrackDState(
    val livenessEnabled: Boolean = false,
)
