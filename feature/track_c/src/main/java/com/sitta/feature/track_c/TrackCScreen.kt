@file:Suppress("DEPRECATION")

package com.sitta.feature.track_c

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sitta.core.common.ArtifactFilenames
import com.sitta.core.data.AuthManager
import com.sitta.core.data.ConfigRepo
import com.sitta.core.data.SessionRepository
import com.sitta.core.domain.Matcher
import com.sitta.core.vision.FingerSkeletonizer
import java.util.UUID

class TrackCViewModelFactory(
    private val sessionRepository: SessionRepository,
    private val authManager: AuthManager,
    private val configRepo: ConfigRepo,
    private val matcher: Matcher,
    private val skeletonizer: FingerSkeletonizer,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TrackCViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TrackCViewModel(sessionRepository, authManager, configRepo, matcher, skeletonizer) as T
        }
        error("Unknown ViewModel class")
    }
}

@Composable
fun TrackCScreen(
    sessionRepository: SessionRepository,
    authManager: AuthManager,
    configRepo: ConfigRepo,
    matcher: Matcher,
    skeletonizer: FingerSkeletonizer,
    onBack: () -> Unit,
    onLiveCapture: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: TrackCViewModel = viewModel(
        factory = TrackCViewModelFactory(sessionRepository, authManager, configRepo, matcher, skeletonizer),
    )
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.pendingLiveCapture) {
        if (uiState.pendingLiveCapture) {
            viewModel.resolveLiveCaptureIfPending()
        }
    }

    val probePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                if (bitmap != null) {
                    viewModel.setGalleryProbe(bitmap, it.lastPathSegment ?: "probe.png")
                }
            }
        }
    }

    val candidatePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val bitmaps = uris.mapNotNull { uri ->
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                bitmap?.let { bmp ->
                    val id = uri.lastPathSegment ?: UUID.randomUUID().toString()
                    id to bmp
                }
            }
        }
        if (bitmaps.isNotEmpty()) {
            viewModel.addCandidates(bitmaps)
        }
    }

    val bestMatch = uiState.results.maxByOrNull { it.score }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101214))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(icon = Icons.Outlined.ArrowBack, contentDescription = "Back", onClick = onBack)
            Text(text = "Matcher", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.size(42.dp))
        }

        Spacer(modifier = Modifier.height(18.dp))

        SectionTitle(text = "Probe Sample")
        Spacer(modifier = Modifier.height(10.dp))

        DarkCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Live Capture", color = Color(0xFF93A3B5), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill(
                        text = "Capture",
                        onClick = {
                            viewModel.markLiveCapturePending()
                            onLiveCapture()
                        },
                    )
                    if (uiState.liveProbeBitmap != null) {
                        ActionPill(
                            text = if (uiState.activeProbeSource == ProbeSource.LIVE) "Active" else "Use Live",
                            onClick = { viewModel.setActiveProbeSource(ProbeSource.LIVE) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            ImageBox(
                bitmap = uiState.liveProbeDisplayBitmap ?: uiState.liveProbeBitmap,
                label = "LIVE",
                badge = if (uiState.activeProbeSource == ProbeSource.LIVE) "ACTIVE" else null,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        DarkCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Gallery / Last", color = Color(0xFF93A3B5), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill(text = "Use Last", onClick = {
                        val tenantId = authManager.activeTenant.value.id
                        val session = sessionRepository.loadLastSession(tenantId) ?: return@ActionPill
                        val probeFile = sessionRepository.loadBitmap(tenantId, session.sessionId, ArtifactFilenames.ROI)
                            ?: sessionRepository.loadBitmap(tenantId, session.sessionId, ArtifactFilenames.RAW)
                            ?: return@ActionPill
                        val bitmap = BitmapFactory.decodeFile(probeFile.absolutePath)
                        if (bitmap != null) {
                            viewModel.setGalleryProbe(bitmap, probeFile.name)
                        }
                    })
                    ActionPill(text = "Pick Photo", onClick = { probePicker.launch("image/*") })
                    if (uiState.galleryProbeBitmap != null) {
                        ActionPill(
                            text = if (uiState.activeProbeSource == ProbeSource.GALLERY) "Active" else "Use Photo",
                            onClick = { viewModel.setActiveProbeSource(ProbeSource.GALLERY) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            ImageBox(
                bitmap = uiState.galleryProbeDisplayBitmap ?: uiState.galleryProbeBitmap,
                label = "GALLERY",
                badge = if (uiState.activeProbeSource == ProbeSource.GALLERY) "ACTIVE" else null,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SectionTitle(text = "Candidates (${uiState.candidates.size})")
        Spacer(modifier = Modifier.height(10.dp))
        DarkCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Gallery", color = Color(0xFF93A3B5), fontSize = 12.sp)
                ActionPill(text = "Add Candidates", onClick = { candidatePicker.launch(arrayOf("image/*")) })
            }
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(uiState.candidates) { candidate ->
                    CandidateTile(
                        id = candidate.id,
                        bitmap = candidate.displayBitmap,
                        highlighted = bestMatch?.candidateId == candidate.id,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        if (bestMatch != null) {
            DarkCard {
                Text(text = "Similarity Score", color = Color(0xFF93A3B5), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .align(Alignment.CenterHorizontally),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .background(Color(0xFF1F2937), CircleShape),
                    )
                    Text(
                        text = String.format("%.0f", bestMatch.score),
                        color = Color(0xFF34D399),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "/100",
                        color = Color(0xFF93A3B5),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 56.dp),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                MatchStatusPill(
                    text = if (bestMatch.decision == "MATCH") "Match Confirmed" else "No Match",
                    isMatch = bestMatch.decision == "MATCH",
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(text = "Threshold", color = Color(0xFF7C8A9B), fontSize = 12.sp)
                        Text(text = "${String.format("%.0f", uiState.threshold)}%", color = Color.White, fontSize = 14.sp)
                    }
                    Column {
                        Text(text = "Confidence", color = Color(0xFF7C8A9B), fontSize = 12.sp)
                        Text(
                            text = if (bestMatch.decision == "MATCH") "High" else "Low",
                            color = if (bestMatch.decision == "MATCH") Color(0xFF34D399) else Color(0xFFF87171),
                            fontSize = 14.sp,
                        )
                    }
                    Column {
                        Text(text = "Match ID", color = Color(0xFF7C8A9B), fontSize = 12.sp)
                        Text(text = bestMatch.candidateId, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                uiState.results.forEach { result ->
                    ResultRow(id = result.candidateId, score = result.score, decision = result.decision)
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Button(
            onClick = { viewModel.runMatch() },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14B8A6)),
            shape = RoundedCornerShape(18.dp),
            enabled = !uiState.isRunning,
        ) {
            Text(
                text = if (uiState.isRunning) "Running..." else "Run Comparison",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DarkCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1D21), RoundedCornerShape(24.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text.uppercase(), color = Color(0xFF6B7280), fontSize = 12.sp, letterSpacing = 1.4.sp)
}

@Composable
private fun ImageBox(bitmap: android.graphics.Bitmap?, label: String, badge: String? = null) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(Color(0xFF13161B), RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
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
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = label, modifier = Modifier.matchParentSize())
        }
        badge?.let {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .background(Color(0xFF123529), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(text = it, color = Color(0xFF34D399), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun CandidateTile(id: String, bitmap: android.graphics.Bitmap, highlighted: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(90.dp)
                .background(if (highlighted) Color(0xFF0F3F3A) else Color(0xFF13161B), RoundedCornerShape(16.dp))
                .padding(6.dp),
        ) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = id, modifier = Modifier.matchParentSize())
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = id.take(6), color = Color(0xFF9AA6B2), fontSize = 11.sp)
    }
}

@Composable
private fun MatchStatusPill(text: String, isMatch: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .background(if (isMatch) Color(0xFF163F2D) else Color(0xFF3B1E27), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .wrapContentWidth(),
        ) {
            Text(text = text, color = if (isMatch) Color(0xFF34D399) else Color(0xFFF87171), fontSize = 13.sp)
        }
    }
}

@Composable
private fun ResultRow(id: String, score: Double, decision: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1D21), RoundedCornerShape(16.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = id, color = Color.White, fontSize = 13.sp)
        Text(text = String.format("%.1f", score), color = Color(0xFF93A3B5), fontSize = 13.sp)
        Text(
            text = decision,
            color = if (decision == "MATCH") Color(0xFF34D399) else Color(0xFFF87171),
            fontSize = 13.sp,
        )
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
            .background(Color(0xFF1E242B), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = Color.White)
    }
}
