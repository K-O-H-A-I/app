package com.sitta.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sitta.feature.track_a.TrackAScreen
import com.sitta.feature.track_b.TrackBScreen
import com.sitta.feature.track_c.TrackCScreen
import com.sitta.feature.track_d.TrackDScreen

@Composable
fun SittaApp(container: AppContainer) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            TrackSelectorScreen(
                authManager = container.authManager,
                onTrackA = { navController.navigate("trackA") },
                onTrackB = { navController.navigate("trackB") },
                onTrackC = { navController.navigate("trackC") },
                onTrackD = { navController.navigate("trackD") },
            )
        }
        composable("trackA") {
            TrackAScreen(
                sessionRepository = container.sessionRepository,
                authManager = container.authManager,
                settingsRepository = container.settingsRepository,
                qualityAnalyzer = container.qualityAnalyzer,
                livenessDetector = container.livenessDetector,
                onCaptureComplete = { navController.navigate("trackB") },
            )
        }
        composable("trackB") {
            TrackBScreen(
                sessionRepository = container.sessionRepository,
                authManager = container.authManager,
                enhancementPipeline = container.enhancementPipeline,
            )
        }
        composable("trackC") {
            TrackCScreen(
                sessionRepository = container.sessionRepository,
                authManager = container.authManager,
                configRepo = container.configRepo,
                matcher = container.matcher,
            )
        }
        composable("trackD") {
            TrackDScreen(settingsRepository = container.settingsRepository)
        }
    }
}
