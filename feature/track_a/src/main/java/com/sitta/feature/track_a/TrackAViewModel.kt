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
    private val readyGate = QualityStabilityGate(TrackACaptureConfig.readyStableMs)
    private val detectionGate = QualityStabilityGate(TrackACaptureConfig.detectionStableMs)
    private val autoGate = QualityStabilityGate(TrackACaptureConfig.autoCaptureHoldMs)
    private val livenessFrames = ArrayDeque<Bitmap>()

    private val _uiState = MutableStateFlow(TrackAUiState())
    val uiState: StateFlow<TrackAUiState> = _uiState.asStateFlow()

    private var latestFrame: Bitmap? = null
    private var latestRoi: Rect? = null
    private var latestDetection: FingerDetectionResult? = null
    private var lastDetectionMillis: Long = 0L
    private var lastLandmarks: List<com.sitta.core.vision.FingerLandmark> = emptyList()
    private var lastLandmarkMillis: Long = 0L
    private var livenessEnabled: Boolean = false
    private var lastLivenessResult: LivenessResult? = null
    private var lastReady: Boolean = false
    private var lastCaptureMs: Long = 0L
    private var captureInFlight: Boolean = false
    private var lastCaptureSource: CaptureSource? = null

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
            val overlayLandmarks = resolveOverlayLandmarks(detection, timestampMillis)

            val effectiveRoi = roi
            var result = qualityAnalyzer.analyze(bitmap, effectiveRoi, timestampMillis)

            val detectionPass = detection.isDetected
            val scene = fingerSceneAnalyzer.analyze(bitmap, effectiveRoi)
            val clutterPass = !scene.cluttered

            val detectionStable = detectionGate.update(detectionPass && clutterPass, timestampMillis)
            val centerScore = computeCenterScore(detection, effectiveRoi, bitmap.width, bitmap.height)
            val centerValid = centerScore > 0
            val qualityScore = result.score0To100
            val coverage = result.metrics.coverageRatio

            val readyRaw = evaluateReadyRaw(
                detectionStable = detectionStable,
                centerScore = centerScore,
                qualityScore = qualityScore,
                coverage = coverage,
            )
            val readyStable = readyGate.update(readyRaw, timestampMillis)
            lastReady = readyRaw

            val livenessPass = evaluateLivenessIfNeeded(bitmap, effectiveRoi)
            val combinedPass = readyStable && livenessPass

            if (!detectionPass) {
                result = result.copy(pass = false, topReason = "No finger detected")
            } else if (!clutterPass) {
                result = result.copy(pass = false, topReason = "Background too cluttered")
            }

            _uiState.value = _uiState.value.copy(
                qualityResult = result,
                stablePass = combinedPass,
                captureEnabled = combinedPass,
                livenessResult = lastLivenessResult,
                detection = detection,
                centerScore = centerScore,
                overlayLandmarks = overlayLandmarks,
                message = when {
                    livenessEnabled && !livenessPass -> "Liveness failed"
                    !detectionPass -> "No finger detected"
                    !clutterPass -> "Background too cluttered"
                    !centerValid -> "Centering required"
                    else -> null
                },
            )

            if (combinedPass) {
                val canAutoCapture = autoGate.update(true, timestampMillis)
                if (canAutoCapture && !captureInFlight && timestampMillis - lastCaptureMs > TrackACaptureConfig.autoCaptureCooldownMs) {
                    logBlocker("AUTO_CAPTURE", "Triggered")
                    captureInternal(CaptureSource.AUTO)
                }
            } else {
                autoGate.update(false, timestampMillis)
            }
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
        captureInternal(CaptureSource.MANUAL)
    }

    fun captureFromBitmap(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val roi = buildGuideRoi(bitmap.width, bitmap.height)
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
            val maskedRoi = if (detection.landmarks.isNotEmpty()) {
                fingerMasker.applyFingerTipMask(roiBitmap, detection.landmarks, roi, bitmap.width, bitmap.height)
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

    private fun resolveOverlayLandmarks(
        detection: FingerDetectionResult,
        timestampMillis: Long,
    ): List<com.sitta.core.vision.FingerLandmark> {
        return if (detection.isDetected && detection.landmarks.isNotEmpty()) {
            lastLandmarks = detection.landmarks
            lastLandmarkMillis = timestampMillis
            detection.landmarks
        } else if (timestampMillis - lastLandmarkMillis <= 320L) {
            lastLandmarks
        } else {
            emptyList()
        }
    }

    private fun captureInternal(source: CaptureSource) {
        if (captureInFlight) return
        val frame = latestFrame ?: return
        val roi = latestRoi ?: return
        val qualityResult = _uiState.value.qualityResult ?: return
        val tenantId = authManager.activeTenant.value.id
        val detection = latestDetection
        captureInFlight = true
        lastCaptureMs = System.currentTimeMillis()
        lastCaptureSource = source
        val notice = if (source == CaptureSource.AUTO) "Auto captured" else "Captured"
        _uiState.value = _uiState.value.copy(captureNotice = notice, captureSource = source)
        viewModelScope.launch {
            kotlinx.coroutines.delay(1200L)
            _uiState.value = _uiState.value.copy(captureNotice = null)
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
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
            } finally {
                captureInFlight = false
            }
        }
    }

    private fun evaluateReadyRaw(
        detectionStable: Boolean,
        centerScore: Int,
        qualityScore: Int,
        coverage: Double,
    ): Boolean {
        if (!detectionStable) {
            logBlocker("READY", "Detection unstable or missing")
            return false
        }
        val centerOk = if (lastReady) centerScore >= TrackACaptureConfig.centerExitScore else centerScore >= TrackACaptureConfig.centerEnterScore
        if (!centerOk) {
            logBlocker("READY", "Center score $centerScore below threshold")
            return false
        }
        val qualityOk = if (lastReady) qualityScore >= TrackACaptureConfig.qualityExitScore else qualityScore >= TrackACaptureConfig.qualityEnterScore
        if (!qualityOk) {
            logBlocker("READY", "Quality score $qualityScore below threshold")
            return false
        }
        val coverageOk = if (lastReady) coverage >= TrackACaptureConfig.coverageExit else coverage >= TrackACaptureConfig.coverageEnter
        if (!coverageOk) {
            logBlocker("READY", "Coverage $coverage below threshold")
            return false
        }
        return true
    }

    private fun logBlocker(tag: String, message: String) {
        android.util.Log.d("TrackA/$tag", message)
    }

    private fun computeCenterScore(
        detection: FingerDetectionResult,
        roi: Rect,
        width: Int,
        height: Int,
    ): Int {
        val tipPoints = detection.landmarks.filter {
            it.type in listOf(
                com.sitta.core.vision.LandmarkType.INDEX_TIP,
                com.sitta.core.vision.LandmarkType.MIDDLE_TIP,
                com.sitta.core.vision.LandmarkType.RING_TIP,
                com.sitta.core.vision.LandmarkType.PINKY_TIP,
            )
        }
        val center = if (tipPoints.isNotEmpty()) {
            val avgX = tipPoints.map { it.x }.average().toFloat() * width
            val avgY = tipPoints.map { it.y }.average().toFloat() * height
            Pair(avgX, avgY)
        } else {
            val boundingBox = detection.boundingBox
            if (boundingBox == null) return 0
            Pair(
                (boundingBox.left + boundingBox.right) / 2f * width,
                (boundingBox.top + boundingBox.bottom) / 2f * height,
            )
        }
        val boxCenterX = center.first
        val boxCenterY = center.second
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
    val overlayLandmarks: List<com.sitta.core.vision.FingerLandmark> = emptyList(),
    val message: String? = null,
    val captureNotice: String? = null,
    val captureSource: CaptureSource? = null,
    val lastSessionId: String? = null,
)

enum class CaptureSource { MANUAL, AUTO }
