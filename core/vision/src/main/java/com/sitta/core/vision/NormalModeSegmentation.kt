package com.sitta.core.vision

import android.graphics.Bitmap
import android.graphics.Rect
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect as CvRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

data class SegmentationResult(
    val mask: Bitmap,
    val segmented: Bitmap,
    val roi: Bitmap,
    val roiRect: Rect,
)

class NormalModeSegmentation {
    private val hsvLower = Scalar(0.0, 40.0, 80.0)
    private val hsvUpper = Scalar(20.0, 160.0, 255.0)

    fun segment(input: Bitmap): SegmentationResult? {
        if (!OpenCvUtils.ensureLoadedOrFalse()) return null
        val src = OpenCvUtils.bitmapToMat(input)
        if (src.empty()) return null
        val bgr = Mat()
        when (src.channels()) {
            4 -> Imgproc.cvtColor(src, bgr, Imgproc.COLOR_RGBA2BGR)
            3 -> src.copyTo(bgr)
            1 -> Imgproc.cvtColor(src, bgr, Imgproc.COLOR_GRAY2BGR)
            else -> return null
        }

        val hsvMask = hsvSkinSegmentation(bgr)
        var grabcutMask = grabcutSegmentation(bgr, hsvMask)
        if (grabcutMask.size() != hsvMask.size()) {
            val resized = Mat()
            Imgproc.resize(grabcutMask, resized, hsvMask.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
            grabcutMask = resized
        }
        if (grabcutMask.type() != CvType.CV_8UC1) {
            val converted = Mat()
            grabcutMask.convertTo(converted, CvType.CV_8UC1)
            grabcutMask = converted
        }
        val finalMask = refineSegmentationMask(hsvMask, grabcutMask)

        val segmented = Mat()
        Core.bitwise_and(bgr, bgr, segmented, finalMask)

        val maskBitmap = OpenCvUtils.matToBitmap(finalMask, true)
        val segmentedBitmap = OpenCvUtils.matToBitmap(segmented, false)

        val roiRect = computeRoiFromMask(finalMask, input.width, input.height)
        val roiBitmap = Bitmap.createBitmap(
            segmentedBitmap,
            roiRect.left,
            roiRect.top,
            roiRect.width(),
            roiRect.height(),
        )
        return SegmentationResult(
            mask = maskBitmap,
            segmented = segmentedBitmap,
            roi = roiBitmap,
            roiRect = roiRect,
        )
    }

    private fun hsvSkinSegmentation(bgr: Mat): Mat {
        val hsv = Mat()
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        val mask = Mat()
        Core.inRange(hsv, hsvLower, hsvUpper, mask)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel, org.opencv.core.Point(-1.0, -1.0), 2)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel, org.opencv.core.Point(-1.0, -1.0), 2)
        return mask
    }

    private fun grabcutSegmentation(bgr: Mat, hsvMask: Mat?, downscaleMaxSide: Int = 900): Mat {
        val h = bgr.rows()
        val w = bgr.cols()
        val maxSide = max(h, w)
        var scale = 1.0
        var imgSmall = bgr
        var hsvMaskSmall = hsvMask
        if (maxSide > downscaleMaxSide) {
            scale = downscaleMaxSide.toDouble() / maxSide.toDouble()
            imgSmall = Mat()
            Imgproc.resize(bgr, imgSmall, Size(w * scale, h * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            hsvMaskSmall = hsvMask?.let {
                val resized = Mat()
                Imgproc.resize(it, resized, Size(w * scale, h * scale), 0.0, 0.0, Imgproc.INTER_NEAREST)
                resized
            }
        }
        val hs = imgSmall.rows()
        val ws = imgSmall.cols()
        val mask = Mat.zeros(hs, ws, CvType.CV_8UC1)
        val rect = computeAdaptiveRect(hsvMaskSmall, ws, hs, 20)
            ?: return hsvMask ?: Mat.ones(h, w, CvType.CV_8UC1).apply { Core.multiply(this, Scalar(255.0), this) }
        val bgd = Mat.zeros(1, 65, CvType.CV_64F)
        val fgd = Mat.zeros(1, 65, CvType.CV_64F)
        return try {
            Imgproc.grabCut(imgSmall, mask, rect, bgd, fgd, 7, Imgproc.GC_INIT_WITH_RECT)
            val grabcutSmall = Mat()
            Core.compare(mask, Scalar(Imgproc.GC_FGD.toDouble()), grabcutSmall, Core.CMP_EQ)
            val prFg = Mat()
            Core.compare(mask, Scalar(Imgproc.GC_PR_FGD.toDouble()), prFg, Core.CMP_EQ)
            Core.bitwise_or(grabcutSmall, prFg, grabcutSmall)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
            Imgproc.morphologyEx(grabcutSmall, grabcutSmall, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
            if (scale != 1.0) {
                val up = Mat()
                Imgproc.resize(grabcutSmall, up, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
                up
            } else {
                grabcutSmall
            }
        } catch (_: Throwable) {
            hsvMask ?: Mat.ones(h, w, CvType.CV_8UC1).apply { Core.multiply(this, Scalar(255.0), this) }
        }
    }

    private fun refineSegmentationMask(hsvMask: Mat, grabcutMask: Mat): Mat {
        val combined = Mat()
        Core.bitwise_and(hsvMask, grabcutMask, combined)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(combined, combined, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 1)

        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val numLabels = Imgproc.connectedComponentsWithStats(combined, labels, stats, centroids)
        if (numLabels <= 1) {
            Imgproc.medianBlur(combined, combined, 5)
            return combined
        }
        val h = combined.rows()
        val w = combined.cols()
        val minArea = (h * w) * 0.01
        val maxArea = (h * w) * 0.9
        val finalMask = Mat.zeros(combined.size(), CvType.CV_8UC1)
        for (i in 1 until numLabels) {
            val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
            if (area < minArea || area > maxArea) continue
            val compW = stats.get(i, Imgproc.CC_STAT_WIDTH)[0]
            val compH = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0]
            val aspect = compH / (compW + 1e-6)
            if (aspect > 0.8) {
                val mask = Mat()
                Core.compare(labels, Scalar(i.toDouble()), mask, Core.CMP_EQ)
                Core.bitwise_or(finalMask, mask, finalMask)
            }
        }
        if (Core.countNonZero(finalMask) == 0) {
            var largestIdx = 1
            var largestArea = 0.0
            for (i in 1 until numLabels) {
                val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
                if (area > largestArea) {
                    largestArea = area
                    largestIdx = i
                }
            }
            Core.compare(labels, Scalar(largestIdx.toDouble()), finalMask, Core.CMP_EQ)
        }
        Imgproc.medianBlur(finalMask, finalMask, 5)
        return finalMask
    }

    private fun computeAdaptiveRect(hsvMask: Mat?, width: Int, height: Int, padPercent: Int): CvRect? {
        if (hsvMask == null || Core.countNonZero(hsvMask) == 0) {
            val rect = CvRect(
                (width * 0.02).toInt(),
                (height * 0.02).toInt(),
                (width * 0.96).toInt(),
                (height * 0.96).toInt(),
            )
            return validateGrabcutRect(rect, width, height)
        }
        val points = MatOfPoint()
        Core.findNonZero(hsvMask, points)
        if (points.empty()) {
            val rect = CvRect(
                (width * 0.02).toInt(),
                (height * 0.02).toInt(),
                (width * 0.96).toInt(),
                (height * 0.96).toInt(),
            )
            return validateGrabcutRect(rect, width, height)
        }
        val rect = Imgproc.boundingRect(points)
        val padX = (rect.width * padPercent / 100.0).toInt()
        val padY = (rect.height * padPercent / 100.0).toInt()
        val expanded = CvRect(
            rect.x - padX,
            rect.y - padY,
            rect.width + padX * 2,
            rect.height + padY * 2,
        )
        return validateGrabcutRect(expanded, width, height)
            ?: validateGrabcutRect(
                CvRect(
                    (width * 0.02).toInt(),
                    (height * 0.02).toInt(),
                    (width * 0.96).toInt(),
                    (height * 0.96).toInt(),
                ),
                width,
                height,
            )
    }

    private fun validateGrabcutRect(rect: CvRect, imgWidth: Int, imgHeight: Int): CvRect? {
        var x = rect.x.coerceAtLeast(0)
        var y = rect.y.coerceAtLeast(0)
        if (x >= imgWidth || y >= imgHeight) return null
        var w = rect.width.coerceAtMost(imgWidth - x)
        var h = rect.height.coerceAtMost(imgHeight - y)
        val minSize = 50
        if (w < minSize || h < minSize) return null
        val minArea = (imgWidth * imgHeight) * 0.05
        if (w * h < minArea) return null
        return CvRect(x, y, w, h)
    }

    private fun computeRoiFromMask(mask: Mat, width: Int, height: Int): Rect {
        val points = MatOfPoint()
        Core.findNonZero(mask, points)
        if (points.empty()) {
            return Rect(0, 0, width, height)
        }
        val rect = Imgproc.boundingRect(points)
        val padX = (rect.width * 0.05).toInt()
        val padY = (rect.height * 0.05).toInt()
        val left = (rect.x - padX).coerceAtLeast(0)
        val top = (rect.y - padY).coerceAtLeast(0)
        val right = (rect.x + rect.width + padX).coerceAtMost(width)
        val bottom = (rect.y + rect.height + padY).coerceAtMost(height)
        return Rect(left, top, right, bottom)
    }
}
