package com.sitta.core.common

import com.google.gson.annotations.SerializedName

object ArtifactFilenames {
    const val RAW = "raw.png"
    const val ROI = "roi.png"
    const val ENHANCED = "enhanced.png"
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
)

data class MatchCandidate(
    @SerializedName("candidate_id") val candidateId: String,
    val score: Double,
    val decision: String,
)

data class MatchReport(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("probe_filename") val probeFilename: String,
    @SerializedName("threshold_used") val thresholdUsed: Double,
    val candidates: List<MatchCandidate>,
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
