@file:Suppress("DEPRECATION")

package com.sitta.feature.track_b

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sitta.core.data.AuthManager
import com.sitta.core.data.SessionRepository
import com.sitta.core.domain.EnhancementPipeline
import com.sitta.core.domain.QualityAnalyzer
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class TrackBViewModelFactory(
    private val sessionRepository: SessionRepository,
    private val authManager: AuthManager,
    private val enhancementPipeline: EnhancementPipeline,
    private val qualityAnalyzer: QualityAnalyzer,
    private val ridgeExtractor: com.sitta.core.vision.NormalModeRidgeExtractor,
    private val skeletonizer: com.sitta.core.vision.FingerSkeletonizer,
    private val segmentationPipeline: com.sitta.core.vision.NormalModeSegmentation,
    private val appContext: android.content.Context,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TrackBViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TrackBViewModel(
                sessionRepository,
                authManager,
                enhancementPipeline,
                qualityAnalyzer,
                ridgeExtractor,
                skeletonizer,
                segmentationPipeline,
                appContext,
            ) as T
        }
        error("Unknown ViewModel class")
    }
}

@Composable
fun TrackBScreen(
    sessionRepository: SessionRepository,
    authManager: AuthManager,
    enhancementPipeline: EnhancementPipeline,
    qualityAnalyzer: QualityAnalyzer,
    ridgeExtractor: com.sitta.core.vision.NormalModeRidgeExtractor,
    skeletonizer: com.sitta.core.vision.FingerSkeletonizer,
    segmentationPipeline: com.sitta.core.vision.NormalModeSegmentation,
    onBack: () -> Unit,
) {
    val viewModel: TrackBViewModel = viewModel(
        factory = TrackBViewModelFactory(
            sessionRepository,
            authManager,
            enhancementPipeline,
            qualityAnalyzer,
            ridgeExtractor,
            skeletonizer,
            segmentationPipeline,
            LocalContext.current.applicationContext,
        ),
    )
    val uiState by viewModel.uiState.collectAsState()
    var showExportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadLastCapture()
    }

    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(icon = Icons.Outlined.ArrowBack, contentDescription = "Back", onClick = onBack)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Enhancement", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(text = "Complete", color = Color(0xFF38D39F), fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.size(42.dp))
        }

        Spacer(modifier = Modifier.height(18.dp))

        SectionTitle(text = "Comparison", trailingLabel = "Complete")
        Spacer(modifier = Modifier.height(10.dp))

        DarkCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Before", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                ActionPill(text = "Load Last Capture", onClick = { viewModel.loadLastCapture() })
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ImagePane(title = "RAW", bitmap = uiState.rawBitmap, modifier = Modifier.weight(1f))
                ImagePane(title = "ENHANCED", bitmap = uiState.enhancedBitmap, modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(12.dp))
            ImagePane(
                title = "SKELETON",
                bitmap = uiState.skeletonBitmap ?: uiState.ridgeBitmap,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SectionTitle(text = "Processing Steps")
        Spacer(modifier = Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            uiState.steps.forEach { step ->
                val duration = if (step.durationMs <= 0L) "--" else "${step.durationMs}ms"
                ProcessingItem(step.name, duration)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val message = uiState.message
        val errorMessage = message?.takeIf {
            it.contains("failed", ignoreCase = true) || it.contains("not found", ignoreCase = true)
        }
        if (errorMessage != null) {
            AlertDialog(
                onDismissRequest = { viewModel.clearMessage() },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearMessage() }) {
                        Text(text = "OK", color = Color(0xFF14B8A6))
                    }
                },
                title = { Text(text = "Enhancement Error", color = MaterialTheme.colorScheme.onSurface) },
                text = { Text(text = errorMessage, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                containerColor = MaterialTheme.colorScheme.surface,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { showExportDialog = true },
                modifier = Modifier.weight(1f).height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14B8A6)),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(text = "Save in ISO Format", color = MaterialTheme.colorScheme.onPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (showExportDialog) {
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text(text = "Export format", color = MaterialTheme.colorScheme.onSurface) },
                text = { Text(text = "Choose a format to export.", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                confirmButton = {
                    TextButton(onClick = {
                        showExportDialog = false
                        viewModel.exportEnhanced(ExportFormat.PNG)
                    }) {
                        Text(text = "PNG", color = Color(0xFF14B8A6))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showExportDialog = false
                        viewModel.exportEnhanced(ExportFormat.TIFF)
                    }) {
                        Text(text = "TIFF", color = Color(0xFF14B8A6))
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
            )
        }
    }
}

@Composable
private fun DarkCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun SectionTitle(text: String, trailingLabel: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, letterSpacing = 1.4.sp)
        trailingLabel?.let {
            Box(
                modifier = Modifier
                    .background(Color(0xFF123529), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(text = it, color = Color(0xFF34D399), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ImagePane(title: String, bitmap: android.graphics.Bitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(160.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)),
    ) {
        if (bitmap == null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF0F172A), Color(0xFF0B1F22)),
                        ),
                        RoundedCornerShape(20.dp),
                    ),
            )
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = title,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(if (title == "RAW") Color(0xFF3B1E27) else Color(0xFF123529), RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(text = title, color = if (title == "RAW") Color(0xFFF87171) else Color(0xFF34D399), fontSize = 11.sp)
        }
    }
}

@Composable
private fun ProcessingItem(title: String, time: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(0xFF14B8A6), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Text(text = time, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier, accent: Color = Color(0xFF22C55E)) {
    Column(
        modifier = modifier
            .background(Color(0xFF1A2530), RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Text(text = title, color = Color(0xFF93A3B5), fontSize = 12.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = value, color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ActionPill(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0xFF0F2F2A), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = text, color = Color(0xFF34D399), fontSize = 11.sp)
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
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = message, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp)
    }
}
