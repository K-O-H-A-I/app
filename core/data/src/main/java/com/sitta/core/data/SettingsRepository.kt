package com.sitta.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsRepository {
    private val livenessEnabledState = MutableStateFlow(false)
    private val autoCaptureEnabledState = MutableStateFlow(true)
    private val debugOverlayEnabledState = MutableStateFlow(false)

    val livenessEnabled: StateFlow<Boolean> = livenessEnabledState
    val autoCaptureEnabled: StateFlow<Boolean> = autoCaptureEnabledState
    val debugOverlayEnabled: StateFlow<Boolean> = debugOverlayEnabledState

    fun setLivenessEnabled(enabled: Boolean) {
        livenessEnabledState.value = enabled
    }

    fun setAutoCaptureEnabled(enabled: Boolean) {
        autoCaptureEnabledState.value = enabled
    }

    fun setDebugOverlayEnabled(enabled: Boolean) {
        debugOverlayEnabledState.value = enabled
    }
}
