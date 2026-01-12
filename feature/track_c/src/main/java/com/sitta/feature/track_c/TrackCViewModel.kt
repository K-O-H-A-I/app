package com.sitta.feature.track_c

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sitta.core.common.AppResult
import com.sitta.core.common.ArtifactFilenames
import com.sitta.core.common.MatchCandidate
import com.sitta.core.common.MatchReport
import com.sitta.core.data.AuthManager
import com.sitta.core.data.ConfigRepo
import com.sitta.core.data.SessionRepository
import com.sitta.core.domain.MatchCandidateResult
import com.sitta.core.domain.Matcher
import com.sitta.core.vision.FingerSkeletonizer
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
    private val skeletonizer: FingerSkeletonizer,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TrackCState())
    val uiState: StateFlow<TrackCState> = _uiState.asStateFlow()

    fun setGalleryProbe(bitmap: Bitmap, filename: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val display = skeletonizer.skeletonize(bitmap)
            _uiState.value = _uiState.value.copy(
                galleryProbeBitmap = bitmap,
                galleryProbeDisplayBitmap = display,
                galleryProbeFilename = filename,
                activeProbeSource = ProbeSource.GALLERY,
            )
        }
    }

    fun setLiveProbe(bitmap: Bitmap, filename: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val display = skeletonizer.skeletonize(bitmap)
            _uiState.value = _uiState.value.copy(
                liveProbeBitmap = bitmap,
                liveProbeDisplayBitmap = display,
                liveProbeFilename = filename,
                activeProbeSource = ProbeSource.LIVE,
            )
        }
    }

    fun setActiveProbeSource(source: ProbeSource) {
        _uiState.value = _uiState.value.copy(activeProbeSource = source)
    }

    fun markLiveCapturePending() {
        _uiState.value = _uiState.value.copy(pendingLiveCapture = true)
    }

    fun resolveLiveCaptureIfPending() {
        if (!_uiState.value.pendingLiveCapture) return
        viewModelScope.launch(Dispatchers.IO) {
            val tenantId = authManager.activeTenant.value.id
            val session = sessionRepository.loadLastSession(tenantId)
                ?: return@launch
            val probeFile = sessionRepository.loadBitmap(tenantId, session.sessionId, ArtifactFilenames.ROI)
                ?: sessionRepository.loadBitmap(tenantId, session.sessionId, ArtifactFilenames.RAW)
                ?: return@launch
            val bitmap = BitmapFactory.decodeFile(probeFile.absolutePath)
            if (bitmap != null) {
                setLiveProbe(bitmap, probeFile.name)
            }
            _uiState.value = _uiState.value.copy(pendingLiveCapture = false)
        }
    }

    fun addCandidates(bitmaps: List<Pair<String, Bitmap>>) {
        viewModelScope.launch(Dispatchers.Default) {
            val newItems = bitmaps.map { (id, bmp) ->
                val display = skeletonizer.skeletonize(bmp)
                CandidateItem(id, bmp, display)
            }
            val updated = _uiState.value.candidates + newItems
            _uiState.value = _uiState.value.copy(candidates = updated)
        }
    }

    fun clearCandidates() {
        _uiState.value = _uiState.value.copy(candidates = emptyList(), results = emptyList())
    }

    fun runMatch() {
        val (probe, probeFilename) = when (_uiState.value.activeProbeSource) {
            ProbeSource.LIVE -> _uiState.value.liveProbeBitmap to _uiState.value.liveProbeFilename
            ProbeSource.GALLERY -> _uiState.value.galleryProbeBitmap to _uiState.value.galleryProbeFilename
        }
        val probeBitmap = probe ?: return
        val candidates = _uiState.value.candidates
        if (candidates.isEmpty()) return

        viewModelScope.launch(Dispatchers.Default) {
            _uiState.value = _uiState.value.copy(isRunning = true)
            val threshold = configRepo.current().matchThreshold
            val candidateMap = candidates.associate { it.id to it.bitmap }
            val result = matcher.match(probeBitmap, candidateMap, threshold)
            _uiState.value = _uiState.value.copy(
                results = result.candidates,
                threshold = threshold,
                isRunning = false,
            )
            saveMatchReport(result.candidates, threshold, probeFilename)
        }
    }

    private suspend fun saveMatchReport(
        results: List<MatchCandidateResult>,
        threshold: Double,
        probeFilename: String?,
    ) {
        val tenantId = authManager.activeTenant.value.id
        val session = sessionRepository.loadLastSession(tenantId)
            ?: when (val created = sessionRepository.createSession(tenantId)) {
                is AppResult.Success -> created.value
                is AppResult.Error -> return
            }
        val report = MatchReport(
            sessionId = session.sessionId,
            probeFilename = probeFilename ?: "probe.png",
            thresholdUsed = threshold,
            candidates = results.map {
                MatchCandidate(candidateId = it.candidateId, score = it.score, decision = it.decision)
            },
        )
        sessionRepository.saveJson(session, com.sitta.core.common.ArtifactFilenames.MATCH, report)
    }
}

data class CandidateItem(val id: String, val bitmap: Bitmap, val displayBitmap: Bitmap)

data class TrackCState(
    val liveProbeBitmap: Bitmap? = null,
    val liveProbeDisplayBitmap: Bitmap? = null,
    val liveProbeFilename: String? = null,
    val galleryProbeBitmap: Bitmap? = null,
    val galleryProbeDisplayBitmap: Bitmap? = null,
    val galleryProbeFilename: String? = null,
    val activeProbeSource: ProbeSource = ProbeSource.GALLERY,
    val candidates: List<CandidateItem> = emptyList(),
    val results: List<MatchCandidateResult> = emptyList(),
    val threshold: Double = 40.0,
    val isRunning: Boolean = false,
    val pendingLiveCapture: Boolean = false,
)

enum class ProbeSource { LIVE, GALLERY }
