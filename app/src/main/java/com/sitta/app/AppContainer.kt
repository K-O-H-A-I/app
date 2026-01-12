package com.sitta.app

import android.content.Context
import com.google.gson.Gson
import com.sitta.core.common.DefaultConfig
import com.sitta.core.data.AuthManager
import com.sitta.core.data.ConfigRepo
import com.sitta.core.data.SessionRepository
import com.sitta.core.data.SettingsRepository
import com.sitta.core.vision.MotionLivenessDetector
import com.sitta.core.vision.FingerDetector
import com.sitta.core.vision.FingerMasker
import com.sitta.core.vision.FingerSceneAnalyzer
import com.sitta.core.vision.FingerSkeletonizer
import com.sitta.core.vision.OpenCvEnhancementPipeline
import com.sitta.core.vision.OpenCvQualityAnalyzer
import com.sitta.core.vision.SourceAfisMatcher

class AppContainer(context: Context) {
    private val gson = Gson()

    val configRepo = ConfigRepo(context, gson)
    val sessionRepository = SessionRepository(context, gson)
    val authManager = AuthManager()
    val settingsRepository = SettingsRepository()

    val qualityAnalyzer = OpenCvQualityAnalyzer(DefaultConfig.value)
    val enhancementPipeline = OpenCvEnhancementPipeline()
    val matcher = SourceAfisMatcher()
    val livenessDetector = MotionLivenessDetector(DefaultConfig.value)
    val fingerDetector = FingerDetector(context)
    val fingerSceneAnalyzer = FingerSceneAnalyzer()
    val fingerMasker = FingerMasker()
    val fingerSkeletonizer = FingerSkeletonizer()
    val themeManager = ThemeManager()
}
