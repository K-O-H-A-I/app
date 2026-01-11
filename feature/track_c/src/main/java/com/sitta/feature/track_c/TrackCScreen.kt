package com.sitta.feature.track_c

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sitta.core.common.ArtifactFilenames
import com.sitta.core.data.AuthManager
import com.sitta.core.data.ConfigRepo
import com.sitta.core.data.SessionRepository
import com.sitta.core.domain.Matcher
import java.util.UUID

class TrackCViewModelFactory(
    private val sessionRepository: SessionRepository,
    private val authManager: AuthManager,
    private val configRepo: ConfigRepo,
    private val matcher: Matcher,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TrackCViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TrackCViewModel(sessionRepository, authManager, configRepo, matcher) as T
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
) {
    val context = LocalContext.current
    val viewModel: TrackCViewModel = viewModel(
        factory = TrackCViewModelFactory(sessionRepository, authManager, configRepo, matcher),
    )
    val uiState by viewModel.uiState.collectAsState()

    val probePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                if (bitmap != null) {
                    viewModel.setProbe(bitmap, it.lastPathSegment ?: "probe.png")
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val tenantId = authManager.activeTenant.value.id
                val session = sessionRepository.loadLastSession(tenantId) ?: return@Button
                val probeFile = sessionRepository.loadBitmap(tenantId, session.sessionId, ArtifactFilenames.ROI)
                    ?: sessionRepository.loadBitmap(tenantId, session.sessionId, ArtifactFilenames.RAW)
                    ?: return@Button
                val bitmap = BitmapFactory.decodeFile(probeFile.absolutePath)
                if (bitmap != null) {
                    viewModel.setProbe(bitmap, probeFile.name)
                }
            }) {
                Text(text = "Use Last Session")
            }
            Button(onClick = { probePicker.launch("image/*") }) {
                Text(text = "Pick Probe")
            }
            Button(onClick = { candidatePicker.launch(arrayOf("image/*")) }) {
                Text(text = "Add Candidates")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = { viewModel.runMatch() }, enabled = !uiState.isRunning) {
            Text(text = if (uiState.isRunning) "Matching..." else "Run Match")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Probe")
        if (uiState.probeBitmap != null) {
            Image(
                bitmap = uiState.probeBitmap!!.asImageBitmap(),
                contentDescription = "Probe",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
            )
        } else {
            Text(text = "No probe selected")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Candidates")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(uiState.candidates) { candidate ->
                Image(
                    bitmap = candidate.bitmap.asImageBitmap(),
                    contentDescription = candidate.id,
                    modifier = Modifier.size(96.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Results (threshold ${"%.1f".format(uiState.threshold)})")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(uiState.results) { result ->
                val badgeColor = if (result.decision == "MATCH") Color(0xFF2E7D32) else Color(0xFFD32F2F)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = result.candidateId)
                    Text(text = "${"%.2f".format(result.score)}", color = MaterialTheme.colorScheme.onSurface)
                    Text(text = result.decision, color = badgeColor)
                }
            }
        }
    }
}
