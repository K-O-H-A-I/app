package com.sitta.core.vision

import android.graphics.Bitmap
import android.graphics.Rect

class FingerSceneAnalyzer {
    data class SceneResult(
        val cluttered: Boolean,
        val skinOutsideRatio: Double,
    )

    fun analyze(bitmap: Bitmap, fingerRect: Rect?): SceneResult {
        if (fingerRect == null) return SceneResult(cluttered = true, skinOutsideRatio = 1.0)

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var skinInside = 0
        var skinOutside = 0
        val step = 6

        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val pixel = pixels[y * width + x]
                if (!isSkinColor(pixel)) continue
                if (fingerRect.contains(x, y)) {
                    skinInside++
                } else {
                    skinOutside++
                }
            }
        }

        val totalSkin = skinInside + skinOutside
        val outsideRatio = if (totalSkin == 0) 1.0 else skinOutside.toDouble() / totalSkin.toDouble()
        val cluttered = outsideRatio > 0.6 && skinInside < skinOutside
        return SceneResult(cluttered = cluttered, skinOutsideRatio = outsideRatio)
    }

    private fun isSkinColor(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min

        val v = max.toFloat()
        val s = if (max == 0) 0f else (delta.toFloat() / max) * 255
        val h = when {
            delta == 0 -> 0f
            max == r -> 60 * (((g - b).toFloat() / delta) % 6)
            max == g -> 60 * (((b - r).toFloat() / delta) + 2)
            else -> 60 * (((r - g).toFloat() / delta) + 4)
        }.let { if (it < 0) it + 360 else it }

        return h in 0f..25f && s in 40f..255f && v in 60f..255f
    }
}
