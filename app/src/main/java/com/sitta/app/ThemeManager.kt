package com.sitta.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ThemeManager {
    private val darkState = MutableStateFlow(true)
    val isDark: StateFlow<Boolean> = darkState

    fun toggle() {
        darkState.value = !darkState.value
    }
}
