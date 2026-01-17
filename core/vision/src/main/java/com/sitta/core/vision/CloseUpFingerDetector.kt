package com.sitta.core.vision

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

data class CloseUpFingerResult(
    val isFingerCloseUp: Boolean,
    val confidence: Double,
    val skinRatio: Double,
    val shapeValid: Boolean,
    val areaRatio: Double,
    val solidity: Double,
    val centroidX: Double,
    val centroidY: Double,
    val ridgeScore: Double,
    val edgeScore: Double,
    val skinDetected: Boolean,
    val ridgesFound: Boolean,
)

class CloseUpFingerDetector(
    private val maxSampleCount: Int = 10_000,
) {
    private val skinLowerHsv = Scalar(0.0, 30.0, 60.0)
    private val skinUpperHsv = Scalar(25.0, 255.0, 255.0)
    private val skinLowerYcrcb = Scalar(0.0, 135.0, 85.0)
    private val skinUpperYcrcb = Scalar(255.0, 180.0, 135.0)

    private val minSolidity = 0.7
    private val minAreaRatio = 0.1
    private val maxAreaRatio = 0.9

    private val gaborFrequencies = doubleArrayOf(0.1, 0.15, 0.2)
    private val gaborOrientations = 8
    private val gaborKernels: List<Mat> = buildGaborKernels()

    private fun buildGaborKernels(): List<Mat> {
        val kernels = ArrayList<Mat>()
        for (freq in gaborFrequencies) {
            val lambda = 1.0 / freq
            for (i in 0 until gaborOrientations) {
                val theta = i * Math.PI / gaborOrientations
                val kernel = Imgproc.getGaborKernel(
                    Size(21.0, 21.0),
                    4.0,
                    theta,
                    lambda,
                    0.5,
                    0.0,
                    CvType.CV_32F,
                )
                kernels.add(kernel)
            }
        }
        return kernels
    }

    fun detect(bitmap: Bitmap): CloseUpFingerResult {
        return runCatching {
            val bgr = Mat()
            Utils.bitmapToMat(bitmap, bgr)
            if (bgr.empty()) return@runCatching emptyResult()

            val hsv = Mat()
            val ycrcb = Mat()
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            Imgproc.cvtColor(bgr, ycrcb, Imgproc.COLOR_BGR2YCrCb)

            val gray = Mat()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)

            val (skinMask, skinRatio) = detectSkin(hsv, ycrcb)
            val skinDetected = skinRatio > 0.15

            val (shapeValid, areaRatio, solidity, centroidX, centroidY) = if (skinDetected) {
                analyzeShape(skinMask, bgr.size())
            } else {
                ShapeResult(false, 0.0, 0.0, -1.0, -1.0)
            }

            val ridgeScore = detectRidges(gray, skinMask)
            val ridgesFound = ridgeScore > 0.3
            val edgeScore = analyzeEdges(gray, skinMask)

            val scores = doubleArrayOf(
                if (skinDetected) 1.0 else 0.0,
                if (shapeValid) 1.0 else if (skinRatio > 0.3) 0.5 else 0.0,
                ridgeScore,
                edgeScore,
            )
            val confidence = scores.average()
            val isFingerCloseUp = confidence > 0.5

            CloseUpFingerResult(
                isFingerCloseUp = isFingerCloseUp,
                confidence = confidence,
                skinRatio = skinRatio,
                shapeValid = shapeValid,
                areaRatio = areaRatio,
                solidity = solidity,
                centroidX = centroidX,
                centroidY = centroidY,
                ridgeScore = ridgeScore,
                edgeScore = edgeScore,
                skinDetected = skinDetected,
                ridgesFound = ridgesFound,
            )
        }.getOrElse {
            emptyResult()
        }
    }

    private fun detectSkin(hsv: Mat, ycrcb: Mat): Pair<Mat, Double> {
        val maskHsv = Mat()
        val maskYcrcb = Mat()
        Core.inRange(hsv, skinLowerHsv, skinUpperHsv, maskHsv)
        Core.inRange(ycrcb, skinLowerYcrcb, skinUpperYcrcb, maskYcrcb)
        val combined = Mat()
        Core.bitwise_or(maskHsv, maskYcrcb, combined)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(combined, combined, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.morphologyEx(combined, combined, Imgproc.MORPH_OPEN, kernel)

        val totalPixels = combined.rows() * combined.cols()
        val skinPixels = Core.countNonZero(combined)
        val ratio = if (totalPixels > 0) skinPixels.toDouble() / totalPixels.toDouble() else 0.0
        return combined to ratio
    }

    private fun analyzeShape(mask: Mat, size: org.opencv.core.Size): ShapeResult {
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        if (contours.isEmpty()) {
            return ShapeResult(false, 0.0, 0.0, -1.0, -1.0)
        }
        val largest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return ShapeResult(false, 0.0, 0.0, -1.0, -1.0)
        val area = Imgproc.contourArea(largest)
        val imageArea = size.width * size.height
        val areaRatio = if (imageArea > 0) area / imageArea else 0.0

        val hullIdx = MatOfInt()
        Imgproc.convexHull(largest, hullIdx)
        val hull = buildHullPoints(largest, hullIdx)
        val hullArea = if (hull.empty()) 0.0 else Imgproc.contourArea(hull)
        val solidity = if (hullArea > 0) area / hullArea else 0.0

        val moments = Imgproc.moments(largest)
        val centroidX = if (moments.m00 != 0.0) moments.m10 / moments.m00 else -1.0
        val centroidY = if (moments.m00 != 0.0) moments.m01 / moments.m00 else -1.0

        val shapeValid = (areaRatio > minAreaRatio && areaRatio < maxAreaRatio && solidity > minSolidity)
        return ShapeResult(shapeValid, areaRatio, solidity, centroidX, centroidY)
    }

    private fun buildHullPoints(contour: MatOfPoint, hullIdx: MatOfInt): MatOfPoint {
        val contourPoints = contour.toArray()
        val indices = hullIdx.toArray()
        val hullPoints = ArrayList<Point>(indices.size)
        for (idx in indices) {
            if (idx in contourPoints.indices) {
                hullPoints.add(contourPoints[idx])
            }
        }
        val hull = MatOfPoint()
        if (hullPoints.isNotEmpty()) {
            hull.fromList(hullPoints)
        }
        return hull
    }

    private fun detectRidges(gray: Mat, mask: Mat): Double {
        val grayNorm = Mat()
        Core.normalize(gray, grayNorm, 0.0, 255.0, Core.NORM_MINMAX)

        val combined = Mat.zeros(grayNorm.size(), CvType.CV_32F)
        val response = Mat()
        for (kernel in gaborKernels) {
            Imgproc.filter2D(grayNorm, response, CvType.CV_32F, kernel)
            Core.absdiff(response, Scalar.all(0.0), response)
            Core.max(combined, response, combined)
        }

        if (!mask.empty()) {
            val maskFloat = Mat()
            mask.convertTo(maskFloat, CvType.CV_32F, 1.0 / 255.0)
            Core.multiply(combined, maskFloat, combined)
        }

        val threshold = percentileMasked(combined, mask, 90.0)
        val ridgePixels = countAbove(combined, threshold)
        val validPixels = if (mask.empty()) {
            max(1.0, (combined.rows() * combined.cols()).toDouble())
        } else {
            max(1.0, Core.countNonZero(mask).toDouble())
        }
        val ridgeScore = min((ridgePixels / validPixels) * 10.0, 1.0)
        return ridgeScore
    }

    private fun analyzeEdges(gray: Mat, mask: Mat): Double {
        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        if (!mask.empty()) {
            Core.bitwise_and(edges, mask, edges)
        }
        val edgePixels = Core.countNonZero(edges)
        val validPixels = if (mask.empty()) {
            max(1.0, (edges.rows() * edges.cols()).toDouble())
        } else {
            max(1.0, Core.countNonZero(mask).toDouble())
        }
        val edgeDensity = edgePixels.toDouble() / validPixels
        return when {
            edgeDensity > 0.05 && edgeDensity < 0.3 -> 1.0
            edgeDensity > 0.02 && edgeDensity < 0.4 -> 0.7
            else -> 0.3
        }
    }

    private fun percentileMasked(mat: Mat, mask: Mat, percentile: Double): Double {
        val rows = mat.rows()
        val cols = mat.cols()
        val samples = DoubleArray(maxSampleCount)
        var count = 0
        val step = max(1, (rows * cols) / maxSampleCount)
        var idx = 0
        val rowValues = DoubleArray(cols)
        val maskRow = ByteArray(cols)
        for (y in 0 until rows) {
            mat.get(y, 0, rowValues)
            if (!mask.empty()) {
                mask.get(y, 0, maskRow)
            }
            for (x in 0 until cols) {
                if (mask.empty() || (maskRow[x].toInt() and 0xFF) > 0) {
                    if (idx % step == 0 && count < maxSampleCount) {
                        samples[count++] = rowValues[x]
                    }
                    idx++
                }
            }
        }
        if (count == 0) return 0.0
        samples.sort(0, count)
        val rank = ((percentile / 100.0) * (count - 1)).toInt()
        return samples[rank]
    }

    private fun countAbove(mat: Mat, threshold: Double): Double {
        val rows = mat.rows()
        val cols = mat.cols()
        var count = 0.0
        val rowValues = DoubleArray(cols)
        for (y in 0 until rows) {
            mat.get(y, 0, rowValues)
            for (x in 0 until cols) {
                if (rowValues[x] > threshold) count++
            }
        }
        return count
    }

    private fun emptyResult(): CloseUpFingerResult {
        return CloseUpFingerResult(
            isFingerCloseUp = false,
            confidence = 0.0,
            skinRatio = 0.0,
            shapeValid = false,
            areaRatio = 0.0,
            solidity = 0.0,
            centroidX = -1.0,
            centroidY = -1.0,
            ridgeScore = 0.0,
            edgeScore = 0.0,
            skinDetected = false,
            ridgesFound = false,
        )
    }

    private data class ShapeResult(
        val valid: Boolean,
        val areaRatio: Double,
        val solidity: Double,
        val centroidX: Double,
        val centroidY: Double,
    )
}
