package com.sitta.core.vision

import android.graphics.Bitmap
import com.sitta.core.domain.EnhancementPipeline
import com.sitta.core.domain.EnhancementResult
import com.sitta.core.domain.EnhancementStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class NormalModeEnhancementPipeline : EnhancementPipeline {
    override suspend fun enhance(bitmap: Bitmap, sharpenStrength: Float): EnhancementResult {
        return withContext(Dispatchers.Default) {
            if (!OpenCvUtils.ensureLoadedOrFalse()) {
                return@withContext EnhancementResult(bitmap, emptyList())
            }
            val steps = mutableListOf<EnhancementStep>()
            val src = OpenCvUtils.bitmapToMat(bitmap)
            if (src.empty()) {
                return@withContext EnhancementResult(bitmap, emptyList())
            }
            val t0 = System.nanoTime()
            val bgr = Mat()
            Imgproc.cvtColor(src, bgr, Imgproc.COLOR_RGBA2BGR)
            val lab = Mat()
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
            val channels = ArrayList<Mat>(3)
            Core.split(lab, channels)
            val l = channels[0]
            steps.add(EnhancementStep("LAB L", elapsedMs(t0)))

            val mask = buildNonBlackMask(bgr)
            val t1 = System.nanoTime()
            val lf = Mat()
            l.convertTo(lf, CvType.CV_32F)
            val meanMat = MatOfDouble()
            val stdMat = MatOfDouble()
            Core.meanStdDev(lf, meanMat, stdMat, mask)
            val mean = meanMat.toArray().getOrNull(0) ?: 0.0
            val std = (stdMat.toArray().getOrNull(0) ?: 0.0) + 1e-6
            val normalized = Mat()
            Core.subtract(lf, Scalar(doubleArrayOf(mean)), normalized)
            Core.divide(normalized, Scalar(doubleArrayOf(std)), normalized)
            Core.multiply(normalized, Scalar(50.0), normalized)
            Core.add(normalized, Scalar(128.0), normalized)
            Core.min(normalized, Scalar(255.0), normalized)
            Core.max(normalized, Scalar(0.0), normalized)
            val lNorm = Mat()
            normalized.convertTo(lNorm, CvType.CV_8U)
            val lMerged = l.clone()
            lNorm.copyTo(lMerged, mask)
            steps.add(EnhancementStep("Tone Normalize", elapsedMs(t1)))

            val t2 = System.nanoTime()
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            val lClahe = Mat()
            clahe.apply(lMerged, lClahe)
            val lFinal = lMerged.clone()
            lClahe.copyTo(lFinal, mask)
            steps.add(EnhancementStep("CLAHE", elapsedMs(t2)))

            val maskedGray = Mat.zeros(lFinal.size(), lFinal.type())
            lFinal.copyTo(maskedGray, mask)
            val outRgba = Mat()
            Imgproc.cvtColor(maskedGray, outRgba, Imgproc.COLOR_GRAY2RGBA)
            val outBitmap = OpenCvUtils.matToBitmap(outRgba, false)
            EnhancementResult(outBitmap, steps)
        }
    }

    private fun buildNonBlackMask(bgr: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val mask = Mat()
        Imgproc.threshold(gray, mask, 1.0, 255.0, Imgproc.THRESH_BINARY)
        return mask
    }

    private fun elapsedMs(startNs: Long): Long {
        return ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1)
    }
}
