package com.sitta.core.common

import kotlin.math.max

object QualityEvaluator {
    data class Evaluation(
        val passes: QualityMetricPasses,
        val score0To100: Int,
        val topReason: String,
        val pass: Boolean,
    )

    fun evaluate(metrics: QualityMetrics, config: AppConfig): Evaluation {
        val passes = QualityMetricPasses(
            focus = metrics.blurScore >= config.blurThreshold,
            light = metrics.illuminationMean in config.illuminationMin..config.illuminationMax,
            coverage = metrics.coverageRatio >= config.coverageMin,
            steady = metrics.stabilityVariance <= config.stabilityMax,
        )
        val score = score(metrics, config)
        val pass = passes.focus && passes.light && passes.coverage && passes.steady
        val topReason = when {
            !passes.focus -> "Blur too high"
            !passes.light -> "Lighting out of range"
            !passes.coverage -> "Coverage too low"
            !passes.steady -> "Too much motion"
            else -> "OK"
        }
        return Evaluation(passes = passes, score0To100 = score, topReason = topReason, pass = pass)
    }

    private fun score(metrics: QualityMetrics, config: AppConfig): Int {
        val blurNorm = (metrics.blurScore / (config.blurThreshold * 2.0)).coerceIn(0.0, 1.0)
        val lightNorm = when {
            metrics.illuminationMean < config.illuminationMin ->
                (metrics.illuminationMean / config.illuminationMin).coerceIn(0.0, 1.0)
            metrics.illuminationMean > config.illuminationMax ->
                (config.illuminationMax / metrics.illuminationMean).coerceIn(0.0, 1.0)
            else -> 1.0
        }
        val coverageNorm = (metrics.coverageRatio / config.coverageMin).coerceIn(0.0, 1.0)
        val stabilityNorm = if (metrics.stabilityVariance <= 0.0) {
            1.0
        } else {
            (config.stabilityMax / metrics.stabilityVariance).coerceIn(0.0, 1.0)
        }
        val score = ((blurNorm + lightNorm + coverageNorm + stabilityNorm) / 4.0) * 100.0
        return score.toInt().coerceIn(0, 100)
    }
}

data class QualityMetricPasses(
    val focus: Boolean,
    val light: Boolean,
    val coverage: Boolean,
    val steady: Boolean,
)

object MatchDecision {
    fun decide(score: Double, threshold: Double): String {
        return if (score >= threshold) "MATCH" else "NO_MATCH"
    }
}
