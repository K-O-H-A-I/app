package com.sitta.core.vision

import android.graphics.Rect

data class FrameCrop(
    val frameWidth: Int,
    val frameHeight: Int,
    val cropRect: Rect,
)
