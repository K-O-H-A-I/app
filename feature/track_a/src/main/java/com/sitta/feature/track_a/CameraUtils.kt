package com.sitta.feature.track_a

import android.graphics.Rect
import kotlin.math.roundToInt

internal fun buildGuideRoi(width: Int, height: Int): Rect {
    val windowWidth = (width * 0.68f).roundToInt().coerceIn((width * 0.6f).roundToInt(), (width * 0.78f).roundToInt())
    val windowHeight = (height * 0.52f).roundToInt().coerceIn((height * 0.45f).roundToInt(), (height * 0.62f).roundToInt())
    val left = ((width - windowWidth) / 2f).roundToInt().coerceAtLeast(0)
    val top = ((height - windowHeight) / 2f).roundToInt().coerceAtLeast(0)
    val safeTop = top.coerceAtLeast(0)
    return Rect(left, safeTop, (left + windowWidth).coerceAtMost(width), (safeTop + windowHeight).coerceAtMost(height))
}
