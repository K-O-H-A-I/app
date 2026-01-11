package com.sitta.feature.track_c

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sitta.core.common.AppResult
import com.sitta.core.common.MatchCandidate
import com.sitta.core.common.MatchReport
import com.sitta.core.data.AuthManager
import com.sitta.core.data.ConfigRepo
import com.sitta.core.data.SessionRepository
import com.sitta.core.domain.MatchCandidateResult
import com.sitta.core.domain.Matcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TrackCViewModel(
    private val sessionRepository: SessionRepository,
    private val authManager: AuthManager,
    private val configRepo: ConfigRepo,
    private val matcher: Matcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TrackCState())
    val uiState: StateFlow<TrackCState> = _uiState.asStateFlow()

    fun setProbe(bitmap: Bitmap, filename: String) {
        _uiState.value = _uiState.value.copy(probeBitmap = bitmap, probeFilename = filename)
    }

    fun addCandidates(bitmaps: List<Pair<String, Bitmap>>) {
        val updated = _uiState.value.candidates + bitmaps.map { CandidateItem(it.first, it.second) }
        _uiState.value = _uiState.value.copy(candidates = updated)
    }

    fun clearCandidates() {
        _uiState.value = _uiState.value.copy(candidates = emptyList(), results = emptyList())
    }

    fun runMatch() {
        val probe = _uiState.value.probeBitmap ?: return
        val candidates = _uiState.value.candidates
        if (candidates.isEmpty()) return

        viewModelScope.launch(Dispatchers.Default) {
            _uiState.value = _uiState.value.copy(isRunning = true)
            val threshold = configRepo.current().matchThreshold
            val candidateMap = candidates.associate { it.id to it.bitmap }
            val result = matcher.match(probe, candidateMap, threshold)
            _uiState.value = _uiState.value.copy(
                results = result.candidates,
                threshold = threshold,
                isRunning = false,
            )
            saveMatchReport(result.candidates, threshold)
        }
    }

    private suspend fun saveMatchReport(results: List<MatchCandidateResult>, threshold: Double) {
        val tenantId = authManager.activeTenant.value.id
        val session = sessionRepository.loadLastSession(tenantId)
            ?: when (val created = sessionRepository.createSession(tenantId)) {
                is AppResult.Success -> created.value
                is AppResult.Error -> return
            }
        val report = MatchReport(
            sessionId = session.sessionId,
            probeFilename = _uiState.value.probeFilename ?: "probe.png",
            thresholdUsed = threshold,
            candidates = results.map {
                MatchCandidate(candidateId = it.candidateId, score = it.score, decision = it.decision)
            },
        )
        sessionRepository.saveJson(session, com.sitta.core.common.ArtifactFilenames.MATCH, report)
    }
}

data class CandidateItem(val id: String, val bitmap: Bitmap)

data class TrackCState(
    val probeBitmap: Bitmap? = null,
    val probeFilename: String? = null,
    val candidates: List<CandidateItem> = emptyList(),
    val results: List<MatchCandidateResult> = emptyList(),
    val threshold: Double = 40.0,
    val isRunning: Boolean = false,
)
