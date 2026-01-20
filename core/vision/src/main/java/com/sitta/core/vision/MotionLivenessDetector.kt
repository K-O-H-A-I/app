package com.sitta.core.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.sitta.core.common.AppConfig
import com.sitta.core.domain.LivenessDetector
import com.sitta.core.domain.LivenessResult
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

class MotionLivenessDetector(private var config: AppConfig) : LivenessDetector {
    fun updateConfig(newConfig: AppConfig) {
        config = newConfig
    }

    override fun evaluate(frames: List<Bitmap>, roi: Rect): LivenessResult {
        if (frames.size < 30) {
            // Not enough frames for FFT analysis (need ~1 sec at 30fps)
            return LivenessResult("FAIL", 0.0, 0.0)
        }

        val signals = DoubleArray(frames.size)
        val fps = 30.0 // Assume ~30fps for now
        
        // 1. Extract Signal (Green Channel Average on Skin Pixels)
        frames.forEachIndexed { index, bitmap ->
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            
            // Crop to ROI
            val cropped = OpenCvUtils.cropMat(mat, roi)
            
            // Resize for speed (downscale)
            val small = Mat()
            Imgproc.resize(cropped, small, Size(64.0, 64.0))
            
            // Estimate Skin Mask (HSV + YCrCb) similar to Python
            val mask = estimateFingerMask(small)
            
            // Mean of Green Channel in Masked Area
            val meanColor = Core.mean(small, mask)
            // Green is index 1 in RGBA (but Bitmap -> Mat is usually RGBA, so R=0, G=1, B=2, A=3)
            // Wait, Android Bitmap config ARGB_8888 -> OpenCV RGBA. 
            // OpenCV Utils usually loads as RGBA.
            signals[index] = meanColor.`val`[1] 
            
            mat.release()
            cropped.release()
            small.release()
            mask.release()
        }

        // 2. FFT & Pulse Estimation
        val result = estimatePulse(signals, fps)
        
        // 3. Decision
        // Python code: score 55.0 threshold. We'll normalize.
        val passed = result.score > 2.0 // SNR threshold
        
        return result.copy(decision = if (passed) "PASS" else "FAIL")
    }

    private fun estimateFingerMask(frame: Mat): Mat {
        val blurred = Mat()
        Imgproc.GaussianBlur(frame, blurred, Size(5.0, 5.0), 0.0)
        
        val hsv = Mat()
        Imgproc.cvtColor(blurred, hsv, Imgproc.COLOR_RGB2HSV)
        
        val ycrcb = Mat()
        Imgproc.cvtColor(blurred, ycrcb, Imgproc.COLOR_RGB2YCrCb)
        
        val maskYCrCb = Mat()
        // Python: (0, 135, 80), (255, 180, 140)
        Core.inRange(ycrcb, Scalar(0.0, 135.0, 80.0), Scalar(255.0, 180.0, 140.0), maskYCrCb)
        
        val maskHsv = Mat()
        // Python: (0, 10, 40), (25, 255, 255)
        Core.inRange(hsv, Scalar(0.0, 10.0, 40.0), Scalar(25.0, 255.0, 255.0), maskHsv)
        
        val mask = Mat()
        Core.bitwise_and(maskYCrCb, maskHsv, mask)
        
        // Morphology
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
        
        blurred.release()
        hsv.release()
        ycrcb.release()
        maskYCrCb.release()
        maskHsv.release()
        
        return mask
    }

    private fun estimatePulse(signal: DoubleArray, fps: Double): LivenessResult {
        // Detrend (Subtract mean)
        val mean = signal.average()
        val detrended = signal.map { it - mean }.toDoubleArray()
        
        // FFT using OpenCV
        // Pad to optimal size
        val paddedSize = Core.getOptimalDFTSize(detrended.size)
        val padded = Mat()
        val srcMat = Mat(detrended.size, 1, CvType.CV_64F)
        for (i in detrended.indices) {
            srcMat.put(i, 0, detrended[i])
        }
        
        Core.copyMakeBorder(srcMat, padded, 0, paddedSize - detrended.size, 0, 0, Core.BORDER_CONSTANT, Scalar.all(0.0))
        
        val planes = ArrayList<Mat>()
        planes.add(padded)
        planes.add(Mat.zeros(padded.size(), CvType.CV_64F))
        val complexI = Mat()
        Core.merge(planes, complexI)
        
        Core.dft(complexI, complexI)
        
        Core.split(complexI, planes)
        // Magnitude = sqrt(Re^2 + Im^2)
        val mag = Mat()
        Core.magnitude(planes[0], planes[1], mag)
        
        val magnitudes = FloatArray(mag.rows())
        mag.get(0, 0, magnitudes)
        
        // Find Peak in Band (0.7 - 4.0 Hz)
        val freqResolution = fps / paddedSize
        val lowBin = (0.7 / freqResolution).toInt()
        val highBin = (4.0 / freqResolution).toInt()
        
        var maxPower = 0.0
        var maxBin = 0
        var ind = 0
        for (i in 0 until magnitudes.size / 2) { // Only need first half
             if (i in lowBin..highBin) {
                 if (magnitudes[i] > maxPower) {
                     maxPower = magnitudes[i].toDouble()
                     maxBin = i
                 }
             }
             ind++
        }
        
        // Calculate SNR
        // Noise = Median of band
        // Python: 10 * log10(peak / noise)
        val bandPowers = ArrayList<Double>()
        for (i in lowBin..highBin) {
            if (i < magnitudes.size / 2) {
                bandPowers.add(magnitudes[i].toDouble())
            }
        }
        bandPowers.sort()
        val noise = if (bandPowers.isNotEmpty()) bandPowers[bandPowers.size / 2] else 1.0 // Median
        
        val snr = if (noise > 0) 10.0 * ln((maxPower + 1e-6) / (noise + 1e-6)) / ln(10.0) else 0.0
        
        return LivenessResult(
             decision = "UNKNOWN", // Set by caller
             score = snr,
             variance = 0.0 // Legacy field
        )
    }
}
