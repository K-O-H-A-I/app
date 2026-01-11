package com.sitta.app

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.google.gson.Gson
import com.sitta.core.common.QualityMetrics
import com.sitta.core.data.AuthManager
import com.sitta.core.data.ConfigRepo
import com.sitta.core.data.SessionRepository
import com.sitta.core.data.SettingsRepository
import com.sitta.core.domain.EnhancementPipeline
import com.sitta.core.domain.LivenessDetector
import com.sitta.core.domain.LivenessResult
import com.sitta.core.domain.Matcher
import com.sitta.core.domain.QualityAnalyzer
import com.sitta.core.domain.QualityResult
import com.sitta.feature.track_a.TrackAScreen
import com.sitta.feature.track_b.TrackBScreen
import com.sitta.feature.track_c.TrackCScreen
import com.sitta.feature.track_d.TrackDScreen
import org.junit.Rule
import org.junit.Test

class TrackScreensTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun trackA_showsCaptureDisabledByDefault() {
        val context = composeRule.activity
        val sessionRepository = SessionRepository(context, Gson())
        val authManager = AuthManager()
        val settingsRepository = SettingsRepository()
        val qualityAnalyzer = object : QualityAnalyzer {
            override fun analyze(bitmap: Bitmap, roi: Rect, timestampMillis: Long): QualityResult {
                return QualityResult(
                    metrics = QualityMetrics(0.0, 0.0, 0.0, 99.0),
                    passes = com.sitta.core.common.QualityMetricPasses(false, false, false, false),
                    score0To100 = 0,
                    pass = false,
                    topReason = "Blur too high",
                    timestampMillis = timestampMillis,
                )
            }
        }
        val livenessDetector = object : LivenessDetector {
            override fun evaluate(frames: List<Bitmap>, roi: Rect): LivenessResult {
                return LivenessResult("PASS", 0.1, 0.1)
            }
        }

        composeRule.setContent {
            TrackAScreen(
                sessionRepository = sessionRepository,
                authManager = authManager,
                settingsRepository = settingsRepository,
                qualityAnalyzer = qualityAnalyzer,
                livenessDetector = livenessDetector,
                onCaptureComplete = {},
                enableCamera = false,
            )
        }
        composeRule.onNodeWithText("Capture").assertIsNotEnabled()
    }

    @Test
    fun trackB_loadButtonVisible() {
        val context = composeRule.activity
        val sessionRepository = SessionRepository(context, Gson())
        val authManager = AuthManager()
        val enhancementPipeline = object : EnhancementPipeline {
            override suspend fun enhance(bitmap: Bitmap, sharpenStrength: Float): Bitmap = bitmap
        }
        composeRule.setContent {
            TrackBScreen(sessionRepository, authManager, enhancementPipeline)
        }
        composeRule.onNodeWithText("Load Last Capture").assertExists()
    }

    @Test
    fun trackC_runMatchVisible() {
        val context = composeRule.activity
        val sessionRepository = SessionRepository(context, Gson())
        val authManager = AuthManager()
        val configRepo = ConfigRepo(context, Gson())
        val matcher = object : Matcher {
            override suspend fun match(
                probe: Bitmap,
                candidates: Map<String, Bitmap>,
                threshold: Double,
            ) = com.sitta.core.domain.MatchResult(threshold, emptyList())
        }
        composeRule.setContent {
            TrackCScreen(sessionRepository, authManager, configRepo, matcher)
        }
        composeRule.onNodeWithText("Run Match").assertExists()
    }

    @Test
    fun trackD_toggleVisible() {
        val settingsRepository = SettingsRepository()
        composeRule.setContent { TrackDScreen(settingsRepository) }
        composeRule.onNodeWithText("Enable Liveness Check").assertExists()
    }
}
