package com.sitta.feature.track_a

import android.graphics.Rect

internal fun buildLeftEllipseRoi(width: Int, height: Int): Rect {
    val roiWidth = (width * 0.52f).toInt()
    val roiHeight = (height * 0.68f).toInt()
    val left = (width * 0.08f).toInt()
    val top = ((height - roiHeight) / 2f).toInt()
    return Rect(left, top, (left + roiWidth).coerceAtMost(width), (top + roiHeight).coerceAtMost(height))
}
