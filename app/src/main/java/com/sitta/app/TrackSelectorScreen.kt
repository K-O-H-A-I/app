package com.sitta.app

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sitta.core.data.AuthManager

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackSelectorScreen(
    authManager: AuthManager,
    onTrackA: () -> Unit,
    onTrackB: () -> Unit,
    onTrackC: () -> Unit,
    onTrackD: () -> Unit,
) {
    val activeTenant by authManager.activeTenant.collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Tenant: ${activeTenant.displayName}")
            Spacer(modifier = Modifier.weight(1f))
            Box {
                Text(
                    text = "Switch",
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clickable { menuExpanded = true },
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    authManager.allTenants().forEach { tenant ->
                        DropdownMenuItem(
                            text = { Text(text = tenant.displayName) },
                            onClick = {
                                authManager.switchTenant(tenant.id)
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Select Track", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        val items = listOf(
            TrackItem("Capture", "Track A", onTrackA, Color(0xFFE3F2FD)),
            TrackItem("Enhance", "Track B", onTrackB, Color(0xFFFFF3E0)),
            TrackItem("Match", "Track C", onTrackC, Color(0xFFE8F5E9)),
            TrackItem("Settings", "Track D", onTrackD, Color(0xFFF3E5F5)),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items) { item ->
                TrackCard(item = item)
            }
        }
    }
}

private data class TrackItem(
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
    val color: Color,
)

@Composable
private fun TrackCard(item: TrackItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clickable { item.onClick() },
        colors = CardDefaults.cardColors(containerColor = item.color),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = item.title, style = MaterialTheme.typography.titleLarge)
            Text(text = item.subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
