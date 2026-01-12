package com.sitta.feature.track_a

import android.graphics.Rect
import kotlin.math.roundToInt

internal fun buildGuideRoi(width: Int, height: Int): Rect {
    val windowWidth = (width * 0.62f).roundToInt().coerceIn((width * 0.55f).roundToInt(), (width * 0.75f).roundToInt())
    val windowHeight = (height * 0.68f).roundToInt().coerceIn((height * 0.58f).roundToInt(), (height * 0.78f).roundToInt())
    val left = 0
    val top = ((height - windowHeight) / 2f).roundToInt() + (height * 0.08f).roundToInt()
    val safeTop = top.coerceAtLeast(0)
    return Rect(left, safeTop, (left + windowWidth).coerceAtMost(width), (safeTop + windowHeight).coerceAtMost(height))
}
