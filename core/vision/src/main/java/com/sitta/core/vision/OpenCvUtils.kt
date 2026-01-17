package com.sitta.core.vision

import android.graphics.Bitmap
import android.graphics.Rect
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Rect as CvRect
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

object OpenCvUtils {
    private val loaded = lazy { OpenCVLoader.initDebug() }

    private fun ensureLoaded() {
        loaded.value
    }

    fun ensureLoadedOrFalse(): Boolean {
        return loaded.value
    }

    fun bitmapToMat(bitmap: Bitmap): Mat {
        ensureLoaded()
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return mat
    }

    fun cropMat(mat: Mat, roi: Rect): Mat {
        val safe = clampRoi(roi, mat.cols(), mat.rows())
        return Mat(mat, CvRect(safe.left, safe.top, safe.width(), safe.height()))
    }

    fun saveBitmapAsTiff(bitmap: Bitmap, outputFile: File): Boolean {
        ensureLoaded()
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return Imgcodecs.imwrite(outputFile.absolutePath, mat)
    }

    fun clampRoi(roi: Rect, width: Int, height: Int): Rect {
        val left = roi.left.coerceIn(0, width - 1)
        val top = roi.top.coerceIn(0, height - 1)
        val right = roi.right.coerceIn(left + 1, width)
        val bottom = roi.bottom.coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }
}
