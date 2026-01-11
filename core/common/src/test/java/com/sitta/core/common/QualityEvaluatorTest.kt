package com.sitta.core.common

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityEvaluatorTest {
    @Test
    fun `passes when metrics meet thresholds`() {
        val config = DefaultConfig.value
        val metrics = QualityMetrics(
            blurScore = config.blurThreshold + 10,
            illuminationMean = (config.illuminationMin + config.illuminationMax) / 2,
            coverageRatio = config.coverageMin + 0.1,
            stabilityVariance = config.stabilityMax - 0.5,
        )
        val result = QualityEvaluator.evaluate(metrics, config)
        assertTrue(result.pass)
        assertEquals("OK", result.topReason)
        assertTrue(result.score0To100 in 0..100)
    }

    @Test
    fun `fails when blur below threshold`() {
        val config = DefaultConfig.value
        val metrics = QualityMetrics(
            blurScore = config.blurThreshold - 1,
            illuminationMean = (config.illuminationMin + config.illuminationMax) / 2,
            coverageRatio = config.coverageMin + 0.1,
            stabilityVariance = config.stabilityMax - 0.5,
        )
        val result = QualityEvaluator.evaluate(metrics, config)
        assertFalse(result.pass)
        assertEquals("Blur too high", result.topReason)
    }

    @Test
    fun `parses config json`() {
        val json = """
            {
              "blurThreshold": 55.0,
              "illuminationMin": 90.0,
              "illuminationMax": 170.0,
              "coverageMin": 0.7,
              "stabilityMax": 1.2,
              "matchThreshold": 42.0,
              "livenessVarianceMin": 0.03,
              "livenessVarianceMax": 0.4
            }
        """.trimIndent()
        val config = Gson().fromJson(json, AppConfig::class.java)
        assertEquals(55.0, config.blurThreshold, 0.0)
        assertEquals(0.7, config.coverageMin, 0.0)
        assertEquals(42.0, config.matchThreshold, 0.0)
    }

    @Test
    fun `match decision threshold`() {
        assertEquals("MATCH", MatchDecision.decide(40.0, 40.0))
        assertEquals("NO_MATCH", MatchDecision.decide(39.9, 40.0))
    }
}
