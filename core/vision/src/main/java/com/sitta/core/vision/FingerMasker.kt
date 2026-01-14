package com.sitta.core.vision

import android.graphics.Bitmap
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

class FingerMasker {
    fun applyMask(bitmap: Bitmap): Bitmap {
        val mat = OpenCvUtils.bitmapToMat(bitmap)
        val rgb = Mat()
        Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_RGBA2RGB)
        val ycrcb = Mat()
        Imgproc.cvtColor(rgb, ycrcb, Imgproc.COLOR_RGB2YCrCb)
        val mask = Mat()
        Core.inRange(ycrcb, Scalar(0.0, 133.0, 77.0), Scalar(255.0, 173.0, 127.0), mask)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)

        val masked = Mat(mat.size(), mat.type(), Scalar(0.0, 0.0, 0.0, 255.0))
        mat.copyTo(masked, mask)

        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        org.opencv.android.Utils.matToBitmap(masked, out)
        return out
    }

    fun applyFingerTipMask(
        bitmap: Bitmap,
        landmarks: List<FingerLandmark>,
        roi: android.graphics.Rect,
        fullWidth: Int,
        fullHeight: Int,
    ): Bitmap {
        if (landmarks.isEmpty()) return applyMask(bitmap)

        val mat = OpenCvUtils.bitmapToMat(bitmap)
        val rgb = Mat()
        Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_RGBA2RGB)
        val ycrcb = Mat()
        Imgproc.cvtColor(rgb, ycrcb, Imgproc.COLOR_RGB2YCrCb)
        val skinMask = Mat()
        Core.inRange(ycrcb, Scalar(0.0, 133.0, 77.0), Scalar(255.0, 173.0, 127.0), skinMask)

        val tipMask = Mat.zeros(skinMask.size(), skinMask.type())
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))

        val fingerPairs = listOf(
            LandmarkType.INDEX_TIP to LandmarkType.INDEX_DIP,
            LandmarkType.MIDDLE_TIP to LandmarkType.MIDDLE_DIP,
            LandmarkType.RING_TIP to LandmarkType.RING_DIP,
            LandmarkType.PINKY_TIP to LandmarkType.PINKY_DIP,
        )

        fingerPairs.forEach { (tipType, dipType) ->
            val tip = landmarks.firstOrNull { it.type == tipType }
            val dip = landmarks.firstOrNull { it.type == dipType }
            if (tip == null || dip == null) return@forEach
            val tipPoint = mapToRoiPoint(tip, roi, fullWidth, fullHeight)
            val dipPoint = mapToRoiPoint(dip, roi, fullWidth, fullHeight)
            if (tipPoint == null || dipPoint == null) return@forEach
            val dx = tipPoint.x - dipPoint.x
            val dy = tipPoint.y - dipPoint.y
            val length = hypot(dx, dy)
            if (length < 4.0) return@forEach
            val depthScale = (1.0 + (-tip.z).coerceIn(-0.3f, 0.3f)).toDouble()
            val radius = (length * 0.33 * depthScale).coerceIn(5.0, 26.0)
            val vx = dx / length
            val vy = dy / length
            val gatePoint = Point(
                (tipPoint.x - vx * length * 0.75).coerceIn(0.0, bitmap.width.toDouble()),
                (tipPoint.y - vy * length * 0.75).coerceIn(0.0, bitmap.height.toDouble()),
            )
            Imgproc.line(tipMask, tipPoint, gatePoint, Scalar(255.0), (radius * 1.7).toInt())
            Imgproc.circle(tipMask, tipPoint, radius.toInt(), Scalar(255.0), -1)
        }

        if (Core.countNonZero(tipMask) == 0) {
            return applyMask(bitmap)
        }

        val combined = Mat()
        Core.bitwise_and(skinMask, tipMask, combined)
        Imgproc.morphologyEx(combined, combined, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(combined, combined, Imgproc.MORPH_CLOSE, kernel)
        val dilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.dilate(combined, combined, dilateKernel)

        val masked = Mat(mat.size(), mat.type(), Scalar(0.0, 0.0, 0.0, 255.0))
        mat.copyTo(masked, combined)

        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        org.opencv.android.Utils.matToBitmap(masked, out)
        return out
    }

    fun cropToFingertips(
        bitmap: Bitmap,
        fallback: Bitmap,
        padFraction: Float = 0.18f,
        targetMaxSide: Int = 360,
        minCoverage: Float = 0.08f,
        minSideFraction: Float = 0.22f,
        maxScale: Float = 1.6f,
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0
        var hit = false
        var nonBlack = 0

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val pixel = pixels[row + x]
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                if (r + g + b > 24) {
                    hit = true
                    nonBlack++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (!hit || minX >= maxX || minY >= maxY) return fallback
        val coverage = nonBlack.toFloat() / (width * height).toFloat()
        if (coverage < minCoverage) return fallback

        val padX = ((maxX - minX) * padFraction).toInt().coerceAtLeast(4)
        val padY = ((maxY - minY) * padFraction).toInt().coerceAtLeast(4)

        val left = (minX - padX).coerceAtLeast(0)
        val top = (minY - padY).coerceAtLeast(0)
        val right = (maxX + padX).coerceAtMost(width - 1)
        val bottom = (maxY + padY).coerceAtMost(height - 1)

        val cropWidth = (right - left).coerceAtLeast(1)
        val cropHeight = (bottom - top).coerceAtLeast(1)
        if (cropWidth < (width * minSideFraction) || cropHeight < (height * minSideFraction)) {
            return fallback
        }
        val cropped = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)

        val maxSide = maxOf(cropWidth, cropHeight)
        val scale = (targetMaxSide.toFloat() / maxSide.toFloat()).coerceIn(1f, maxScale)
        return if (scale > 1f) {
            Bitmap.createScaledBitmap(
                cropped,
                (cropWidth * scale).toInt().coerceAtLeast(1),
                (cropHeight * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            cropped
        }
    }

    private fun mapToRoiPoint(
        landmark: FingerLandmark,
        roi: android.graphics.Rect,
        fullWidth: Int,
        fullHeight: Int,
    ): Point? {
        val x = landmark.x * fullWidth
        val y = landmark.y * fullHeight
        val rx = x - roi.left
        val ry = y - roi.top
        if (rx < 0 || ry < 0 || rx > roi.width() || ry > roi.height()) return null
        return Point(rx.toDouble(), ry.toDouble())
    }
}
