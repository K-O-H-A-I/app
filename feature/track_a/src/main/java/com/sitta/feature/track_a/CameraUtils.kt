package com.sitta.feature.track_a

import android.graphics.Rect
import kotlin.math.roundToInt

internal fun buildGuideRoi(width: Int, height: Int): Rect {
    val windowWidth = (width * 0.78f).roundToInt().coerceIn((width * 0.65f).roundToInt(), (width * 0.85f).roundToInt())
    val windowHeight = (height * 0.22f).roundToInt().coerceIn((height * 0.18f).roundToInt(), (height * 0.28f).roundToInt())
    val left = ((width - windowWidth) / 2f).roundToInt()
    val top = ((height - windowHeight) / 2f).roundToInt() - (height * 0.08f).roundToInt()
    val safeTop = top.coerceAtLeast(0)
    return Rect(left, safeTop, (left + windowWidth).coerceAtMost(width), (safeTop + windowHeight).coerceAtMost(height))
}
