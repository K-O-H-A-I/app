package com.sitta.core.domain

import android.graphics.Bitmap
import android.graphics.Rect
import com.sitta.core.common.QualityMetricPasses
import com.sitta.core.common.QualityMetrics

interface QualityAnalyzer {
    fun analyze(bitmap: Bitmap, roi: Rect, timestampMillis: Long = System.currentTimeMillis()): QualityResult
}

data class QualityResult(
    val metrics: QualityMetrics,
    val passes: QualityMetricPasses,
    val score0To100: Int,
    val pass: Boolean,
    val topReason: String,
    val timestampMillis: Long,
)

class QualityStabilityGate(private val stableDurationMs: Long = 500L) {
    private var passStartMillis: Long? = null

    fun update(pass: Boolean, timestampMillis: Long): Boolean {
        if (!pass) {
            passStartMillis = null
            return false
        }
        val start = passStartMillis
        return if (start == null) {
            passStartMillis = timestampMillis
            false
        } else {
            timestampMillis - start >= stableDurationMs
        }
    }
}

data class EnhancementStep(
    val name: String,
    val durationMs: Long,
)

data class EnhancementResult(
    val bitmap: Bitmap,
    val steps: List<EnhancementStep>,
)

interface EnhancementPipeline {
    suspend fun enhance(bitmap: Bitmap, sharpenStrength: Float): EnhancementResult
}

data class MatchCandidateResult(
    val candidateId: String,
    val score: Double,
    val decision: String,
)

data class MatchResult(
    val thresholdUsed: Double,
    val candidates: List<MatchCandidateResult>,
)

interface Matcher {
    suspend fun match(
        probe: Bitmap,
        candidates: Map<String, Bitmap>,
        threshold: Double,
    ): MatchResult
}

data class LivenessResult(
    val decision: String,
    val score: Double,
    val variance: Double,
)

interface LivenessDetector {
    fun evaluate(frames: List<Bitmap>, roi: Rect): LivenessResult
}
