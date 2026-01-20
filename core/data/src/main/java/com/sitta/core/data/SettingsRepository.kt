package com.sitta.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsRepository {
    private val livenessEnabledState = MutableStateFlow(false)
    private val autoCaptureEnabledState = MutableStateFlow(true)

    val livenessEnabled: StateFlow<Boolean> = livenessEnabledState
    val autoCaptureEnabled: StateFlow<Boolean> = autoCaptureEnabledState

    fun setLivenessEnabled(enabled: Boolean) {
        livenessEnabledState.value = enabled
    }

    fun setAutoCaptureEnabled(enabled: Boolean) {
        autoCaptureEnabledState.value = enabled
    }

}
