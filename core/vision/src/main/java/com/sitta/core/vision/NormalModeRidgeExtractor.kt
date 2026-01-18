package com.sitta.core.vision

import android.graphics.Bitmap
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

data class RidgeExtractionResult(
    val ridgeBitmap: Bitmap,
)

class NormalModeRidgeExtractor {
    fun extractRidges(input: Bitmap, maskBitmap: Bitmap?): RidgeExtractionResult? {
        if (!OpenCvUtils.ensureLoadedOrFalse()) return null
        val src = OpenCvUtils.bitmapToMat(input)
        if (src.empty()) return null
        val bgr = Mat()
        Imgproc.cvtColor(src, bgr, Imgproc.COLOR_RGBA2BGR)
        val mask = if (maskBitmap != null) {
            val maskMat = OpenCvUtils.bitmapToMat(maskBitmap)
            val grayMask = Mat()
            Imgproc.cvtColor(maskMat, grayMask, Imgproc.COLOR_RGBA2GRAY)
            val binary = Mat()
            Imgproc.threshold(grayMask, binary, 1.0, 255.0, Imgproc.THRESH_BINARY)
            binary
        } else {
            buildNonBlackMask(bgr)
        }
        val finger = Mat()
        Core.bitwise_and(bgr, bgr, finger, mask)
        val gray = Mat()
        Imgproc.cvtColor(finger, gray, Imgproc.COLOR_BGR2GRAY)

        val clahe = Imgproc.createCLAHE(2.2, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(gray, enhanced)

        val blur = Mat()
        Imgproc.GaussianBlur(enhanced, blur, Size(5.0, 5.0), 0.0)

        val sharp = Mat()
        Core.addWeighted(enhanced, 1.6, blur, -(1.6 - 1.0), 0.0, sharp)

        val ridges = Mat()
        Imgproc.adaptiveThreshold(
            sharp,
            ridges,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            17,
            2.0,
        )
        Core.bitwise_and(ridges, ridges, ridges, mask)
        Imgproc.medianBlur(ridges, ridges, 3)
        Core.bitwise_and(ridges, ridges, ridges, mask)

        val ridgeBitmap = OpenCvUtils.matToBitmap(ridges, true)
        return RidgeExtractionResult(ridgeBitmap)
    }

    private fun buildNonBlackMask(bgr: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val mask = Mat()
        Imgproc.threshold(gray, mask, 1.0, 255.0, Imgproc.THRESH_BINARY)
        return mask
    }
}
