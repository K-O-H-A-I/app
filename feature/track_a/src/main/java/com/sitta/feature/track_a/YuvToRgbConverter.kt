package com.sitta.feature.track_a

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.min

class YuvToRgbConverter {
    private var pixelBuffer: IntArray = IntArray(0)

    fun toBitmap(
        image: ImageProxy,
        crop: Rect? = null,
        maxSize: Int = 480,
        reuse: Bitmap? = null,
    ): Bitmap {
        val rect = crop ?: Rect(0, 0, image.width, image.height)
        val cropWidth = rect.width().coerceAtLeast(1)
        val cropHeight = rect.height().coerceAtLeast(1)
        val maxDim = max(cropWidth, cropHeight)
        val scale = min(1f, maxSize.toFloat() / maxDim.toFloat())
        val outWidth = max(1, (cropWidth * scale).toInt())
        val outHeight = max(1, (cropHeight * scale).toInt())
        val pixelCount = outWidth * outHeight
        if (pixelBuffer.size < pixelCount) {
            pixelBuffer = IntArray(pixelCount)
        }
        when (image.format) {
            ImageFormat.YUV_420_888 -> {
                yuvToArgb(image, rect, outWidth, outHeight, pixelBuffer)
            }
            ImageFormat.JPEG -> {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                return if (bitmap.width == outWidth && bitmap.height == outHeight) {
                    bitmap
                } else {
                    Bitmap.createScaledBitmap(bitmap, outWidth, outHeight, true)
                }
            }
            else -> {
                yuvToArgb(image, rect, outWidth, outHeight, pixelBuffer)
            }
        }
        val bitmap = if (reuse != null && reuse.width == outWidth && reuse.height == outHeight) {
            reuse
        } else {
            Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        }
        bitmap.setPixels(pixelBuffer, 0, outWidth, 0, 0, outWidth, outHeight)
        return bitmap
    }

    private fun yuvToArgb(
        image: ImageProxy,
        crop: Rect,
        outWidth: Int,
        outHeight: Int,
        output: IntArray,
    ) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val scaleX = crop.width().toFloat() / outWidth.toFloat()
        val scaleY = crop.height().toFloat() / outHeight.toFloat()

        var outIndex = 0
        for (yOut in 0 until outHeight) {
            val srcY = crop.top + (yOut * scaleY).toInt()
            val yRow = yRowStride * srcY
            val uvRow = uRowStride * (srcY / 2)
            val vRow = vRowStride * (srcY / 2)
            for (xOut in 0 until outWidth) {
                val srcX = crop.left + (xOut * scaleX).toInt()
                val yIndex = yRow + srcX * yPixelStride
                val uvIndex = uvRow + (srcX / 2) * uPixelStride
                val vIndex = vRow + (srcX / 2) * vPixelStride

                val yVal = (yBuffer.get(yIndex).toInt() and 0xFF) - 16
                val uVal = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                val vVal = (vBuffer.get(vIndex).toInt() and 0xFF) - 128

                val c = if (yVal < 0) 0 else yVal
                var r = (298 * c + 409 * vVal + 128) shr 8
                var g = (298 * c - 100 * uVal - 208 * vVal + 128) shr 8
                var b = (298 * c + 516 * uVal + 128) shr 8
                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)
                output[outIndex++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}
