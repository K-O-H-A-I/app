package com.sitta.core.vision

import android.graphics.Bitmap
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class FingerMasker {
    fun applyMask(bitmap: Bitmap): Bitmap {
        val mat = OpenCvUtils.bitmapToMat(bitmap)
        val rgb = Mat()
        Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_RGBA2RGB)
        val ycrcb = Mat()
        Imgproc.cvtColor(rgb, ycrcb, Imgproc.COLOR_RGB2YCrCb)
        val mask = Mat()
        Core.inRange(ycrcb, Scalar(0.0, 133.0, 77.0), Scalar(255.0, 173.0, 127.0), mask)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)

        val masked = Mat(mat.size(), mat.type(), Scalar(0.0, 0.0, 0.0, 255.0))
        mat.copyTo(masked, mask)

        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        org.opencv.android.Utils.matToBitmap(masked, out)
        return out
    }
}
