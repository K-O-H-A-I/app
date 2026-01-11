@file:Suppress("DEPRECATION")

package com.sitta.feature.track_d

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
fun TrackDScreen(settingsRepository: SettingsRepository, onBack: () -> Unit) {
    val viewModel: TrackDViewModel = viewModel(factory = TrackDViewModelFactory(settingsRepository))
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101214))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(icon = Icons.Outlined.ArrowBack, contentDescription = "Back", onClick = onBack)
            Text(text = "Settings", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.size(42.dp))
        }

        Spacer(modifier = Modifier.height(18.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1D21), RoundedCornerShape(24.dp))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFF7C3AED), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MonitorHeart,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Liveness Detection", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(text = "Anti-spoofing protection", color = Color(0xFF93A3B5), fontSize = 12.sp)
                }
                Switch(
                    checked = uiState.livenessEnabled,
                    onCheckedChange = { viewModel.setLivenessEnabled(it) },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "When enabled, capture requires motion variance within a mid-band to prevent static spoofing.",
            color = Color(0xFF7C8A9B),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(Color(0xFF1E242B), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = Color.White)
    }
}
