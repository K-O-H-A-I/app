package com.sitta.core.vision

import android.graphics.Bitmap
import com.sitta.core.domain.EnhancementPipeline
import com.sitta.core.domain.EnhancementResult
import com.sitta.core.domain.EnhancementStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class OpenCvEnhancementPipeline : EnhancementPipeline {
    override suspend fun enhance(bitmap: Bitmap, sharpenStrength: Float): EnhancementResult {
        return withContext(Dispatchers.Default) {
            val steps = mutableListOf<EnhancementStep>()
            val input = downscale(bitmap, 640)
            val srcMat = Mat()
            Utils.bitmapToMat(input, srcMat)

            val t0 = System.nanoTime()
            val gray = Mat()
            Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_RGBA2GRAY)
            steps.add(EnhancementStep("Grayscale", elapsedMs(t0)))

            val t1 = System.nanoTime()
            Core.normalize(gray, gray, 0.0, 255.0, Core.NORM_MINMAX)
            val clahe = Imgproc.createCLAHE(2.2, Size(8.0, 8.0))
            val claheMat = Mat()
            clahe.apply(gray, claheMat)
            steps.add(EnhancementStep("Contrast Boost", elapsedMs(t1)))

            val t2 = System.nanoTime()
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
            Imgproc.filter2D(claheMat, sharpened, -1, kernel)
            steps.add(EnhancementStep("Detail Sharpen", elapsedMs(t2)))

            val outBitmap = Bitmap.createBitmap(sharpened.cols(), sharpened.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(sharpened, outBitmap)
            EnhancementResult(outBitmap, steps)
        }
    }

    private fun elapsedMs(startNs: Long): Long {
        return ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1)
    }

    private fun downscale(bitmap: Bitmap, maxSide: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val maxDim = maxOf(width, height)
        if (maxDim <= maxSide) return bitmap
        val scale = maxSide.toFloat() / maxDim.toFloat()
        val newWidth = (width * scale).toInt().coerceAtLeast(1)
        val newHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
