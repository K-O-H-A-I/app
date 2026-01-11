package com.sitta.feature.track_b

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sitta.core.data.AuthManager
import com.sitta.core.data.SessionRepository
import com.sitta.core.domain.EnhancementPipeline

class TrackBViewModelFactory(
    private val sessionRepository: SessionRepository,
    private val authManager: AuthManager,
    private val enhancementPipeline: EnhancementPipeline,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TrackBViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TrackBViewModel(sessionRepository, authManager, enhancementPipeline) as T
        }
        error("Unknown ViewModel class")
    }
}

@Composable
fun TrackBScreen(
    sessionRepository: SessionRepository,
    authManager: AuthManager,
    enhancementPipeline: EnhancementPipeline,
) {
    val viewModel: TrackBViewModel = viewModel(
        factory = TrackBViewModelFactory(sessionRepository, authManager, enhancementPipeline),
    )
    val uiState by viewModel.uiState.collectAsState()
    val configuration = LocalConfiguration.current
    val isWide = configuration.screenWidthDp > configuration.screenHeightDp

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = { viewModel.loadLastCapture() }) {
            Text(text = "Load Last Capture")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "Sharpen strength: ${"%.2f".format(uiState.sharpenStrength)}")
        Slider(
            value = uiState.sharpenStrength,
            onValueChange = { viewModel.updateSharpenStrength(it) },
            valueRange = 0f..2f,
        )
        Spacer(modifier = Modifier.height(16.dp))
        uiState.message?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
        if (isWide) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ImagePane(
                    title = "Raw",
                    bitmap = uiState.rawBitmap,
                    modifier = Modifier.weight(1f),
                )
                ImagePane(
                    title = "Enhanced",
                    bitmap = uiState.enhancedBitmap,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ImagePane(title = "Raw", bitmap = uiState.rawBitmap)
                ImagePane(title = "Enhanced", bitmap = uiState.enhancedBitmap)
            }
        }
    }
}

@Composable
private fun ImagePane(
    title: String,
    bitmap: android.graphics.Bitmap?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title)
        Spacer(modifier = Modifier.height(8.dp))
        if (bitmap == null) {
            Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                Text(text = "No image")
            }
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
            )
        }
    }
}
