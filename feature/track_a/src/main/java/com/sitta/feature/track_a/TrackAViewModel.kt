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
import com.sitta.core.vision.FingerDetectionResult
import com.sitta.core.vision.FingerDetector
import com.sitta.core.vision.FingerMasker
import com.sitta.core.vision.FingerSceneAnalyzer
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
    private val fingerDetector: FingerDetector,
    private val fingerSceneAnalyzer: FingerSceneAnalyzer,
    private val fingerMasker: FingerMasker,
) : ViewModel() {
    private val gate = QualityStabilityGate(500L)
    private val detectionGate = QualityStabilityGate(300L)
    private val livenessFrames = ArrayDeque<Bitmap>()

    private val _uiState = MutableStateFlow(TrackAUiState())
    val uiState: StateFlow<TrackAUiState> = _uiState.asStateFlow()

    private var latestFrame: Bitmap? = null
    private var latestRoi: Rect? = null
    private var latestDetection: FingerDetectionResult? = null
    private var lastDetectionMillis: Long = 0L
    private var livenessEnabled: Boolean = false
    private var lastLivenessResult: LivenessResult? = null

    init {
        fingerDetector.initialize()
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
        viewModelScope.launch(Dispatchers.Default) {
            val detection = runDetection(bitmap, timestampMillis)
            latestRoi = roi
            latestDetection = detection

            val effectiveRoi = roi
            var result = qualityAnalyzer.analyze(bitmap, effectiveRoi, timestampMillis)

            val detectionPass = detection.isDetected
            val scene = fingerSceneAnalyzer.analyze(bitmap, effectiveRoi)
            val clutterPass = !scene.cluttered

            val detectionStable = detectionGate.update(detectionPass && clutterPass, timestampMillis)
            val livenessPass = evaluateLivenessIfNeeded(bitmap, effectiveRoi)
            val combinedPass = result.pass && detectionStable && livenessPass
            val centerScore = computeCenterScore(detection.boundingBox, effectiveRoi, bitmap.width, bitmap.height)

            if (!detectionPass) {
                result = result.copy(pass = false, topReason = "No finger detected")
            } else if (!clutterPass) {
                result = result.copy(pass = false, topReason = "Background too cluttered")
            }

            val stablePass = gate.update(combinedPass, timestampMillis)
            _uiState.value = _uiState.value.copy(
                qualityResult = result,
                stablePass = stablePass,
                captureEnabled = stablePass,
                livenessResult = lastLivenessResult,
                detection = detection,
                centerScore = centerScore,
                message = when {
                    livenessEnabled && !livenessPass -> "Liveness failed"
                    !detectionPass -> "No finger detected"
                    !clutterPass -> "Background too cluttered"
                    else -> null
                },
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
        val detection = latestDetection

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
            val maskedRoi = if (detection != null && detection.landmarks.isNotEmpty()) {
                fingerMasker.applyFingerTipMask(roiBitmap, detection.landmarks, roi, frame.width, frame.height)
            } else {
                fingerMasker.applyMask(roiBitmap)
            }
            val roiResult = sessionRepository.saveBitmap(session, ArtifactFilenames.ROI, maskedRoi)
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

    fun captureFromBitmap(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val roi = buildLeftEllipseRoi(bitmap.width, bitmap.height)
            val detection = runDetection(bitmap, System.currentTimeMillis())
            val effectiveRoi = roi
            val qualityResult = qualityAnalyzer.analyze(bitmap, effectiveRoi, System.currentTimeMillis())

            _uiState.value = _uiState.value.copy(
                qualityResult = qualityResult,
                detection = detection,
            )

            val tenantId = authManager.activeTenant.value.id
            val session = when (val created = sessionRepository.createSession(tenantId)) {
                is AppResult.Success -> created.value
                is AppResult.Error -> return@launch
            }

            val rawResult = sessionRepository.saveBitmap(session, ArtifactFilenames.RAW, bitmap)
            if (rawResult is AppResult.Error) return@launch

            val roiBitmap = Bitmap.createBitmap(
                bitmap,
                effectiveRoi.left,
                effectiveRoi.top,
                effectiveRoi.width(),
                effectiveRoi.height(),
            )
            val maskedRoi = fingerMasker.applyMask(roiBitmap)
            val roiResult = sessionRepository.saveBitmap(session, ArtifactFilenames.ROI, maskedRoi)
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

            _uiState.value = _uiState.value.copy(lastSessionId = session.sessionId)
        }
    }

    private fun runDetection(bitmap: Bitmap, timestampMillis: Long): FingerDetectionResult {
        val minIntervalMs = 120L
        val last = latestDetection
        return if (timestampMillis - lastDetectionMillis < minIntervalMs && last != null) {
            last
        } else {
            val detection = fingerDetector.detectFinger(bitmap)
            lastDetectionMillis = timestampMillis
            detection
        }
    }

    private fun computeCenterScore(
        boundingBox: android.graphics.RectF?,
        roi: Rect,
        width: Int,
        height: Int,
    ): Int {
        if (boundingBox == null) return 0
        val boxCenterX = (boundingBox.left + boundingBox.right) / 2f * width
        val boxCenterY = (boundingBox.top + boundingBox.bottom) / 2f * height
        val roiCenterX = roi.exactCenterX()
        val roiCenterY = roi.exactCenterY()
        val dx = (boxCenterX - roiCenterX) / roi.width()
        val dy = (boxCenterY - roiCenterY) / roi.height()
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        return ((1.0 - (dist / 0.75)).coerceIn(0.0, 1.0) * 100).toInt()
    }

}

data class TrackAUiState(
    val qualityResult: QualityResult? = null,
    val stablePass: Boolean = false,
    val captureEnabled: Boolean = false,
    val livenessResult: LivenessResult? = null,
    val detection: FingerDetectionResult? = null,
    val centerScore: Int = 0,
    val message: String? = null,
    val lastSessionId: String? = null,
)
