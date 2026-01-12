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

        val skeleton = Mat.zeros(binary.size(), binary.type())
        val temp = Mat()
        val eroded = Mat()
        val element = Imgproc.getStructuringElement(Imgproc.MORPH_CROSS, Size(3.0, 3.0))

        var done: Boolean
        do {
            Imgproc.erode(binary, eroded, element)
            Imgproc.dilate(eroded, temp, element)
            Core.subtract(binary, temp, temp)
            Core.bitwise_or(skeleton, temp, skeleton)
            eroded.copyTo(binary)
            done = Core.countNonZero(binary) == 0
        } while (!done)

        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val color = Mat()
        Imgproc.cvtColor(skeleton, color, Imgproc.COLOR_GRAY2RGBA)
        Utils.matToBitmap(color, out)
        return out
    }
}
