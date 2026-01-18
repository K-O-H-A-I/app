package com.sitta.core.common

import com.google.gson.annotations.SerializedName

object ArtifactFilenames {
    const val RAW = "raw.png"
    const val ROI = "roi.png"
    const val SEGMENTED = "segmented.png"
    const val SEGMENTATION_MASK = "mask.png"
    const val ENHANCED = "enhanced.png"
    const val RIDGES = "ridges.png"
    const val SEGMENTED_500DPI = "segmented_500dpi.png"
    const val ENHANCED_500DPI = "enhanced_500dpi.png"
    const val RIDGES_500DPI = "ridges_500dpi.png"
    const val ENHANCED_TIFF = "enhanced.tiff"
    const val SKELETON = "skeleton.png"
    const val SKELETON_TIFF = "skeleton.tiff"
    const val QUALITY = "quality.json"
    const val MATCH = "match.json"
    const val LIVENESS = "liveness.json"
}

data class AppConfig(
    @SerializedName("blurThreshold") val blurThreshold: Double,
    @SerializedName("illuminationMin") val illuminationMin: Double,
    @SerializedName("illuminationMax") val illuminationMax: Double,
    @SerializedName("coverageMin") val coverageMin: Double,
    @SerializedName("stabilityMax") val stabilityMax: Double,
    @SerializedName("matchThreshold") val matchThreshold: Double,
    @SerializedName("livenessVarianceMin") val livenessVarianceMin: Double,
    @SerializedName("livenessVarianceMax") val livenessVarianceMax: Double,
)

object DefaultConfig {
    val value = AppConfig(
        blurThreshold = 50.0,
        illuminationMin = 80.0,
        illuminationMax = 180.0,
        coverageMin = 0.6,
        stabilityMax = 1.5,
        matchThreshold = 40.0,
        livenessVarianceMin = 0.02,
        livenessVarianceMax = 0.5,
    )
}

data class QualityMetrics(
    @SerializedName("blur_score") val blurScore: Double,
    @SerializedName("illumination_mean") val illuminationMean: Double,
    @SerializedName("coverage_ratio") val coverageRatio: Double,
    @SerializedName("stability_variance") val stabilityVariance: Double,
)

data class QualityReport(
    @SerializedName("session_id") val sessionId: String,
    val timestamp: Long,
    @SerializedName("score_0_100") val score0To100: Int,
    val pass: Boolean,
    val metrics: QualityMetrics,
    @SerializedName("top_reason") val topReason: String,
    @SerializedName("capture_mode") val captureMode: String? = null,
    @SerializedName("performance") val performance: CapturePerformance? = null,
)

data class CapturePerformance(
    @SerializedName("capture_start_ms") val captureStartMs: Long,
    @SerializedName("ready_ms") val readyMs: Long?,
    @SerializedName("capture_complete_ms") val captureCompleteMs: Long,
    @SerializedName("time_to_ready_ms") val timeToReadyMs: Long?,
    @SerializedName("total_time_ms") val totalTimeMs: Long,
    @SerializedName("frames_analyzed") val framesAnalyzed: Int,
    @SerializedName("mediapipe_success") val mediapipeSuccess: Int,
    @SerializedName("mediapipe_fail") val mediapipeFail: Int,
    @SerializedName("avg_blur") val avgBlur: Double,
    @SerializedName("avg_light") val avgLight: Double,
    @SerializedName("avg_steady") val avgSteady: Double,
)

data class MatchCandidate(
    @SerializedName("candidate_id") val candidateId: String,
    val score: Double,
    val decision: String,
    @SerializedName("confidence") val confidence: Double? = null,
    @SerializedName("feature_scores") val featureScores: Map<String, Double> = emptyMap(),
    @SerializedName("time_ms") val timeMs: Long? = null,
)

data class MatchReport(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("probe_filename") val probeFilename: String,
    @SerializedName("threshold_used") val thresholdUsed: Double,
    val candidates: List<MatchCandidate>,
    @SerializedName("time_ms") val timeMs: Long? = null,
)

data class LivenessReport(
    @SerializedName("session_id") val sessionId: String,
    val decision: String,
    val score: Double,
    @SerializedName("heuristic_used") val heuristicUsed: String,
)

data class SessionInfo(
    val tenantId: String,
    val sessionId: String,
    val timestamp: Long,
)

sealed class AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : AppResult<Nothing>()
}
