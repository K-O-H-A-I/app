package com.sitta.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
                sessionRepository = container.sessionRepository,
                settingsRepository = container.settingsRepository,
                onTrackA = { navController.navigate("trackA") },
                onTrackB = { navController.navigate("trackB") },
                onTrackC = { navController.navigate("trackC") },
                onTrackD = { navController.navigate("trackD") },
                onToggleTheme = { container.themeManager.toggle() },
                isDark = container.themeManager.isDark.value,
            )
        }
        composable(
            "trackA?origin={origin}",
            arguments = listOf(navArgument("origin") { defaultValue = "enhance" }),
        ) { entry ->
            val origin = entry.arguments?.getString("origin") ?: "enhance"
            TrackAScreen(
                sessionRepository = container.sessionRepository,
                authManager = container.authManager,
                settingsRepository = container.settingsRepository,
                qualityAnalyzer = container.qualityAnalyzer,
                livenessDetector = container.livenessDetector,
                fingerDetector = container.fingerDetector,
                fingerSceneAnalyzer = container.fingerSceneAnalyzer,
                fingerMasker = container.fingerMasker,
                onCaptureComplete = {
                    if (origin == "match") {
                        navController.popBackStack("trackC", inclusive = false)
                    } else {
                        navController.navigate("trackB")
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable("trackB") {
            TrackBScreen(
                sessionRepository = container.sessionRepository,
                authManager = container.authManager,
                enhancementPipeline = container.enhancementPipeline,
                qualityAnalyzer = container.qualityAnalyzer,
                ridgeExtractor = container.fingerRidgeExtractor,
                skeletonizer = container.fingerSkeletonizer,
                onBack = { navController.popBackStack("home", inclusive = false) },
            )
        }
        composable("trackC") {
            TrackCScreen(
                sessionRepository = container.sessionRepository,
                authManager = container.authManager,
                configRepo = container.configRepo,
                matcher = container.matcher,
                skeletonizer = container.fingerSkeletonizer,
                onBack = { navController.popBackStack() },
                onLiveCapture = { navController.navigate("trackA?origin=match") },
            )
        }
        composable("trackD") {
            TrackDScreen(
                settingsRepository = container.settingsRepository,
                sessionRepository = container.sessionRepository,
                onBack = { navController.popBackStack() },
                onToggleTheme = { container.themeManager.toggle() },
                isDark = container.themeManager.isDark.value,
            )
        }
    }
}
