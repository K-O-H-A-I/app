package com.sitta.feature.track_a

object TrackACaptureConfig {
    // Ready thresholds (enter vs exit) to avoid flicker.
    const val qualityEnterScore = 60
    const val qualityExitScore = 45
    const val centerEnterScore = 55
    const val centerExitScore = 35
    const val coverageEnter = 0.22
    const val coverageExit = 0.15
    const val detectionStableMs = 300L
    const val readyStableMs = 500L
    const val autoCaptureHoldMs = 700L
    const val autoCaptureCooldownMs = 1800L
}
