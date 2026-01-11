package com.sitta.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.sitta.app.ui.theme.SittaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val container = remember { AppContainer(this) }
            LaunchedEffect(Unit) {
                container.configRepo.load()
            }
            LaunchedEffect(container.configRepo) {
                container.configRepo.config.collect { config ->
                    container.qualityAnalyzer.updateConfig(config)
                    container.livenessDetector.updateConfig(config)
                }
            }
            SittaTheme {
                SittaApp(container)
            }
        }
    }
}
