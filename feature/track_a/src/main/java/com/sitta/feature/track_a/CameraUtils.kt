package com.sitta.feature.track_a

import android.graphics.Rect

internal fun buildCenteredRoi(width: Int, height: Int, scale: Float = 0.6f): Rect {
    val roiWidth = (width * scale).toInt()
    val roiHeight = (height * scale).toInt()
    val left = (width - roiWidth) / 2
    val top = (height - roiHeight) / 2
    return Rect(left, top, left + roiWidth, top + roiHeight)
}
