package com.sitta.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsRepository {
    private val livenessEnabledState = MutableStateFlow(false)

    val livenessEnabled: StateFlow<Boolean> = livenessEnabledState

    fun setLivenessEnabled(enabled: Boolean) {
        livenessEnabledState.value = enabled
    }
}
