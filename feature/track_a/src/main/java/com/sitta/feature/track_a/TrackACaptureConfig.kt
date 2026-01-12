package com.sitta.feature.track_a

object TrackACaptureConfig {
    // Ready thresholds (enter vs exit) to avoid flicker.
    const val qualityEnterScore = 38
    const val qualityExitScore = 26
    const val centerEnterScore = 22
    const val centerExitScore = 12
    const val coverageEnter = 0.08
    const val coverageExit = 0.05
    const val detectionStableMs = 140L
    const val readyStableMs = 200L
    const val autoCaptureHoldMs = 260L
    const val autoCaptureCooldownMs = 1200L
}
