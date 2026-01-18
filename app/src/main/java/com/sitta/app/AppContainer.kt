package com.sitta.app

import android.content.Context
import com.google.gson.Gson
import com.sitta.core.data.AuthManager
import com.sitta.core.data.ConfigRepo
import com.sitta.core.data.SessionRepository
import com.sitta.core.data.SettingsRepository
import com.sitta.core.vision.MotionLivenessDetector
import com.sitta.core.vision.FingerDetector
import com.sitta.core.vision.FingerRidgeExtractor
import com.sitta.core.vision.FingerSceneAnalyzer
import com.sitta.core.vision.FingerSkeletonizer
import com.sitta.core.vision.NormalModeEnhancementPipeline
import com.sitta.core.vision.NormalModeSegmentation
import com.sitta.core.vision.OpenCvQualityAnalyzer
import com.sitta.core.vision.OpenCvUtils
import com.sitta.core.vision.HybridFingerprintMatcher
import com.sitta.core.vision.NormalModeRidgeExtractor

class AppContainer(context: Context) {
    private val gson = Gson()

    val configRepo = ConfigRepo(context, gson)
    val sessionRepository = SessionRepository(context, gson)
    val authManager = AuthManager()
    val settingsRepository = SettingsRepository()

    init {
        kotlinx.coroutines.runBlocking {
            configRepo.load()
        }
        OpenCvUtils.ensureLoadedOrFalse()
    }

    private val config = configRepo.current()
    val qualityAnalyzer = OpenCvQualityAnalyzer(config)
    val enhancementPipeline = NormalModeEnhancementPipeline()
    val matcher = HybridFingerprintMatcher()
    val livenessDetector = MotionLivenessDetector(config)
    val fingerDetector = FingerDetector(context)
    val fingerSceneAnalyzer = FingerSceneAnalyzer()
    val segmentation = NormalModeSegmentation()
    val fingerRidgeExtractor = NormalModeRidgeExtractor()
    val fingerSkeletonizer = FingerSkeletonizer()
    val themeManager = ThemeManager()
}
