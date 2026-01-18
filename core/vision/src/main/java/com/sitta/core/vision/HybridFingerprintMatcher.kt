package com.sitta.core.vision

import android.graphics.Bitmap
import com.sitta.core.domain.MatchCandidateResult
import com.sitta.core.domain.MatchResult
import com.sitta.core.domain.Matcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.MatOfDouble
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class HybridFingerprintMatcher : Matcher {
    private val targetSize = 256
    private val matchThreshold = 58.0
    private val uncertainThreshold = 45.0
    private val weights = mapOf(
        "orientation" to 0.30,
        "gabor" to 0.25,
        "frequency" to 0.20,
        "texture" to 0.15,
        "pixel" to 0.10,
    )

    override suspend fun match(
        probe: Bitmap,
        candidates: Map<String, Bitmap>,
        threshold: Double,
    ): MatchResult {
        return withContext(Dispatchers.Default) {
            val overallStart = System.nanoTime()
            if (!OpenCvUtils.ensureLoadedOrFalse()) {
                return@withContext MatchResult(
                    thresholdUsed = threshold,
                    candidates = candidates.keys.map {
                        MatchCandidateResult(candidateId = it, score = 0.0, decision = "NO_MATCH")
                    },
                    timeMs = 0L,
                )
            }
            val probeMat = preprocess(probe, isContact = false)
            val probeRidges = extractRidgeStructure(probeMat)
            val results = candidates.map { (id, candidateBitmap) ->
                val start = System.nanoTime()
                val contactMat = preprocess(candidateBitmap, isContact = true)
                val contactRidges = extractRidgeStructure(contactMat)
                val aligned = alignImages(contactRidges, probeRidges)
                val contactCore = extractCoreRegion(contactRidges)
                val alignedCore = extractCoreRegion(aligned)

                val (o1, c1) = computeOrientationField(contactCore)
                val (o2, c2) = computeOrientationField(alignedCore)
                val orientSim = orientationSimilarity(o1, c1, o2, c2)
                val gaborSim = vectorSimilarity(
                    computeGaborFeatures(contactCore),
                    computeGaborFeatures(alignedCore),
                )
                val freqSim = frequencySimilarity(
                    computeRidgeFrequency(contactCore, o1),
                    computeRidgeFrequency(alignedCore, o2),
                )
                val texSim = vectorSimilarity(
                    computeTextureFeatures(contactCore),
                    computeTextureFeatures(alignedCore),
                )
                val pixelSim = pixelSimilarity(contactCore, alignedCore)

                val score = (
                    weights.getValue("orientation") * orientSim +
                        weights.getValue("gabor") * gaborSim +
                        weights.getValue("frequency") * freqSim +
                        weights.getValue("texture") * texSim +
                        weights.getValue("pixel") * pixelSim
                    ) * 100.0

                val decision = when {
                    score >= matchThreshold -> "MATCH"
                    score >= uncertainThreshold -> "UNCERTAIN"
                    else -> "NO_MATCH"
                }

                val timeMs = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(0L)
                MatchCandidateResult(
                    candidateId = id,
                    score = score,
                    decision = decision,
                    confidence = (score / 100.0).coerceIn(0.0, 1.0),
                    featureScores = mapOf(
                        "orientation" to orientSim,
                        "gabor" to gaborSim,
                        "frequency" to freqSim,
                        "texture" to texSim,
                        "pixel" to pixelSim,
                    ),
                    timeMs = timeMs,
                )
            }.sortedByDescending { it.score }

            val overallMs = ((System.nanoTime() - overallStart) / 1_000_000L).coerceAtLeast(0L)
            MatchResult(thresholdUsed = threshold, candidates = results, timeMs = overallMs)
        }
    }

    private fun preprocess(bitmap: Bitmap, isContact: Boolean): Mat {
        val src = OpenCvUtils.bitmapToMat(bitmap)
        val gray = Mat()
        if (src.channels() == 4) {
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        } else {
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        }
        val resized = resizeAndPad(gray)
        val inverted = if (isContact && Core.mean(resized).`val`[0] > 127.0) {
            val inv = Mat()
            Core.bitwise_not(resized, inv)
            inv
        } else {
            resized
        }
        val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(inverted, enhanced)
        return enhanced
    }

    private fun resizeAndPad(gray: Mat): Mat {
        val h = gray.rows()
        val w = gray.cols()
        val scale = min(targetSize.toDouble() / w, targetSize.toDouble() / h)
        val newW = max(1, (w * scale).toInt())
        val newH = max(1, (h * scale).toInt())
        val resized = Mat()
        Imgproc.resize(gray, resized, Size(newW.toDouble(), newH.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val padded = Mat.zeros(targetSize, targetSize, CvType.CV_8U)
        val padX = (targetSize - newW) / 2
        val padY = (targetSize - newH) / 2
        resized.copyTo(padded.submat(padY, padY + newH, padX, padX + newW))
        return padded
    }

    private fun extractRidgeStructure(img: Mat): Mat {
        val h = img.rows()
        val w = img.cols()
        val ridgeMap = Mat.zeros(h, w, CvType.CV_32F)
        val frequencies = doubleArrayOf(0.08, 0.10, 0.12, 0.15)
        val nOrient = 12
        for (freq in frequencies) {
            val sigma = 1.0 / freq / 1.5
            val kernelSize = (sigma * 6).toInt().or(1)
            for (i in 0 until nOrient) {
                val theta = i * Math.PI / nOrient
                val kernel = Imgproc.getGaborKernel(
                    Size(kernelSize.toDouble(), kernelSize.toDouble()),
                    sigma,
                    theta,
                    1.0 / freq,
                    0.5,
                    0.0,
                    CvType.CV_32F,
                )
                val filtered = Mat()
                Imgproc.filter2D(img, filtered, CvType.CV_32F, kernel)
                Core.absdiff(filtered, Scalar(0.0), filtered)
                Core.max(ridgeMap, filtered, ridgeMap)
            }
        }
        val normalized = Mat()
        Core.normalize(ridgeMap, normalized, 0.0, 255.0, Core.NORM_MINMAX)
        normalized.convertTo(normalized, CvType.CV_8U)
        return normalized
    }

    private fun alignImages(img1: Mat, img2: Mat): Mat {
        val h = img1.rows()
        val w = img1.cols()
        var bestAngle = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (angle in -20..20 step 4) {
            val rot = rotate(img2, angle.toDouble())
            val response = phaseCorrelate(img1, rot).second
            if (response > bestScore) {
                bestScore = response
                bestAngle = angle
            }
        }
        val rotated = rotate(img2, bestAngle.toDouble())
        val phase = phaseCorrelate(img1, rotated)
        val shift = phase.first
        val dx = -shift.x
        val dy = -shift.y
        val m = Mat(2, 3, CvType.CV_32F)
        m.put(0, 0, 1.0, 0.0, dx, 0.0, 1.0, dy)
        val aligned = Mat()
        Imgproc.warpAffine(rotated, aligned, m, Size(w.toDouble(), h.toDouble()))
        return aligned
    }

    private fun rotate(img: Mat, angle: Double): Mat {
        val center = Point(img.cols() / 2.0, img.rows() / 2.0)
        val m = Imgproc.getRotationMatrix2D(center, angle, 1.0)
        val out = Mat()
        Imgproc.warpAffine(img, out, m, Size(img.cols().toDouble(), img.rows().toDouble()))
        return out
    }

    private fun extractCoreRegion(img: Mat, ratio: Double = 0.7): Mat {
        val h = img.rows()
        val w = img.cols()
        val marginY = ((1 - ratio) * h / 2.0).toInt()
        val marginX = ((1 - ratio) * w / 2.0).toInt()
        return img.submat(marginY, h - marginY, marginX, w - marginX)
    }

    private fun computeOrientationField(img: Mat, blockSize: Int = 16): Pair<Mat, Mat> {
        val gx = Mat()
        val gy = Mat()
        Imgproc.Sobel(img, gx, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(img, gy, CvType.CV_32F, 0, 1, 3)
        val h = img.rows()
        val w = img.cols()
        val nY = h / blockSize
        val nX = w / blockSize
        val orientation = Mat.zeros(nY, nX, CvType.CV_32F)
        val coherence = Mat.zeros(nY, nX, CvType.CV_32F)
        for (j in 0 until nY) {
            for (i in 0 until nX) {
                val y1 = j * blockSize
                val y2 = (j + 1) * blockSize
                val x1 = i * blockSize
                val x2 = (i + 1) * blockSize
                val blockGx = gx.submat(y1, y2, x1, x2)
                val blockGy = gy.submat(y1, y2, x1, x2)
                val gxx = Mat()
                val gyy = Mat()
                val gxy = Mat()
                Core.multiply(blockGx, blockGx, gxx)
                Core.multiply(blockGy, blockGy, gyy)
                Core.multiply(blockGx, blockGy, gxy)
                val vx = 2.0 * Core.sumElems(gxy).`val`[0]
                val vy = Core.sumElems(gxx).`val`[0] - Core.sumElems(gyy).`val`[0]
                val angle = 0.5 * atan2(vx, vy)
                orientation.put(j, i, angle)
                val denom = Core.sumElems(gxx).`val`[0] + Core.sumElems(gyy).`val`[0]
                val coh = if (denom > 1e-6) sqrt(vx * vx + vy * vy) / denom else 0.0
                coherence.put(j, i, coh)
            }
        }
        return orientation to coherence
    }

    private fun computeGaborFeatures(img: Mat): DoubleArray {
        val features = ArrayList<Double>()
        val freqs = doubleArrayOf(0.08, 0.10, 0.13)
        for (freq in freqs) {
            for (i in 0 until 8) {
                val theta = i * Math.PI / 8.0
                val kernel = Imgproc.getGaborKernel(Size(21.0, 21.0), 3.5, theta, 1.0 / freq, 0.5, 0.0, CvType.CV_32F)
                val filtered = Mat()
                Imgproc.filter2D(img, filtered, CvType.CV_32F, kernel)
                val mean = Core.mean(filtered).`val`[0]
                val std = meanStdDev(filtered).second
                val p90 = percentileAbs(filtered, 0.9)
                features.add(mean)
                features.add(std)
                features.add(p90)
            }
        }
        return features.toDoubleArray()
    }

    private fun computeRidgeFrequency(img: Mat, orientation: Mat, blockSize: Int = 32): Mat {
        val h = img.rows()
        val w = img.cols()
        val nY = h / blockSize
        val nX = w / blockSize
        val freqMap = Mat.zeros(nY, nX, CvType.CV_32F)
        for (j in 0 until nY) {
            for (i in 0 until nX) {
                val y1 = j * blockSize
                val y2 = (j + 1) * blockSize
                val x1 = i * blockSize
                val x2 = (i + 1) * blockSize
                val block = img.submat(y1, y2, x1, x2)
                val oj = min(j, orientation.rows() - 1)
                val oi = min(i, orientation.cols() - 1)
                val theta = orientation.get(oj, oi)[0]
                val m = Imgproc.getRotationMatrix2D(Point(blockSize / 2.0, blockSize / 2.0), -theta * 180.0 / Math.PI, 1.0)
                val rotated = Mat()
                Imgproc.warpAffine(block, rotated, m, Size(blockSize.toDouble(), blockSize.toDouble()))
                val profile = Mat()
                Core.reduce(rotated, profile, 0, Core.REDUCE_AVG, CvType.CV_32F)
                val profileArr = FloatArray(profile.cols())
                profile.get(0, 0, profileArr)
                val mean = profileArr.average().toFloat()
                for (k in profileArr.indices) {
                    profileArr[k] = profileArr[k] - mean
                }
                val peaks = findPeaks(profileArr, 3, (std(profileArr) * 0.3).toFloat())
                if (peaks.size >= 2) {
                    val diffs = peaks.zipWithNext { a, b -> (b - a).toFloat() }
                    val avgDist = diffs.average().toFloat()
                    if (avgDist > 0f) {
                        freqMap.put(j, i, 1.0 / avgDist.toDouble())
                    }
                }
            }
        }
        return freqMap
    }

    private fun computeTextureFeatures(img: Mat, nBlocks: Int = 4): DoubleArray {
        val h = img.rows()
        val w = img.cols()
        val bh = h / nBlocks
        val bw = w / nBlocks
        val features = ArrayList<Double>()
        for (j in 0 until nBlocks) {
            for (i in 0 until nBlocks) {
                val y1 = j * bh
                val y2 = (j + 1) * bh
                val x1 = i * bw
                val x2 = (i + 1) * bw
                val block = img.submat(y1, y2, x1, x2)
                features.add(Core.mean(block).`val`[0])
                features.add(meanStdDev(block).second)
                val gx = Mat()
                val gy = Mat()
                Imgproc.Sobel(block, gx, CvType.CV_32F, 1, 0, 3)
                Imgproc.Sobel(block, gy, CvType.CV_32F, 0, 1, 3)
                features.add(meanAbs(gx))
                features.add(meanAbs(gy))
            }
        }
        return features.toDoubleArray()
    }

    private fun orientationSimilarity(o1: Mat, c1: Mat, o2: Mat, c2: Mat): Double {
        val minH = min(o1.rows(), o2.rows())
        val minW = min(o1.cols(), o2.cols())
        var sum = 0.0
        var weightSum = 0.0
        for (y in 0 until minH) {
            for (x in 0 until minW) {
                val coh1 = c1.get(y, x)[0]
                val coh2 = c2.get(y, x)[0]
                if (coh1 <= 0.2 || coh2 <= 0.2) continue
                val diff = abs(o1.get(y, x)[0] - o2.get(y, x)[0])
                val wrapped = min(diff, Math.PI - diff)
                val weight = sqrt(coh1 * coh2)
                sum += wrapped * weight
                weightSum += weight
            }
        }
        if (weightSum < 1e-6) return 0.5
        val meanDiff = sum / weightSum
        return 1.0 - meanDiff / (Math.PI / 2.0)
    }

    private fun vectorSimilarity(v1: DoubleArray, v2: DoubleArray): Double {
        if (v1.isEmpty() || v2.isEmpty()) return 0.5
        var dot = 0.0
        var norm1 = 0.0
        var norm2 = 0.0
        val n = min(v1.size, v2.size)
        for (i in 0 until n) {
            dot += v1[i] * v2[i]
            norm1 += v1[i] * v1[i]
            norm2 += v2[i] * v2[i]
        }
        if (norm1 < 1e-8 || norm2 < 1e-8) return 0.5
        return max(0.0, dot / (sqrt(norm1) * sqrt(norm2)))
    }

    private fun frequencySimilarity(f1: Mat, f2: Mat): Double {
        val minH = min(f1.rows(), f2.rows())
        val minW = min(f1.cols(), f2.cols())
        val arr1 = DoubleArray(minH * minW)
        val arr2 = DoubleArray(minH * minW)
        var idx = 0
        var sum1 = 0.0
        var sum2 = 0.0
        for (y in 0 until minH) {
            for (x in 0 until minW) {
                val v1 = f1.get(y, x)[0]
                val v2 = f2.get(y, x)[0]
                arr1[idx] = v1
                arr2[idx] = v2
                sum1 += v1
                sum2 += v2
                idx++
            }
        }
        val mean1 = sum1 / arr1.size
        val mean2 = sum2 / arr2.size
        var num = 0.0
        var den1 = 0.0
        var den2 = 0.0
        for (i in arr1.indices) {
            val a = arr1[i] / (mean1 + 1e-8)
            val b = arr2[i] / (mean2 + 1e-8)
            num += (a - 1.0) * (b - 1.0)
            den1 += (a - 1.0) * (a - 1.0)
            den2 += (b - 1.0) * (b - 1.0)
        }
        val denom = sqrt(den1 * den2)
        val corr = if (denom < 1e-8) 0.0 else num / denom
        return if (corr.isNaN()) 0.5 else (corr + 1.0) / 2.0
    }

    private fun pixelSimilarity(img1: Mat, img2: Mat): Double {
        val f1 = Mat()
        val f2 = Mat()
        img1.convertTo(f1, CvType.CV_32F)
        img2.convertTo(f2, CvType.CV_32F)
        val mean1 = Core.mean(f1).`val`[0]
        val mean2 = Core.mean(f2).`val`[0]
        Core.subtract(f1, Scalar(mean1), f1)
        Core.subtract(f2, Scalar(mean2), f2)
        val num = Core.sumElems(f1.mul(f2)).`val`[0]
        val den1 = Core.sumElems(f1.mul(f1)).`val`[0]
        val den2 = Core.sumElems(f2.mul(f2)).`val`[0]
        val denom = sqrt(den1 * den2) + 1e-8
        val ncc = num / denom
        return max(0.0, ncc)
    }

    private fun percentileAbs(mat: Mat, p: Double): Double {
        val data = FloatArray(mat.rows() * mat.cols())
        mat.get(0, 0, data)
        if (data.isEmpty()) return 0.0
        data.sort()
        val idx = ((data.size - 1) * p).toInt().coerceIn(0, data.size - 1)
        return abs(data[idx].toDouble())
    }

    private fun meanStdDev(mat: Mat): Pair<Double, Double> {
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(mat, mean, std)
        val meanVal = mean.toArray().getOrNull(0) ?: 0.0
        val stdVal = std.toArray().getOrNull(0) ?: 0.0
        return meanVal to stdVal
    }

    private fun meanAbs(mat: Mat): Double {
        val absMat = Mat()
        Core.absdiff(mat, Scalar(0.0), absMat)
        return Core.mean(absMat).`val`[0]
    }

    internal fun std(values: FloatArray): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average().toFloat()
        var sum = 0.0
        for (v in values) {
            val d = v - mean
            sum += d * d
        }
        return sqrt(sum / values.size.toDouble())
    }

    internal fun findPeaks(profile: FloatArray, distance: Int, height: Float): List<Int> {
        val peaks = ArrayList<Int>()
        var last = -distance
        for (i in 1 until profile.size - 1) {
            val v = profile[i]
            if (v < height) continue
            if (v > profile[i - 1] && v > profile[i + 1] && i - last >= distance) {
                peaks.add(i)
                last = i
            }
        }
        return peaks
    }

    private fun phaseCorrelate(img1: Mat, img2: Mat): Pair<Point, Double> {
        val f1 = Mat()
        val f2 = Mat()
        img1.convertTo(f1, CvType.CV_32F)
        img2.convertTo(f2, CvType.CV_32F)
        val dft1 = Mat()
        val dft2 = Mat()
        Core.dft(f1, dft1, Core.DFT_COMPLEX_OUTPUT, 0)
        Core.dft(f2, dft2, Core.DFT_COMPLEX_OUTPUT, 0)
        val cps = Mat()
        Core.mulSpectrums(dft1, dft2, cps, 0, true)
        val planes = ArrayList<Mat>(2)
        Core.split(cps, planes)
        val mag = Mat()
        Core.magnitude(planes[0], planes[1], mag)
        Core.divide(planes[0], mag, planes[0])
        Core.divide(planes[1], mag, planes[1])
        Core.merge(planes, cps)
        val corr = Mat()
        Core.idft(cps, corr, Core.DFT_REAL_OUTPUT or Core.DFT_SCALE, 0)
        val mm = Core.minMaxLoc(corr)
        val peak = mm.maxLoc
        val shift = Point(
            peak.x - corr.cols() / 2.0,
            peak.y - corr.rows() / 2.0,
        )
        return shift to mm.maxVal
    }
}
