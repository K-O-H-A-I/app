package com.sitta.core.vision

import android.graphics.Bitmap
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class FingerRidgeExtractor {
    private val gaborKernels: List<Mat> by lazy { buildGaborKernels() }

    fun extractRidge(bitmap: Bitmap): Bitmap {
        val src = OpenCvUtils.bitmapToMat(bitmap)
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        val mask = Mat()
        Imgproc.threshold(gray, mask, 8.0, 255.0, Imgproc.THRESH_BINARY)

        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(gray, enhanced)

        val blur = Mat()
        Imgproc.GaussianBlur(enhanced, blur, Size(5.0, 5.0), 0.0)
        val sharp = Mat()
        Core.addWeighted(enhanced, 1.5, blur, -0.5, 0.0, sharp)
        val maskedSharp = Mat()
        Core.bitwise_and(sharp, sharp, maskedSharp, mask)

        val ridgeEnhanced = applyGaborBank(maskedSharp)
        val ridge = Mat()
        Imgproc.adaptiveThreshold(
            ridgeEnhanced,
            ridge,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            15,
            -3.0,
        )
        Core.bitwise_and(ridge, ridge, ridge, mask)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.morphologyEx(ridge, ridge, Imgproc.MORPH_OPEN, kernel)

        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        org.opencv.android.Utils.matToBitmap(ridge, out)
        return out
    }

    private fun applyGaborBank(input: Mat): Mat {
        val floatInput = Mat()
        input.convertTo(floatInput, CvType.CV_32F)
        val response = Mat.zeros(input.size(), CvType.CV_32F)
        val tmp = Mat()
        for (kernel in gaborKernels) {
            Imgproc.filter2D(floatInput, tmp, CvType.CV_32F, kernel)
            Core.max(response, tmp, response)
        }
        val normalized = Mat()
        Core.normalize(response, normalized, 0.0, 255.0, Core.NORM_MINMAX)
        normalized.convertTo(normalized, CvType.CV_8U)
        return normalized
    }

    private fun buildGaborKernels(): List<Mat> {
        val kernels = mutableListOf<Mat>()
        val ksize = 21
        val sigma = 3.0
        val lambda = 10.0
        val gamma = 0.5
        val psi = 0.0
        val orientations = 8
        for (i in 0 until orientations) {
            val theta = i * Math.PI / orientations
            kernels.add(Imgproc.getGaborKernel(Size(ksize.toDouble(), ksize.toDouble()), sigma, theta, lambda, gamma, psi, CvType.CV_32F))
        }
        return kernels
    }
}
