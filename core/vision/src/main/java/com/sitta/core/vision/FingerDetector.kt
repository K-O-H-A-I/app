package com.sitta.core.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.atan2

class FingerDetector(private val context: Context) {
    private var handLandmarker: HandLandmarker? = null
    private var isInitialized = false

    fun initialize() {
        if (isInitialized) return
        runCatching {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.45f)
                .setMinHandPresenceConfidence(0.45f)
                .setMinTrackingConfidence(0.45f)
                .build()
            handLandmarker = HandLandmarker.createFromOptions(context, options)
            isInitialized = true
        }.onFailure {
            isInitialized = false
        }
    }

    fun detectFinger(bitmap: Bitmap): FingerDetectionResult {
        if (!isInitialized || handLandmarker == null) {
            return detectFingerFallback(bitmap)
        }

        return runCatching {
            val scaled = downscale(bitmap, 480)
            val mpImage = BitmapImageBuilder(scaled).build()
            val result = handLandmarker!!.detect(mpImage)
            parseHandLandmarkerResult(result, scaled.width, scaled.height)
        }.getOrElse {
            detectFingerFallback(bitmap)
        }
    }

    private fun parseHandLandmarkerResult(
        result: HandLandmarkerResult,
        imageWidth: Int,
        imageHeight: Int,
    ): FingerDetectionResult {
        if (result.landmarks().isEmpty()) {
            return FingerDetectionResult.notDetected()
        }

        val handLandmarks = result.landmarks()[0]
        val landmarks = handLandmarks.mapIndexed { index, landmark ->
            FingerLandmark(
                x = landmark.x(),
                y = landmark.y(),
                z = landmark.z(),
                type = indexToLandmarkType(index),
            )
        }

        val indexLandmarks = landmarks.filter {
            it.type in listOf(
                LandmarkType.INDEX_MCP,
                LandmarkType.INDEX_PIP,
                LandmarkType.INDEX_DIP,
                LandmarkType.INDEX_TIP,
            )
        }

        val boundingBox = if (indexLandmarks.isNotEmpty()) {
            val minX = indexLandmarks.minOf { it.x }
            val maxX = indexLandmarks.maxOf { it.x }
            val minY = indexLandmarks.minOf { it.y }
            val maxY = indexLandmarks.maxOf { it.y }
            val paddingX = (maxX - minX) * 0.3f
            val paddingY = (maxY - minY) * 0.2f
            RectF(
                (minX - paddingX).coerceAtLeast(0f),
                (minY - paddingY).coerceAtLeast(0f),
                (maxX + paddingX).coerceAtMost(1f),
                (maxY + paddingY).coerceAtMost(1f),
            )
        } else {
            null
        }

        val indexMcp = landmarks.find { it.type == LandmarkType.INDEX_MCP }
        val indexTip = landmarks.find { it.type == LandmarkType.INDEX_TIP }
        val orientationAngle = if (indexMcp != null && indexTip != null) {
            val dx = indexTip.x - indexMcp.x
            val dy = indexTip.y - indexMcp.y
            Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
        } else {
            0f
        }

        val palmFacing = estimatePalmFacing(landmarks)

        return FingerDetectionResult(
            isDetected = true,
            boundingBox = boundingBox,
            landmarks = landmarks,
            confidence = result.handedness()[0][0].score(),
            orientationAngle = orientationAngle,
            palmFacing = palmFacing,
        )
    }

    private fun estimatePalmFacing(landmarks: List<FingerLandmark>): Boolean {
        val wrist = landmarks.find { it.type == LandmarkType.WRIST } ?: return false
        val indexMcp = landmarks.find { it.type == LandmarkType.INDEX_MCP } ?: return false
        val pinkyMcp = landmarks.find { it.type == LandmarkType.PINKY_MCP } ?: return false

        val v1x = indexMcp.x - wrist.x
        val v1y = indexMcp.y - wrist.y
        val v1z = indexMcp.z - wrist.z
        val v2x = pinkyMcp.x - wrist.x
        val v2y = pinkyMcp.y - wrist.y
        val v2z = pinkyMcp.z - wrist.z

        val nx = v1y * v2z - v1z * v2y
        val ny = v1z * v2x - v1x * v2z
        val nz = v1x * v2y - v1y * v2x

        return nz < 0f
    }

    private fun indexToLandmarkType(index: Int): LandmarkType {
        return when (index) {
            0 -> LandmarkType.WRIST
            1 -> LandmarkType.THUMB_CMC
            2 -> LandmarkType.THUMB_MCP
            3 -> LandmarkType.THUMB_IP
            4 -> LandmarkType.THUMB_TIP
            5 -> LandmarkType.INDEX_MCP
            6 -> LandmarkType.INDEX_PIP
            7 -> LandmarkType.INDEX_DIP
            8 -> LandmarkType.INDEX_TIP
            9 -> LandmarkType.MIDDLE_MCP
            10 -> LandmarkType.MIDDLE_PIP
            11 -> LandmarkType.MIDDLE_DIP
            12 -> LandmarkType.MIDDLE_TIP
            13 -> LandmarkType.RING_MCP
            14 -> LandmarkType.RING_PIP
            15 -> LandmarkType.RING_DIP
            16 -> LandmarkType.RING_TIP
            17 -> LandmarkType.PINKY_MCP
            18 -> LandmarkType.PINKY_PIP
            19 -> LandmarkType.PINKY_DIP
            20 -> LandmarkType.PINKY_TIP
            else -> LandmarkType.WRIST
        }
    }

    private fun detectFingerFallback(bitmap: Bitmap): FingerDetectionResult {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var minX = width
        var maxX = 0
        var minY = height
        var maxY = 0
        var skinPixelCount = 0

        val step = 4
        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val pixel = pixels[y * width + x]
                if (isSkinColor(pixel)) {
                    skinPixelCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        val minSkinRatio = 0.03f
        val skinRatio = skinPixelCount.toFloat() / ((width / step) * (height / step))
        if (skinRatio < minSkinRatio || minX >= maxX || minY >= maxY) {
            return FingerDetectionResult.notDetected()
        }

        val boundingBox = RectF(
            minX.toFloat() / width,
            minY.toFloat() / height,
            maxX.toFloat() / width,
            maxY.toFloat() / height,
        )

        return FingerDetectionResult(
            isDetected = true,
            boundingBox = boundingBox,
            landmarks = emptyList(),
            confidence = skinRatio.coerceAtMost(1f),
            orientationAngle = 0f,
            palmFacing = true,
        )
    }

    private fun isSkinColor(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min

        val v = max.toFloat()
        val s = if (max == 0) 0f else (delta.toFloat() / max) * 255
        val h = when {
            delta == 0 -> 0f
            max == r -> 60 * (((g - b).toFloat() / delta) % 6)
            max == g -> 60 * (((b - r).toFloat() / delta) + 2)
            else -> 60 * (((r - g).toFloat() / delta) + 4)
        }.let { if (it < 0) it + 360 else it }

        return h in 0f..25f && s in 40f..255f && v in 60f..255f
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

    fun close() {
        handLandmarker?.close()
        handLandmarker = null
        isInitialized = false
    }
}
