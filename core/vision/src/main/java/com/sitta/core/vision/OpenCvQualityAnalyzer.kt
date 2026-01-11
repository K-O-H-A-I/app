package com.sitta.core.vision

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.sitta.core.common.AppConfig
import com.sitta.core.common.QualityEvaluator
import com.sitta.core.common.QualityMetrics
import com.sitta.core.domain.QualityAnalyzer
import com.sitta.core.domain.QualityResult
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

class OpenCvQualityAnalyzer(private var config: AppConfig) : QualityAnalyzer {
    private var previousCentroid: PointF? = null

    fun updateConfig(newConfig: AppConfig) {
        config = newConfig
    }

    override fun analyze(bitmap: Bitmap, roi: Rect, timestampMillis: Long): QualityResult {
        val mat = OpenCvUtils.bitmapToMat(bitmap)
        val roiMat = OpenCvUtils.cropMat(mat, roi)

        val gray = Mat()
        Imgproc.cvtColor(roiMat, gray, Imgproc.COLOR_RGBA2GRAY)

        val blurScore = computeBlur(gray)
        val illuminationMean = Core.mean(gray).`val`[0]
        val coverage = computeCoverage(roiMat)
        val stability = computeStability(roiMat)

        val metrics = QualityMetrics(
            blurScore = blurScore,
            illuminationMean = illuminationMean,
            coverageRatio = coverage,
            stabilityVariance = stability,
        )
        val evaluation = QualityEvaluator.evaluate(metrics, config)

        return QualityResult(
            metrics = metrics,
            passes = evaluation.passes,
            score0To100 = evaluation.score0To100,
            pass = evaluation.pass,
            topReason = evaluation.topReason,
            timestampMillis = timestampMillis,
        )
    }

    private fun computeBlur(gray: Mat): Double {
        val laplacian = Mat()
        Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
        val mean = MatOfDouble()
        val stddev = MatOfDouble()
        Core.meanStdDev(laplacian, mean, stddev)
        val sigma = stddev.toArray().firstOrNull() ?: 0.0
        return sigma * sigma
    }

    private fun computeCoverage(roiMat: Mat): Double {
        val rgb = Mat()
        Imgproc.cvtColor(roiMat, rgb, Imgproc.COLOR_RGBA2RGB)
        val ycrcb = Mat()
        Imgproc.cvtColor(rgb, ycrcb, Imgproc.COLOR_RGB2YCrCb)
        val lower = Scalar(0.0, 133.0, 77.0)
        val upper = Scalar(255.0, 173.0, 127.0)
        val mask = Mat()
        Core.inRange(ycrcb, lower, upper, mask)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
        val nonZero = Core.countNonZero(mask).toDouble()
        val total = mask.rows().toDouble() * mask.cols().toDouble()
        return if (total > 0) nonZero / total else 0.0
    }

    private fun computeStability(roiMat: Mat): Double {
        val centroid = computeCentroid(roiMat)
        val prev = previousCentroid
        previousCentroid = centroid
        return if (prev == null) {
            config.stabilityMax + 1
        } else {
            hypot((centroid.x - prev.x).toDouble(), (centroid.y - prev.y).toDouble())
        }
    }

    private fun computeCentroid(roiMat: Mat): PointF {
        val gray = Mat()
        Imgproc.cvtColor(roiMat, gray, Imgproc.COLOR_RGBA2GRAY)
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        val thresh = Mat()
        Imgproc.threshold(blurred, thresh, 0.0, 255.0, Imgproc.THRESH_OTSU)
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(thresh, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        if (contours.isEmpty()) {
            return PointF(roiMat.cols() / 2f, roiMat.rows() / 2f)
        }
        val largest = contours.maxByOrNull { Imgproc.contourArea(it) }
        val moments = Imgproc.moments(largest)
        val cx = if (moments.m00 != 0.0) moments.m10 / moments.m00 else roiMat.cols() / 2.0
        val cy = if (moments.m00 != 0.0) moments.m01 / moments.m00 else roiMat.rows() / 2.0
        return PointF(cx.toFloat(), cy.toFloat())
    }

}
