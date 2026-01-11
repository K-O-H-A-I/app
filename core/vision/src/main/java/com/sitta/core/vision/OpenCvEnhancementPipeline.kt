package com.sitta.core.vision

import android.graphics.Bitmap
import com.sitta.core.domain.EnhancementPipeline
import com.sitta.core.domain.EnhancementResult
import com.sitta.core.domain.EnhancementStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class OpenCvEnhancementPipeline : EnhancementPipeline {
    override suspend fun enhance(bitmap: Bitmap, sharpenStrength: Float): EnhancementResult {
        return withContext(Dispatchers.Default) {
            val steps = mutableListOf<EnhancementStep>()
            val srcMat = Mat()
            Utils.bitmapToMat(bitmap, srcMat)

            val t0 = System.nanoTime()
            val gray = Mat()
            Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_RGBA2GRAY)
            steps.add(EnhancementStep("Grayscale", elapsedMs(t0)))

            val t1 = System.nanoTime()
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            val claheMat = Mat()
            clahe.apply(gray, claheMat)
            steps.add(EnhancementStep("Contrast Boost", elapsedMs(t1)))

            val t2 = System.nanoTime()
            val denoised = Mat()
            Imgproc.bilateralFilter(claheMat, denoised, 9, 75.0, 75.0)
            steps.add(EnhancementStep("Noise Reduction", elapsedMs(t2)))

            val t3 = System.nanoTime()
            val sharpened = Mat()
            val strength = sharpenStrength.coerceIn(0f, 2f)
            val kernel = Mat(3, 3, CvType.CV_32F)
            val center = 1f + 4f * strength
            val kernelData = floatArrayOf(
                0f, -strength, 0f,
                -strength, center, -strength,
                0f, -strength, 0f,
            )
            kernel.put(0, 0, kernelData)
            Imgproc.filter2D(denoised, sharpened, -1, kernel)
            steps.add(EnhancementStep("Detail Sharpen", elapsedMs(t3)))

            val outBitmap = Bitmap.createBitmap(sharpened.cols(), sharpened.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(sharpened, outBitmap)
            EnhancementResult(outBitmap, steps)
        }
    }

    private fun elapsedMs(startNs: Long): Long {
        return ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1)
    }
}
