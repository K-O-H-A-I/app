package com.sitta.feature.track_d

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sitta.core.data.SettingsRepository

class TrackDViewModelFactory(private val settingsRepository: SettingsRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TrackDViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TrackDViewModel(settingsRepository) as T
        }
        error("Unknown ViewModel class")
    }
}

@Composable
fun TrackDScreen(settingsRepository: SettingsRepository) {
    val viewModel: TrackDViewModel = viewModel(factory = TrackDViewModelFactory(settingsRepository))
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Liveness Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Enable Liveness Check", modifier = Modifier.weight(1f))
            Switch(
                checked = uiState.livenessEnabled,
                onCheckedChange = { viewModel.setLivenessEnabled(it) },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "When enabled, capture requires motion variance within a mid-band to prevent static spoofing.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
