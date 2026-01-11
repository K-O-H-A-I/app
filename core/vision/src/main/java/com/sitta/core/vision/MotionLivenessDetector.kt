package com.sitta.core.vision

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.sitta.core.common.AppConfig
import com.sitta.core.domain.LivenessDetector
import com.sitta.core.domain.LivenessResult
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

class MotionLivenessDetector(private var config: AppConfig) : LivenessDetector {
    fun updateConfig(newConfig: AppConfig) {
        config = newConfig
    }

    override fun evaluate(frames: List<Bitmap>, roi: Rect): LivenessResult {
        val centroids = frames.map { bitmap ->
            val mat = OpenCvUtils.bitmapToMat(bitmap)
            val roiMat = OpenCvUtils.cropMat(mat, roi)
            computeCentroid(roiMat)
        }
        val distances = centroids.zipWithNext { a, b ->
            hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
        }
        val variance = if (distances.isEmpty()) 0.0 else {
            val mean = distances.average()
            distances.map { (it - mean) * (it - mean) }.average()
        }

        val decision = when {
            variance < config.livenessVarianceMin -> "FAIL"
            variance > config.livenessVarianceMax -> "FAIL"
            else -> "PASS"
        }

        return LivenessResult(
            decision = decision,
            score = variance,
            variance = variance,
        )
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
