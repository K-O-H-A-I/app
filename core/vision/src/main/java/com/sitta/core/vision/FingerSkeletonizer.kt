package com.sitta.core.vision

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class FingerSkeletonizer {
    fun skeletonize(bitmap: Bitmap): Bitmap {
        val src = OpenCvUtils.bitmapToMat(bitmap)
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        val binary = Mat()
        Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

        val skeleton = morphologicalSkeleton(binary)

        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val color = Mat()
        Imgproc.cvtColor(skeleton, color, Imgproc.COLOR_GRAY2RGBA)
        Utils.matToBitmap(color, out)
        return out
    }

    private fun morphologicalSkeleton(binary: Mat): Mat {
        val working = Mat()
        binary.copyTo(working)
        val skeleton = Mat.zeros(working.size(), working.type())
        val temp = Mat()
        val eroded = Mat()
        val element = Imgproc.getStructuringElement(Imgproc.MORPH_CROSS, Size(3.0, 3.0))

        var done: Boolean
        do {
            Imgproc.erode(working, eroded, element)
            Imgproc.dilate(eroded, temp, element)
            Core.subtract(working, temp, temp)
            Core.bitwise_or(skeleton, temp, skeleton)
            eroded.copyTo(working)
            done = Core.countNonZero(working) == 0
        } while (!done)

        return skeleton
    }
}
