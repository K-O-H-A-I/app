package com.sitta.core.vision

import android.graphics.Bitmap
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

class FingerMasker {
    data class SegmentationResult(
        val masked: Bitmap,
        val maskRatio: Double,
        val maskArea: Int,
    )
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

    fun segmentWithGrabCut(bitmap: Bitmap): Bitmap? {
        val mat = OpenCvUtils.bitmapToMat(bitmap)
        val bgr = Mat()
        Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_RGBA2BGR)
        val hsv = Mat()
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)

        val hsvMask = Mat()
        Core.inRange(hsv, Scalar(0.0, 30.0, 60.0), Scalar(20.0, 170.0, 255.0), hsvMask)
        val hsvKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
        Imgproc.morphologyEx(hsvMask, hsvMask, Imgproc.MORPH_CLOSE, hsvKernel, Point(-1.0, -1.0), 2)
        Imgproc.morphologyEx(hsvMask, hsvMask, Imgproc.MORPH_OPEN, hsvKernel, Point(-1.0, -1.0), 1)

        val gcMask = Mat.zeros(bgr.size(), CvType.CV_8UC1)
        val rect = Rect(
            (bgr.cols() * 0.05).toInt(),
            (bgr.rows() * 0.05).toInt(),
            (bgr.cols() * 0.9).toInt(),
            (bgr.rows() * 0.9).toInt(),
        )
        val bgd = Mat.zeros(1, 65, CvType.CV_64F)
        val fgd = Mat.zeros(1, 65, CvType.CV_64F)
        Imgproc.grabCut(bgr, gcMask, rect, bgd, fgd, 5, Imgproc.GC_INIT_WITH_RECT)

        val fg = Mat()
        val prFg = Mat()
        Core.compare(gcMask, Scalar(Imgproc.GC_FGD.toDouble()), fg, Core.CMP_EQ)
        Core.compare(gcMask, Scalar(Imgproc.GC_PR_FGD.toDouble()), prFg, Core.CMP_EQ)
        val grabMask = Mat()
        Core.bitwise_or(fg, prFg, grabMask)

        val grabKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        Imgproc.morphologyEx(grabMask, grabMask, Imgproc.MORPH_CLOSE, grabKernel, Point(-1.0, -1.0), 2)

        val combined = Mat()
        Core.bitwise_and(hsvMask, grabMask, combined)
        val largest = keepLargestComponent(combined) ?: return null
        if (Core.countNonZero(largest) == 0) return null

        val masked = Mat(mat.size(), mat.type(), Scalar(0.0, 0.0, 0.0, 255.0))
        mat.copyTo(masked, largest)

        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        org.opencv.android.Utils.matToBitmap(masked, out)
        return out
    }

    fun segmentFingertips(
        bitmap: Bitmap,
        detection: FingerDetectionResult?,
        minAreaPx: Int = 2000,
    ): SegmentationResult? {
        val mat = OpenCvUtils.bitmapToMat(bitmap)
        val bgr = Mat()
        Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_RGBA2BGR)
        val hsv = Mat()
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)

        val hsvMask = Mat()
        Core.inRange(hsv, Scalar(0.0, 30.0, 60.0), Scalar(20.0, 170.0, 255.0), hsvMask)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
        Imgproc.morphologyEx(hsvMask, hsvMask, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
        Imgproc.morphologyEx(hsvMask, hsvMask, Imgproc.MORPH_OPEN, kernel, Point(-1.0, -1.0), 1)

        val hasLandmarks = detection?.landmarks?.isNotEmpty() == true
        val mask = if (hasLandmarks) {
            val padMask = buildPadMask(
                detection!!.landmarks,
                bitmap.width,
                bitmap.height,
            )
            if (Core.countNonZero(padMask) == 0) {
                null
            } else {
                val combined = Mat()
                Core.bitwise_and(hsvMask, padMask, combined)
                val hsvCoverage = Core.countNonZero(combined).toDouble() / (bitmap.width * bitmap.height).toDouble()
                val refined = if (hsvCoverage > 0.85) {
                    combined
                } else {
                    runGrabCutWithMask(bgr, hsvMask, padMask)
                }
                val cleaned = filterComponentsByPadOverlap(refined, padMask, minAreaPx)
                cleaned
            }
        } else {
            val grabMask = runGrabCut(bgr)
            val combined = Mat()
            Core.bitwise_and(hsvMask, grabMask, combined)
            val innerRect = Rect(
                (bitmap.width * 0.2).toInt(),
                (bitmap.height * 0.2).toInt(),
                (bitmap.width * 0.6).toInt(),
                (bitmap.height * 0.6).toInt(),
            )
            val rectMask = Mat.zeros(combined.size(), combined.type())
            Imgproc.rectangle(rectMask, innerRect, Scalar(255.0), -1)
            Core.bitwise_and(combined, rectMask, combined)
            keepLargestComponent(combined)
        } ?: return null

        if (Core.countNonZero(mask) == 0) return null
        val masked = Mat(mat.size(), mat.type(), Scalar(0.0, 0.0, 0.0, 255.0))
        mat.copyTo(masked, mask)
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        org.opencv.android.Utils.matToBitmap(masked, out)
        val maskArea = Core.countNonZero(mask)
        val maskRatio = maskArea.toDouble() / (bitmap.width * bitmap.height).toDouble()
        return SegmentationResult(out, maskRatio, maskArea)
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

    private fun keepLargestComponent(mask: Mat): Mat? {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)
        if (count <= 1) return mask
        var bestLabel = 1
        var bestArea = 0.0
        for (i in 1 until count) {
            val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
            if (area > bestArea) {
                bestArea = area
                bestLabel = i
            }
        }
        val out = Mat.zeros(mask.size(), mask.type())
        for (y in 0 until labels.rows()) {
            for (x in 0 until labels.cols()) {
                if (labels.get(y, x)[0].toInt() == bestLabel) {
                    out.put(y, x, 255.0)
                }
            }
        }
        return out
    }

    private fun removeSmallComponents(mask: Mat, minAreaPx: Int): Mat {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)
        if (count <= 1) return mask
        val out = Mat.zeros(mask.size(), mask.type())
        for (label in 1 until count) {
            val area = stats.get(label, Imgproc.CC_STAT_AREA)[0].toInt()
            if (area < minAreaPx) continue
            for (y in 0 until labels.rows()) {
                for (x in 0 until labels.cols()) {
                    if (labels.get(y, x)[0].toInt() == label) {
                        out.put(y, x, 255.0)
                    }
                }
            }
        }
        return out
    }

    private fun runGrabCutWithMask(bgr: Mat, hsvMask: Mat, padMask: Mat): Mat {
        val gcMask = Mat.zeros(bgr.size(), CvType.CV_8UC1)
        gcMask.setTo(Scalar(Imgproc.GC_BGD.toDouble()))
        val padPixels = Mat()
        Core.compare(padMask, Scalar(0.0), padPixels, Core.CMP_GT)
        val hsvPixels = Mat()
        Core.compare(hsvMask, Scalar(0.0), hsvPixels, Core.CMP_GT)

        gcMask.setTo(Scalar(Imgproc.GC_PR_BGD.toDouble()), hsvPixels)
        gcMask.setTo(Scalar(Imgproc.GC_PR_FGD.toDouble()), padPixels)
        val padAndSkin = Mat()
        Core.bitwise_and(padPixels, hsvPixels, padAndSkin)
        gcMask.setTo(Scalar(Imgproc.GC_FGD.toDouble()), padAndSkin)

        val bgd = Mat.zeros(1, 65, CvType.CV_64F)
        val fgd = Mat.zeros(1, 65, CvType.CV_64F)
        Imgproc.grabCut(bgr, gcMask, Rect(1, 1, bgr.cols() - 2, bgr.rows() - 2), bgd, fgd, 3, Imgproc.GC_INIT_WITH_MASK)

        val fg = Mat()
        val prFg = Mat()
        Core.compare(gcMask, Scalar(Imgproc.GC_FGD.toDouble()), fg, Core.CMP_EQ)
        Core.compare(gcMask, Scalar(Imgproc.GC_PR_FGD.toDouble()), prFg, Core.CMP_EQ)
        val grabMask = Mat()
        Core.bitwise_or(fg, prFg, grabMask)
        return grabMask
    }

    private fun filterComponentsByPadOverlap(mask: Mat, padMask: Mat, minAreaPx: Int): Mat {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)
        if (count <= 1) return mask
        val out = Mat.zeros(mask.size(), mask.type())
        for (label in 1 until count) {
            val area = stats.get(label, Imgproc.CC_STAT_AREA)[0].toInt()
            if (area < minAreaPx) continue
            var overlap = 0
            for (y in 0 until labels.rows()) {
                for (x in 0 until labels.cols()) {
                    if (labels.get(y, x)[0].toInt() == label && padMask.get(y, x)[0] > 0) {
                        overlap++
                    }
                }
            }
            val overlapRatio = overlap.toDouble() / area.toDouble()
            if (overlapRatio < 0.6) continue
            for (y in 0 until labels.rows()) {
                for (x in 0 until labels.cols()) {
                    if (labels.get(y, x)[0].toInt() == label) {
                        out.put(y, x, 255.0)
                    }
                }
            }
        }
        return out
    }

    private fun runGrabCut(bgr: Mat): Mat {
        val gcMask = Mat.zeros(bgr.size(), CvType.CV_8UC1)
        val rect = Rect(
            (bgr.cols() * 0.05).toInt(),
            (bgr.rows() * 0.05).toInt(),
            (bgr.cols() * 0.9).toInt(),
            (bgr.rows() * 0.9).toInt(),
        )
        val bgd = Mat.zeros(1, 65, CvType.CV_64F)
        val fgd = Mat.zeros(1, 65, CvType.CV_64F)
        Imgproc.grabCut(bgr, gcMask, rect, bgd, fgd, 5, Imgproc.GC_INIT_WITH_RECT)
        val fg = Mat()
        val prFg = Mat()
        Core.compare(gcMask, Scalar(Imgproc.GC_FGD.toDouble()), fg, Core.CMP_EQ)
        Core.compare(gcMask, Scalar(Imgproc.GC_PR_FGD.toDouble()), prFg, Core.CMP_EQ)
        val grabMask = Mat()
        Core.bitwise_or(fg, prFg, grabMask)
        val grabKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        Imgproc.morphologyEx(grabMask, grabMask, Imgproc.MORPH_CLOSE, grabKernel, Point(-1.0, -1.0), 2)
        return grabMask
    }

    private fun buildPadMask(
        landmarks: List<FingerLandmark>,
        width: Int,
        height: Int,
    ): Mat {
        val mask = Mat.zeros(height, width, CvType.CV_8UC1)
        val pairs = listOf(
            Triple(LandmarkType.INDEX_TIP, LandmarkType.INDEX_DIP, LandmarkType.INDEX_PIP),
            Triple(LandmarkType.MIDDLE_TIP, LandmarkType.MIDDLE_DIP, LandmarkType.MIDDLE_PIP),
            Triple(LandmarkType.RING_TIP, LandmarkType.RING_DIP, LandmarkType.RING_PIP),
            Triple(LandmarkType.PINKY_TIP, LandmarkType.PINKY_DIP, LandmarkType.PINKY_PIP),
        )
        pairs.forEach { (tipType, dipType, pipType) ->
            val tip = landmarks.firstOrNull { it.type == tipType } ?: return@forEach
            val dip = landmarks.firstOrNull { it.type == dipType } ?: return@forEach
            val tipPoint = Point((tip.x * width).toDouble(), (tip.y * height).toDouble())
            val dipPoint = Point((dip.x * width).toDouble(), (dip.y * height).toDouble())
            val dx = dipPoint.x - tipPoint.x
            val dy = dipPoint.y - tipPoint.y
            val length = hypot(dx, dy)
            if (length < 6.0) return@forEach
            val pip = landmarks.firstOrNull { it.type == pipType }
            val pipDist = if (pip != null) {
                val pipPoint = Point((pip.x * width).toDouble(), (pip.y * height).toDouble())
                hypot(pipPoint.x - dipPoint.x, pipPoint.y - dipPoint.y)
            } else {
                0.0
            }
            val padLength = length * 0.7
            val widthRef = estimateFingerWidth(landmarks, tipType, length)
            val padWidth = (widthRef * 0.8).coerceAtLeast(length * 0.45)
            val center = Point(
                tipPoint.x + dx * 0.3,
                tipPoint.y + dy * 0.3,
            )
            val angle = Math.toDegrees(kotlin.math.atan2(dy, dx))
            Imgproc.ellipse(
                mask,
                center,
                Size(padWidth * 0.5, padLength * 0.5),
                angle,
                0.0,
                360.0,
                Scalar(255.0),
                -1,
            )
        }
        val dilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        Imgproc.dilate(mask, mask, dilateKernel, Point(-1.0, -1.0), 1)
        return mask
    }

    private fun estimateFingerWidth(
        landmarks: List<FingerLandmark>,
        tipType: LandmarkType,
        fallbackLength: Double,
    ): Double {
        val width = when (tipType) {
            LandmarkType.INDEX_TIP -> {
                val a = landmarks.firstOrNull { it.type == LandmarkType.INDEX_MCP }
                val b = landmarks.firstOrNull { it.type == LandmarkType.MIDDLE_MCP }
                if (a != null && b != null) hypot(a.x - b.x, a.y - b.y) else null
            }
            LandmarkType.MIDDLE_TIP -> {
                val a = landmarks.firstOrNull { it.type == LandmarkType.MIDDLE_MCP }
                val b = landmarks.firstOrNull { it.type == LandmarkType.RING_MCP }
                if (a != null && b != null) hypot(a.x - b.x, a.y - b.y) else null
            }
            LandmarkType.RING_TIP -> {
                val a = landmarks.firstOrNull { it.type == LandmarkType.RING_MCP }
                val b = landmarks.firstOrNull { it.type == LandmarkType.PINKY_MCP }
                if (a != null && b != null) hypot(a.x - b.x, a.y - b.y) else null
            }
            LandmarkType.PINKY_TIP -> {
                val a = landmarks.firstOrNull { it.type == LandmarkType.RING_MCP }
                val b = landmarks.firstOrNull { it.type == LandmarkType.PINKY_MCP }
                if (a != null && b != null) hypot(a.x - b.x, a.y - b.y) else null
            }
            else -> null
        }
        val base = (width ?: (fallbackLength * 0.6)).toDouble()
        val scale = maxOf(widthScale(landmarks), 1.0)
        return base * scale
    }

    private fun widthScale(landmarks: List<FingerLandmark>): Double {
        val wrist = landmarks.firstOrNull { it.type == LandmarkType.WRIST } ?: return 1.0
        val indexMcp = landmarks.firstOrNull { it.type == LandmarkType.INDEX_MCP } ?: return 1.0
        val pinkyMcp = landmarks.firstOrNull { it.type == LandmarkType.PINKY_MCP } ?: return 1.0
        val palmWidth = hypot(indexMcp.x - pinkyMcp.x, indexMcp.y - pinkyMcp.y)
        return if (palmWidth > 0.0) (palmWidth / 0.2).coerceIn(0.8, 1.4) else 1.0
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
