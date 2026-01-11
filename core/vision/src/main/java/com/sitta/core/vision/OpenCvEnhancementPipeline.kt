package com.sitta.core.vision

import android.graphics.Bitmap
import com.sitta.core.domain.EnhancementPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class OpenCvEnhancementPipeline : EnhancementPipeline {
    override suspend fun enhance(bitmap: Bitmap, sharpenStrength: Float): Bitmap {
        return withContext(Dispatchers.Default) {
            val srcMat = Mat()
            Utils.bitmapToMat(bitmap, srcMat)

            val gray = Mat()
            Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_RGBA2GRAY)

            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            val claheMat = Mat()
            clahe.apply(gray, claheMat)

            val denoised = Mat()
            Imgproc.bilateralFilter(claheMat, denoised, 9, 75.0, 75.0)

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

            val outBitmap = Bitmap.createBitmap(sharpened.cols(), sharpened.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(sharpened, outBitmap)
            outBitmap
        }
    }
}
