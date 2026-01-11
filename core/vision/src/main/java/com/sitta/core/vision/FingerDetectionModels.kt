package com.sitta.core.vision

import android.graphics.RectF

data class FingerDetectionResult(
    val isDetected: Boolean,
    val boundingBox: RectF?,
    val landmarks: List<FingerLandmark>,
    val confidence: Float,
    val orientationAngle: Float,
    val palmFacing: Boolean,
) {
    companion object {
        fun notDetected() = FingerDetectionResult(
            isDetected = false,
            boundingBox = null,
            landmarks = emptyList(),
            confidence = 0f,
            orientationAngle = 0f,
            palmFacing = false,
        )
    }
}

data class FingerLandmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val type: LandmarkType,
)

enum class LandmarkType {
    WRIST,
    THUMB_CMC,
    THUMB_MCP,
    THUMB_IP,
    THUMB_TIP,
    INDEX_MCP,
    INDEX_PIP,
    INDEX_DIP,
    INDEX_TIP,
    MIDDLE_MCP,
    MIDDLE_PIP,
    MIDDLE_DIP,
    MIDDLE_TIP,
    RING_MCP,
    RING_PIP,
    RING_DIP,
    RING_TIP,
    PINKY_MCP,
    PINKY_PIP,
    PINKY_DIP,
    PINKY_TIP,
}
