@file:Suppress("DEPRECATION")

package com.sitta.app

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sitta.core.common.ArtifactFilenames
import com.sitta.core.common.SessionInfo
import com.sitta.core.data.AuthManager
import com.sitta.core.data.SettingsRepository
import com.sitta.core.data.SessionRepository
import java.time.Instant
import java.time.ZoneId

@Composable
fun TrackSelectorScreen(
    authManager: AuthManager,
    sessionRepository: SessionRepository,
    settingsRepository: SettingsRepository,
    onTrackA: () -> Unit,
    onTrackB: () -> Unit,
    onTrackC: () -> Unit,
    onTrackD: () -> Unit,
    onToggleTheme: () -> Unit,
    isDark: Boolean,
) {
    val activeTenant by authManager.activeTenant.collectAsState()
    val livenessEnabled by settingsRepository.livenessEnabled.collectAsState()
    val background = if (isDark) Color(0xFF121212) else Color(0xFFF7F7F7)
    val surface = if (isDark) Color(0xFF1E1E1E) else Color.White
    val sessions = sessionRepository.listSessions(activeTenant.id)
    val recentActivity = buildRecentActivity(sessionRepository, sessions)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        brush = Brush.linearGradient(listOf(Color(0xFF1FD3B4), Color(0xFF18B89B))),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Outlined.Fingerprint,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(modifier = Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SITAA Contactless",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isDark) Color.White else Color(0xFF111111),
                )
                Text(
                    text = "Biometric Security Platform • ${activeTenant.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280),
                )
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(if (isDark) Color(0xFF1E1E1E) else Color.White, CircleShape)
                    .clickable { onToggleTheme() },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = if (isDark) Icons.Outlined.WbSunny else Icons.Outlined.Nightlight,
                    contentDescription = null,
                    tint = if (isDark) Color(0xFFFBBF24) else Color(0xFF374151),
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = surface),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SESSION SUMMARY",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF6B7280),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Last session: ${sessions.firstOrNull()?.sessionId?.take(6) ?: "--"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "MAIN FUNCTIONS",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF6B7280),
        )
        Spacer(modifier = Modifier.height(12.dp))

        val cards = listOf(
            TrackCardItem("New Capture", "Contactless acquisition", Icons.Outlined.Fingerprint, Color(0xFF1FD3B4), onTrackA),
            TrackCardItem("Enhance", "Image processing", Icons.Outlined.AutoFixHigh, Color(0xFF8B5CF6), onTrackB),
            TrackCardItem("Match", "Identity verification", Icons.Outlined.CompareArrows, Color(0xFF3B82F6), onTrackC),
            TrackCardItem("Settings", "System configuration", Icons.Outlined.Settings, Color(0xFF6B7280), onTrackD),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            TrackCard(item = cards[0], surface = surface, isDark = isDark, modifier = Modifier.weight(1f))
            TrackCard(item = cards[1], surface = surface, isDark = isDark, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            TrackCard(item = cards[2], surface = surface, isDark = isDark, modifier = Modifier.weight(1f))
            TrackCard(item = cards[3], surface = surface, isDark = isDark, modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = surface),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(Color(0xFF7C3AED), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Outlined.MonitorHeart,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Liveness Detection",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isDark) Color.White else Color(0xFF111111),
                    )
                    Text(
                        text = "Anti-spoofing protection",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280),
                    )
                }
                Switch(
                    checked = livenessEnabled,
                    onCheckedChange = { settingsRepository.setLivenessEnabled(it) },
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "RECENT ACTIVITY",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF6B7280),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = surface),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (recentActivity.isEmpty()) {
                    ActivityRow("No recent activity", "--")
                } else {
                    recentActivity.forEach { item ->
                        ActivityRow(item.title, item.timeLabel)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

private data class TrackCardItem(
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val iconColor: Color,
    val onClick: () -> Unit,
)

private data class ActivityItem(
    val title: String,
    val timeLabel: String,
)

@Composable
private fun TrackCard(item: TrackCardItem, surface: Color, isDark: Boolean, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = surface),
        shape = RoundedCornerShape(28.dp),
        modifier = modifier
            .height(180.dp)
            .clickable { item.onClick() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(item.iconColor, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isDark) Color.White else Color(0xFF111111),
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Open  >",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF1FD3B4),
                )
            }
        }
    }
}

@Composable
private fun ActivityRow(title: String, time: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(Color(0xFF22C55E), CircleShape),
        )
        Spacer(modifier = Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(text = time, style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
        }
    }
}

private fun buildRecentActivity(
    sessionRepository: SessionRepository,
    sessions: List<SessionInfo>,
): List<ActivityItem> {
    val items = mutableListOf<ActivityItemWithTime>()
    val zone = ZoneId.systemDefault()
    sessions.forEach { session ->
        val quality = sessionRepository.sessionArtifactPath(session, ArtifactFilenames.QUALITY)
        if (quality.exists()) {
            items.add(ActivityItemWithTime("Fingerprint captured", quality.lastModified()))
        }
        val enhanced = sessionRepository.sessionArtifactPath(session, ArtifactFilenames.ENHANCED)
        if (enhanced.exists()) {
            items.add(ActivityItemWithTime("Enhancement processed", enhanced.lastModified()))
        }
        val match = sessionRepository.sessionArtifactPath(session, ArtifactFilenames.MATCH)
        if (match.exists()) {
            items.add(ActivityItemWithTime("Match completed", match.lastModified()))
        }
    }
    return items.sortedByDescending { it.timestamp }.take(3).map {
        ActivityItem(it.title, formatRelative(it.timestamp, zone))
    }
}

private data class ActivityItemWithTime(
    val title: String,
    val timestamp: Long,
)

private fun formatRelative(timestamp: Long, zone: ZoneId): String {
    val now = Instant.now().toEpochMilli()
    val deltaMinutes = ((now - timestamp) / 60000).coerceAtLeast(0)
    return when {
        deltaMinutes < 1 -> "just now"
        deltaMinutes < 60 -> "${deltaMinutes} min ago"
        deltaMinutes < 24 * 60 -> "${deltaMinutes / 60} hour ago"
        else -> {
            val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
            date.toString()
        }
    }
}
