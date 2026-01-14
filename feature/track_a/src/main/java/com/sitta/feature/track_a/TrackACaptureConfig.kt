package com.sitta.feature.track_a

object TrackACaptureConfig {
    // Ready thresholds (enter vs exit) to avoid flicker.
    const val qualityEnterScore = 38
    const val qualityExitScore = 26
    const val centerEnterScore = 22
    const val centerExitScore = 12
    const val coverageEnter = 0.08
    const val coverageExit = 0.05
    const val detectionStableMs = 100L
    const val readyStableMs = 200L
    const val autoCaptureHoldMs = 180L
    const val readyPassCount = 6
    const val readyFailCount = 3
    const val focusEnterScore = 70
    const val focusExitScore = 60
    const val lightEnterScore = 70
    const val lightExitScore = 60
    const val steadyEnterScore = 70
    const val steadyExitScore = 60
    const val torchAutoOnScore = 55
    const val torchAutoOffScore = 78
    const val torchDebounceMs = 450L
    const val autoCaptureCooldownMs = 1200L
    const val focusHoldMs = 180L
    const val focusRefreshMs = 2000L
    const val focusMaxWaitMs = 220L
    const val minFingerScalePx = 42f
}
