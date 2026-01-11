package com.sitta.feature.track_a

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sitta.core.common.AppResult
import com.sitta.core.common.ArtifactFilenames
import com.sitta.core.common.LivenessReport
import com.sitta.core.common.QualityReport
import com.sitta.core.data.AuthManager
import com.sitta.core.data.SessionRepository
import com.sitta.core.data.SettingsRepository
import com.sitta.core.domain.LivenessDetector
import com.sitta.core.domain.LivenessResult
import com.sitta.core.domain.QualityAnalyzer
import com.sitta.core.domain.QualityResult
import com.sitta.core.domain.QualityStabilityGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque

class TrackAViewModel(
    private val sessionRepository: SessionRepository,
    private val authManager: AuthManager,
    private val settingsRepository: SettingsRepository,
    private val qualityAnalyzer: QualityAnalyzer,
    private val livenessDetector: LivenessDetector,
) : ViewModel() {
    private val gate = QualityStabilityGate(500L)
    private val livenessFrames = ArrayDeque<Bitmap>()

    private val _uiState = MutableStateFlow(TrackAUiState())
    val uiState: StateFlow<TrackAUiState> = _uiState.asStateFlow()

    private var latestFrame: Bitmap? = null
    private var latestRoi: Rect? = null
    private var livenessEnabled: Boolean = false
    private var lastLivenessResult: LivenessResult? = null

    init {
        viewModelScope.launch {
            settingsRepository.livenessEnabled.collect { enabled ->
                livenessEnabled = enabled
                if (!enabled) {
                    lastLivenessResult = null
                    livenessFrames.clear()
                }
            }
        }
    }

    fun onFrame(bitmap: Bitmap, roi: Rect, timestampMillis: Long) {
        latestFrame = bitmap
        latestRoi = roi
        viewModelScope.launch(Dispatchers.Default) {
            val result = qualityAnalyzer.analyze(bitmap, roi, timestampMillis)
            val livenessPass = evaluateLivenessIfNeeded(bitmap, roi)
            val combinedPass = result.pass && livenessPass
            val stablePass = gate.update(combinedPass, timestampMillis)
            _uiState.value = _uiState.value.copy(
                qualityResult = result,
                stablePass = stablePass,
                captureEnabled = stablePass,
                livenessResult = lastLivenessResult,
                message = if (livenessEnabled && !livenessPass) "Liveness failed" else null,
            )
        }
    }

    private fun evaluateLivenessIfNeeded(bitmap: Bitmap, roi: Rect): Boolean {
        if (!livenessEnabled) return true
        livenessFrames.addLast(bitmap)
        if (livenessFrames.size < 5) {
            return lastLivenessResult?.decision == "PASS"
        }
        val frames = livenessFrames.toList()
        livenessFrames.clear()
        val result = livenessDetector.evaluate(frames, roi)
        lastLivenessResult = result
        return result.decision == "PASS"
    }

    fun capture() {
        val frame = latestFrame ?: return
        val roi = latestRoi ?: return
        val qualityResult = _uiState.value.qualityResult ?: return
        val tenantId = authManager.activeTenant.value.id

        viewModelScope.launch(Dispatchers.IO) {
            val session = when (val created = sessionRepository.createSession(tenantId)) {
                is AppResult.Success -> created.value
                is AppResult.Error -> return@launch
            }
            val rawResult = sessionRepository.saveBitmap(session, ArtifactFilenames.RAW, frame)
            if (rawResult is AppResult.Error) return@launch

            val roiBitmap = Bitmap.createBitmap(
                frame,
                roi.left,
                roi.top,
                roi.width(),
                roi.height(),
            )
            val roiResult = sessionRepository.saveBitmap(session, ArtifactFilenames.ROI, roiBitmap)
            if (roiResult is AppResult.Error) return@launch

            val qualityReport = QualityReport(
                sessionId = session.sessionId,
                timestamp = System.currentTimeMillis(),
                score0To100 = qualityResult.score0To100,
                pass = qualityResult.pass,
                metrics = qualityResult.metrics,
                topReason = qualityResult.topReason,
            )
            sessionRepository.saveJson(session, ArtifactFilenames.QUALITY, qualityReport)

            val liveness = lastLivenessResult
            if (livenessEnabled && liveness != null) {
                val livenessReport = LivenessReport(
                    sessionId = session.sessionId,
                    decision = liveness.decision,
                    score = liveness.score,
                    heuristicUsed = "Motion_Centroid",
                )
                sessionRepository.saveJson(session, ArtifactFilenames.LIVENESS, livenessReport)
            }

            _uiState.value = _uiState.value.copy(lastSessionId = session.sessionId)
        }
    }
}

data class TrackAUiState(
    val qualityResult: QualityResult? = null,
    val stablePass: Boolean = false,
    val captureEnabled: Boolean = false,
    val livenessResult: LivenessResult? = null,
    val message: String? = null,
    val lastSessionId: String? = null,
)
