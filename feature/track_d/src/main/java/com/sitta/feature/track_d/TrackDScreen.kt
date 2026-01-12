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
import androidx.compose.material.icons.outlined.AutoAwesomeMotion
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sitta.core.data.SessionExporter
import com.sitta.core.data.SessionRepository
import com.sitta.core.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
fun TrackDScreen(
    settingsRepository: SettingsRepository,
    sessionRepository: SessionRepository,
    onBack: () -> Unit,
    onToggleTheme: () -> Unit,
    isDark: Boolean,
) {
    val viewModel: TrackDViewModel = viewModel(factory = TrackDViewModelFactory(settingsRepository))
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportMessage = remember { mutableStateOf<String?>(null) }

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

        SettingsCard(
            icon = if (isDark) Icons.Outlined.WbSunny else Icons.Outlined.Nightlight,
            iconColor = Color(0xFF14B8A6),
            title = "Dark Mode",
            subtitle = "Theme appearance",
        ) {
            Switch(
                checked = isDark,
                onCheckedChange = { onToggleTheme() },
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        SettingsCard(
            icon = Icons.Outlined.AutoAwesomeMotion,
            iconColor = Color(0xFF8B5CF6),
            title = "Auto Capture",
            subtitle = "Trigger capture when ready",
        ) {
            Switch(
                checked = uiState.autoCaptureEnabled,
                onCheckedChange = { viewModel.setAutoCaptureEnabled(it) },
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        SettingsCard(
            icon = Icons.Outlined.BugReport,
            iconColor = Color(0xFFF97316),
            title = "Debug Landmarks",
            subtitle = "Show landmark overlay in capture",
        ) {
            Switch(
                checked = uiState.debugOverlayEnabled,
                onCheckedChange = { viewModel.setDebugOverlayEnabled(it) },
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                scope.launch(Dispatchers.IO) {
                    val exporter = SessionExporter(context)
                    val result = exporter.exportAllSessions(sessionRepository.sessionsRootDir())
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        exportMessage.value = when (result) {
                            is com.sitta.core.common.AppResult.Success -> "Exported to: ${result.value.absolutePath}"
                            is com.sitta.core.common.AppResult.Error -> "Export failed: ${result.message}"
                        }
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14B8A6)),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(text = "Export Sessions", color = Color.White)
        }

        exportMessage.value?.let {
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = it, color = Color(0xFF9AA6B2), fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
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
                    .background(iconColor, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(text = subtitle, color = Color(0xFF93A3B5), fontSize = 12.sp)
            }
            trailing()
        }
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
