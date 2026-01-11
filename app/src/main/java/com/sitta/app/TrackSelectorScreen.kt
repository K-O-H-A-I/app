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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import com.sitta.core.data.AuthManager

@Composable
fun TrackSelectorScreen(
    authManager: AuthManager,
    onTrackA: () -> Unit,
    onTrackB: () -> Unit,
    onTrackC: () -> Unit,
    onTrackD: () -> Unit,
    onToggleTheme: () -> Unit,
    isDark: Boolean,
) {
    val activeTenant by authManager.activeTenant.collectAsState()
    val background = if (isDark) Color(0xFF121212) else Color(0xFFF7F7F7)
    val surface = if (isDark) Color(0xFF1E1E1E) else Color.White

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(18.dp))
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
                        text = "SYSTEM STATUS",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF6B7280),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFF22C55E), CircleShape),
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = "All Systems Operational",
                            color = if (isDark) Color(0xFF34D399) else Color(0xFF16A34A),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "127",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (isDark) Color.White else Color(0xFF111827),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Scans Today",
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

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(cards) { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = surface),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
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
                ActivityRow("Fingerprint captured", "2 min ago")
                ActivityRow("Match completed", "15 min ago")
                ActivityRow("Enhancement processed", "1 hour ago")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

private data class TrackCardItem(
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val iconColor: Color,
    val onClick: () -> Unit,
)

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
        Text(text = ">", color = Color(0xFF6B7280))
    }
}
