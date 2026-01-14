package com.sitta.feature.track_a

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sitta.core.common.AppResult
import com.sitta.core.common.CapturePerformance
import com.sitta.core.common.DefaultConfig
import com.sitta.core.common.ArtifactFilenames
import com.sitta.core.common.LivenessReport
import com.sitta.core.common.QualityEvaluator
import com.sitta.core.common.QualityMetrics
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
import com.sitta.core.vision.LandmarkType
import com.sitta.core.vision.FingerDetector
import com.sitta.core.vision.FingerMasker
import com.sitta.core.vision.FingerSceneAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.TreeMap
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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
    private var lastBlurScore: Double = 0.0
    private var lastIlluminationMean: Double = 0.0
    private var lastCoverageRatio: Double = 0.0
    private var lastStabilityVariance: Double = DefaultConfig.value.stabilityMax + 1
    private var previousCenter: Pair<Float, Float>? = null
    private var lastLumaTimestamp: Long = 0L
    private var avgLumaIntervalMs: Double = 0.0
    private var readyPassCount: Int = 0
    private var readyFailCount: Int = 0
    private var readyState: Boolean = false
    private val frameLock = Any()
    private val frameBuffer = TreeMap<Long, SyncFrame>()
    private var lastEvaluatedFrameMs: Long = 0L
    private val perfTracker = CapturePerfTracker()
    private var lastDetectionMillis: Long = 0L
    private var lastLandmarks: List<com.sitta.core.vision.FingerLandmark> = emptyList()
    private var lastLandmarkMillis: Long = 0L
    private var livenessEnabled: Boolean = false
    private var autoCaptureEnabled: Boolean = true
    private var debugOverlayEnabled: Boolean = false
    private var focusRequested: Boolean = false
    private var focusSettledAt: Long = 0L
    private var focusAttemptedAt: Long = 0L
    private var lastLivenessResult: LivenessResult? = null
    private var lastReady: Boolean = false
    private var lastCaptureMs: Long = 0L
    private var captureInFlight: Boolean = false
    private var lastCaptureSource: CaptureSource? = null

    init {
        fingerDetector.initialize()
        fingerDetector.setResultListener { detection, timestamp, frameWidth, frameHeight ->
            viewModelScope.launch(Dispatchers.Default) {
                onDetectionResult(detection, timestamp, frameWidth, frameHeight)
            }
        }
        fingerDetector.setErrorListener { error ->
            android.util.Log.w("TrackA/MediaPipe", "Landmark error", error)
        }
        viewModelScope.launch {
            settingsRepository.livenessEnabled.collect { enabled ->
                livenessEnabled = enabled
                if (!enabled) {
                    lastLivenessResult = null
                    livenessFrames.clear()
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.autoCaptureEnabled.collect { enabled ->
                autoCaptureEnabled = enabled
            }
        }
        viewModelScope.launch {
            settingsRepository.debugOverlayEnabled.collect { enabled ->
                debugOverlayEnabled = enabled
            }
        }
    }

    fun onLumaMetrics(
        blurScore: Double,
        illuminationMean: Double,
        roi: Rect,
        frameWidth: Int,
        frameHeight: Int,
        timestampMillis: Long,
    ) {
        latestRoi = roi
        lastBlurScore = blurScore
        lastIlluminationMean = illuminationMean
        perfTracker.onLumaFrame(blurScore, illuminationMean, lastStabilityVariance)
        updateLumaFrame(
            timestampMillis = timestampMillis,
            blurScore = blurScore,
            illuminationMean = illuminationMean,
            roi = roi,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
        )
        viewModelScope.launch(Dispatchers.Default) {
            evaluateFromLatestFrame(timestampMillis)
        }
    }

    fun onLandmarksFrame(bitmap: Bitmap, timestampMillis: Long, frameCrop: com.sitta.core.vision.FrameCrop) {
        latestFrame = bitmap
        fingerDetector.detectAsync(bitmap, timestampMillis, frameCrop)
        viewModelScope.launch(Dispatchers.Default) {
            val roiFull = latestRoi ?: buildGuideRoi(frameCrop.frameWidth, frameCrop.frameHeight)
            val cropRect = frameCrop.cropRect
            val adjusted = Rect(
                (roiFull.left - cropRect.left).coerceAtLeast(0),
                (roiFull.top - cropRect.top).coerceAtLeast(0),
                (roiFull.right - cropRect.left).coerceAtMost(cropRect.width()),
                (roiFull.bottom - cropRect.top).coerceAtMost(cropRect.height()),
            )
            val livenessRoi = if (adjusted.width() <= 0 || adjusted.height() <= 0) {
                Rect(0, 0, bitmap.width, bitmap.height)
            } else {
                adjusted
            }
            evaluateLivenessIfNeeded(bitmap, livenessRoi)
        }
    }

    fun computeLandmarkCropRect(
        frameWidth: Int,
        frameHeight: Int,
        guideRoi: Rect,
        timestampMillis: Long,
    ): Rect? {
        if (timestampMillis - lastDetectionMillis > 450L) return null
        val detection = latestDetection ?: return null
        val points = detection.landmarks
        val rect = if (points.isNotEmpty()) {
            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            Rect(
                (minX * frameWidth).toInt(),
                (minY * frameHeight).toInt(),
                (maxX * frameWidth).toInt(),
                (maxY * frameHeight).toInt(),
            )
        } else {
            val bbox = detection.boundingBox ?: return null
            Rect(
                (bbox.left * frameWidth).toInt(),
                (bbox.top * frameHeight).toInt(),
                (bbox.right * frameWidth).toInt(),
                (bbox.bottom * frameHeight).toInt(),
            )
        }
        val infl = inflateRect(rect, frameWidth, frameHeight, 1.5f)
        return if (infl.width() > 0 && infl.height() > 0) infl else guideRoi
    }

    private fun inflateRect(rect: Rect, frameWidth: Int, frameHeight: Int, scale: Float): Rect {
        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()
        val halfW = rect.width() * scale / 2f
        val halfH = rect.height() * scale / 2f
        val left = (cx - halfW).toInt().coerceAtLeast(0)
        val top = (cy - halfH).toInt().coerceAtLeast(0)
        val right = (cx + halfW).toInt().coerceAtMost(frameWidth)
        val bottom = (cy + halfH).toInt().coerceAtMost(frameHeight)
        return Rect(left, top, right, bottom)
    }

    private fun onDetectionResult(
        detection: FingerDetectionResult,
        timestampMillis: Long,
        frameWidth: Int,
        frameHeight: Int,
    ) {
        if (timestampMillis < lastDetectionMillis) {
            return
        }
        latestDetection = detection
        lastDetectionMillis = timestampMillis
        if (detection.landmarks.isNotEmpty()) {
            lastLandmarks = detection.landmarks
            lastLandmarkMillis = timestampMillis
        }
        perfTracker.onLandmarkResult(detection.isDetected)
        updateDetectionFrame(timestampMillis, detection, frameWidth, frameHeight)
        viewModelScope.launch(Dispatchers.Default) {
            evaluateFromLatestFrame(timestampMillis)
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

    fun onAutoCaptureCompleted(success: Boolean) {
        captureInFlight = false
        val notice = if (success) "Auto captured" else "Auto capture failed"
        _uiState.value = _uiState.value.copy(
            autoCaptureRequested = false,
            captureNotice = notice,
            captureSource = CaptureSource.AUTO,
        )
        if (!success) {
            perfTracker.buildAndReset("auto_capture_failed")
        }
        viewModelScope.launch {
            kotlinx.coroutines.delay(1200L)
            _uiState.value = _uiState.value.copy(captureNotice = null)
        }
    }

    fun captureFromBitmap(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val roi = buildGuideRoi(bitmap.width, bitmap.height)
            val now = System.currentTimeMillis()
            val detection = latestDetection?.takeIf { now - lastDetectionMillis <= 700L }
                ?: fingerDetector.detectFinger(bitmap)
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
            val croppedRoi = fingerMasker.cropToFingertips(maskedRoi, roiBitmap)
            val roiResult = sessionRepository.saveBitmap(session, ArtifactFilenames.ROI, croppedRoi)
            if (roiResult is AppResult.Error) return@launch

            val perf = perfTracker.buildAndReset("manual_capture")
            val qualityReport = QualityReport(
                sessionId = session.sessionId,
                timestamp = System.currentTimeMillis(),
                score0To100 = qualityResult.score0To100,
                pass = qualityResult.pass,
                metrics = qualityResult.metrics,
                topReason = qualityResult.topReason,
                performance = perf,
            )
            sessionRepository.saveJson(session, ArtifactFilenames.QUALITY, qualityReport)

            _uiState.value = _uiState.value.copy(lastSessionId = session.sessionId)
        }
    }

    private fun overlayLandmarksFor(timestampMillis: Long): List<com.sitta.core.vision.FingerLandmark> {
        return if (timestampMillis - lastLandmarkMillis <= 320L) lastLandmarks else emptyList()
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
                val croppedRoi = fingerMasker.cropToFingertips(maskedRoi, roiBitmap)
                val roiResult = sessionRepository.saveBitmap(session, ArtifactFilenames.ROI, croppedRoi)
                if (roiResult is AppResult.Error) return@launch

                val perf = perfTracker.buildAndReset("capture_internal")
                val qualityReport = QualityReport(
                    sessionId = session.sessionId,
                    timestamp = System.currentTimeMillis(),
                    score0To100 = qualityResult.score0To100,
                    pass = qualityResult.pass,
                    metrics = qualityResult.metrics,
                    topReason = qualityResult.topReason,
                    performance = perf,
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
        focusScore: Int,
        lightScore: Int,
        steadyScore: Int,
        scaleOk: Boolean,
        timestampMillis: Long,
    ): Boolean {
        if (!detectionStable) {
            logBlocker("READY", "Detection unstable or missing")
            focusRequested = false
            return false
        }
        val centerOk = if (lastReady) centerScore >= TrackACaptureConfig.centerExitScore else centerScore >= TrackACaptureConfig.centerEnterScore
        if (!centerOk) {
            logBlocker("READY", "Center score $centerScore below threshold")
            focusRequested = false
            return false
        }
        if (!scaleOk) {
            logBlocker("READY", "Finger scale below threshold")
            focusRequested = false
            return false
        }
        val focusThreshold = if (lastReady) TrackACaptureConfig.focusExitScore else TrackACaptureConfig.focusEnterScore
        val focusOk = focusScore >= focusThreshold
        if (!focusOk) {
            logBlocker("READY", "Focus score $focusScore below threshold")
            focusRequested = false
            return false
        }
        val lightThreshold = if (lastReady) TrackACaptureConfig.lightExitScore else TrackACaptureConfig.lightEnterScore
        val lightOk = lightScore >= lightThreshold
        if (!lightOk) {
            logBlocker("READY", "Light score $lightScore below threshold")
            focusRequested = false
            return false
        }
        val steadyThreshold = if (lastReady) TrackACaptureConfig.steadyExitScore else TrackACaptureConfig.steadyEnterScore
        val steadyOk = steadyScore >= steadyThreshold
        if (!steadyOk) {
            logBlocker("READY", "Steady score $steadyScore below threshold")
            focusRequested = false
            return false
        }
        if (!focusRequested && timestampMillis - focusSettledAt > TrackACaptureConfig.focusRefreshMs) {
            focusRequested = true
            focusAttemptedAt = timestampMillis
        }
        if (focusRequested && timestampMillis - focusAttemptedAt > TrackACaptureConfig.focusMaxWaitMs) {
            focusRequested = false
        }
        return true
    }

    private fun scoreFocus(blurScore: Double): Int {
        val threshold = DefaultConfig.value.blurThreshold
        return ((blurScore / threshold) * 100).toInt().coerceIn(0, 100)
    }

    private fun scoreLight(illumination: Double): Int {
        val min = DefaultConfig.value.illuminationMin
        val max = DefaultConfig.value.illuminationMax
        return when {
            illumination < min -> ((illumination / min) * 100).toInt()
            illumination > max -> ((max / illumination) * 100).toInt()
            else -> 100
        }.coerceIn(0, 100)
    }

    private fun scoreSteady(variance: Double): Int {
        val max = DefaultConfig.value.stabilityMax
        return if (variance <= 0.0) {
            100
        } else {
            ((max / variance).coerceIn(0.0, 1.0) * 100).toInt()
        }
    }

    private fun computeFingerScalePx(
        detection: FingerDetectionResult,
        width: Int,
        height: Int,
    ): Float {
        val pairs = listOf(
            LandmarkType.INDEX_TIP to LandmarkType.INDEX_DIP,
            LandmarkType.MIDDLE_TIP to LandmarkType.MIDDLE_DIP,
            LandmarkType.RING_TIP to LandmarkType.RING_DIP,
            LandmarkType.PINKY_TIP to LandmarkType.PINKY_DIP,
        )
        val distances = pairs.mapNotNull { (tipType, dipType) ->
            val tip = detection.landmarks.firstOrNull { it.type == tipType }
            val dip = detection.landmarks.firstOrNull { it.type == dipType }
            if (tip == null || dip == null) return@mapNotNull null
            val dx = (tip.x - dip.x) * width
            val dy = (tip.y - dip.y) * height
            kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        }
        return if (distances.isEmpty()) 0f else distances.average().toFloat()
    }

    fun onFocusMeteringSettled() {
        focusSettledAt = System.currentTimeMillis()
        focusRequested = false
    }

    fun landmarkCadenceMs(blurScore: Double, lightScore: Double, nowMillis: Long): Long {
        val detectionFresh = nowMillis - lastDetectionMillis <= 450L
        val focusScore = scoreFocus(blurScore)
        val lightScorePct = scoreLight(lightScore)
        val lowQuality = focusScore < 35 || lightScorePct < 35
        return when {
            !detectionFresh -> 60L
            lowQuality -> 180L
            else -> 130L
        }
    }

    fun scoreCandidate(bitmap: Bitmap): Double {
        val roi = buildGuideRoi(bitmap.width, bitmap.height)
        val quality = qualityAnalyzer.analyze(bitmap, roi)
        val glarePct = computeGlarePercent(bitmap, roi)
        return quality.metrics.blurScore - (glarePct * 2000.0)
    }

    private fun computeGlarePercent(bitmap: Bitmap, roi: Rect): Double {
        val width = bitmap.width
        val height = bitmap.height
        if (roi.width() <= 0 || roi.height() <= 0) return 0.0
        val pixels = IntArray(roi.width() * roi.height())
        bitmap.getPixels(pixels, 0, roi.width(), roi.left, roi.top, roi.width(), roi.height())
        var bright = 0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xff
            val g = (pixel shr 8) and 0xff
            val b = pixel and 0xff
            val lum = (r + g + b) / 3
            if (lum > 245) bright++
        }
        return bright.toDouble() / pixels.size.toDouble()
    }

    private fun logBlocker(tag: String, message: String) {
        android.util.Log.d("TrackA/$tag", message)
    }

    private fun updateLumaFrame(
        timestampMillis: Long,
        blurScore: Double,
        illuminationMean: Double,
        roi: Rect,
        frameWidth: Int,
        frameHeight: Int,
    ) {
        synchronized(frameLock) {
            val existing = frameBuffer[timestampMillis]
            val updated = (existing ?: SyncFrame(timestampMillis))
                .copy(
                    blurScore = blurScore,
                    illuminationMean = illuminationMean,
                    roi = roi,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    hasLuma = true,
                )
            frameBuffer[timestampMillis] = updated
            pruneFrames(timestampMillis)
        }
    }

    private fun updateDetectionFrame(
        timestampMillis: Long,
        detection: FingerDetectionResult,
        frameWidth: Int,
        frameHeight: Int,
    ) {
        synchronized(frameLock) {
            val existing = frameBuffer[timestampMillis]
            val roi = existing?.roi ?: buildGuideRoi(frameWidth, frameHeight)
            val updated = (existing ?: SyncFrame(timestampMillis))
                .copy(
                    detection = detection,
                    roi = roi,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    hasDetection = true,
                )
            frameBuffer[timestampMillis] = updated
            pruneFrames(timestampMillis)
        }
    }

    private fun pruneFrames(nowMillis: Long) {
        val cutoff = nowMillis - 700L
        val iterator = frameBuffer.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key < cutoff) {
                iterator.remove()
            } else {
                break
            }
        }
    }

    private fun latestCompleteFrame(): SyncFrame? {
        synchronized(frameLock) {
            val entries = frameBuffer.entries.toList()
            for (index in entries.indices.reversed()) {
                val frame = entries[index].value
                if (frame.isComplete) return frame
            }
            return null
        }
    }

    private fun evaluateFromLatestFrame(timestampMillis: Long) {
        val frame = latestCompleteFrame()
        if (frame == null) {
            updateUiWithoutDetection(timestampMillis)
            return
        }
        if (frame.timestamp <= lastEvaluatedFrameMs) return
        lastEvaluatedFrameMs = frame.timestamp

        val detection = frame.detection ?: FingerDetectionResult.notDetected()
        val coverage = computeCoverageRatio(detection, frame.roi, frame.frameWidth, frame.frameHeight)
        lastCoverageRatio = coverage
        val centerPoint = computeDetectionCenter(detection, frame.roi, frame.frameWidth, frame.frameHeight)
        val stabilityVariance = computeStabilityVariance(centerPoint)
        lastStabilityVariance = stabilityVariance

        val metrics = QualityMetrics(
            blurScore = frame.blurScore,
            illuminationMean = frame.illuminationMean,
            coverageRatio = coverage,
            stabilityVariance = stabilityVariance,
        )
        val evaluation = QualityEvaluator.evaluate(metrics, DefaultConfig.value)
        var result = QualityResult(
            metrics = metrics,
            passes = evaluation.passes,
            score0To100 = evaluation.score0To100,
            pass = evaluation.pass,
            topReason = evaluation.topReason,
            timestampMillis = frame.timestamp,
        )

        val presenceCoverage = coverage >= TrackACaptureConfig.coverageEnter
        val detectionFresh = frame.timestamp - lastDetectionMillis <= 450L
        val detectionPass = detectionFresh && detection.isDetected &&
            (detection.landmarks.isNotEmpty() || (detection.boundingBox != null && presenceCoverage))
        val detectionStable = detectionGate.update(detectionPass, frame.timestamp)

        val centerScore = computeCenterScore(detection, frame.roi, frame.frameWidth, frame.frameHeight)
        val centerValid = centerScore > 0
        val focusScore = scoreFocus(frame.blurScore)
        val lightScore = scoreLight(frame.illuminationMean)
        val steadyScore = scoreSteady(stabilityVariance)
        val fingerScalePx = computeFingerScalePx(detection, frame.frameWidth, frame.frameHeight)
        val scaleOk = fingerScalePx >= TrackACaptureConfig.minFingerScalePx

        val readyRaw = evaluateReadyRaw(
            detectionStable = detectionStable,
            centerScore = centerScore,
            focusScore = focusScore,
            lightScore = lightScore,
            steadyScore = steadyScore,
            scaleOk = scaleOk,
            timestampMillis = frame.timestamp,
        )
        val delta = if (lastLumaTimestamp == 0L) null else frame.timestamp - lastLumaTimestamp
        if (delta != null) {
            avgLumaIntervalMs = if (avgLumaIntervalMs == 0.0) {
                delta.toDouble()
            } else {
                (avgLumaIntervalMs * 0.9) + (delta.toDouble() * 0.1)
            }
        }
        lastLumaTimestamp = frame.timestamp
        val requireCount = avgLumaIntervalMs > 0.0 && avgLumaIntervalMs <= 45.0
        if (readyRaw) {
            readyPassCount++
            readyFailCount = 0
        } else {
            readyFailCount++
            readyPassCount = 0
        }
        val readyByTime = readyGate.update(readyRaw, frame.timestamp)
        val readyByCount = !requireCount || readyPassCount >= TrackACaptureConfig.readyPassCount

        if (!readyState) {
            if (readyRaw && readyByTime && readyByCount) {
                readyState = true
                perfTracker.onReady()
            }
        } else if (!readyRaw) {
            if (!requireCount || readyFailCount >= TrackACaptureConfig.readyFailCount) {
                readyState = false
            }
        }
        lastReady = readyState

        val livenessPass = if (!livenessEnabled) true else lastLivenessResult?.decision == "PASS"
        val combinedPass = readyState && livenessPass

        if (!detectionPass) {
            result = result.copy(pass = false, topReason = "No finger detected")
            previousCenter = null
        }

        _uiState.value = _uiState.value.copy(
            qualityResult = result,
            stablePass = combinedPass,
            captureEnabled = combinedPass,
            livenessResult = lastLivenessResult,
            detection = detection,
            centerScore = centerScore,
            overlayLandmarks = overlayLandmarksFor(frame.timestamp),
            debugOverlayEnabled = debugOverlayEnabled,
            focusScore = focusScore,
            lightScore = lightScore,
            steadyScore = steadyScore,
            fingerScalePx = fingerScalePx,
            focusRequested = focusRequested,
            message = when {
                livenessEnabled && !livenessPass -> "Liveness failed"
                !detectionPass -> "No finger detected"
                !scaleOk -> "Move closer to the camera"
                !centerValid -> "Centering required"
                else -> null
            },
        )

        if (combinedPass && autoCaptureEnabled) {
            val canAutoCapture = autoGate.update(true, frame.timestamp)
            if (canAutoCapture && !captureInFlight && frame.timestamp - lastCaptureMs > TrackACaptureConfig.autoCaptureCooldownMs) {
                captureInFlight = true
                lastCaptureMs = frame.timestamp
                lastCaptureSource = CaptureSource.AUTO
                _uiState.value = _uiState.value.copy(
                    autoCaptureRequested = true,
                    captureSource = CaptureSource.AUTO,
                )
            }
        } else {
            autoGate.update(false, frame.timestamp)
        }
    }

    private fun updateUiWithoutDetection(timestampMillis: Long) {
        val focusScore = scoreFocus(lastBlurScore)
        val lightScore = scoreLight(lastIlluminationMean)
        val steadyScore = scoreSteady(lastStabilityVariance)
        val metrics = QualityMetrics(
            blurScore = lastBlurScore,
            illuminationMean = lastIlluminationMean,
            coverageRatio = 0.0,
            stabilityVariance = lastStabilityVariance,
        )
        val evaluation = QualityEvaluator.evaluate(metrics, DefaultConfig.value)
        val result = QualityResult(
            metrics = metrics,
            passes = evaluation.passes,
            score0To100 = evaluation.score0To100,
            pass = false,
            topReason = "No finger detected",
            timestampMillis = timestampMillis,
        )
        _uiState.value = _uiState.value.copy(
            qualityResult = result,
            stablePass = false,
            captureEnabled = false,
            livenessResult = lastLivenessResult,
            detection = latestDetection,
            centerScore = 0,
            overlayLandmarks = overlayLandmarksFor(timestampMillis),
            debugOverlayEnabled = debugOverlayEnabled,
            focusScore = focusScore,
            lightScore = lightScore,
            steadyScore = steadyScore,
            fingerScalePx = 0f,
            focusRequested = focusRequested,
            message = "No finger detected",
        )
    }

    private data class SyncFrame(
        val timestamp: Long,
        val blurScore: Double = 0.0,
        val illuminationMean: Double = 0.0,
        val roi: Rect = Rect(),
        val frameWidth: Int = 0,
        val frameHeight: Int = 0,
        val detection: FingerDetectionResult? = null,
        val hasLuma: Boolean = false,
        val hasDetection: Boolean = false,
    ) {
        val isComplete: Boolean get() = hasLuma && hasDetection
    }

    private class CapturePerfTracker {
        private var captureStartMs: Long = 0L
        private var firstReadyMs: Long? = null
        private var framesAnalyzed: Int = 0
        private var mediapipeSuccess: Int = 0
        private var mediapipeFail: Int = 0
        private var blurSum = 0.0
        private var lightSum = 0.0
        private var steadySum = 0.0

        fun onLumaFrame(blur: Double, light: Double, steady: Double) {
            if (captureStartMs == 0L) captureStartMs = System.currentTimeMillis()
            framesAnalyzed++
            blurSum += blur
            lightSum += light
            steadySum += steady
        }

        fun onLandmarkResult(success: Boolean) {
            if (success) mediapipeSuccess++ else mediapipeFail++
        }

        fun onReady() {
            if (firstReadyMs == null) firstReadyMs = System.currentTimeMillis()
        }

        fun buildAndReset(tag: String): CapturePerformance? {
            if (captureStartMs == 0L) return null
            val now = System.currentTimeMillis()
            val timeToReady = firstReadyMs?.let { it - captureStartMs }
            val avgBlur = if (framesAnalyzed > 0) blurSum / framesAnalyzed else 0.0
            val avgLight = if (framesAnalyzed > 0) lightSum / framesAnalyzed else 0.0
            val avgSteady = if (framesAnalyzed > 0) steadySum / framesAnalyzed else 0.0
            val total = now - captureStartMs
            android.util.Log.i(
                "TrackA/Perf",
                "$tag total=${total}ms ready=${timeToReady ?: -1}ms frames=$framesAnalyzed mp_ok=$mediapipeSuccess mp_fail=$mediapipeFail avgBlur=${avgBlur.toInt()} avgLight=${avgLight.toInt()} avgSteady=${avgSteady.toInt()}",
            )
            val perf = CapturePerformance(
                captureStartMs = captureStartMs,
                readyMs = firstReadyMs,
                captureCompleteMs = now,
                timeToReadyMs = timeToReady,
                totalTimeMs = total,
                framesAnalyzed = framesAnalyzed,
                mediapipeSuccess = mediapipeSuccess,
                mediapipeFail = mediapipeFail,
                avgBlur = avgBlur,
                avgLight = avgLight,
                avgSteady = avgSteady,
            )
            captureStartMs = 0L
            firstReadyMs = null
            framesAnalyzed = 0
            mediapipeSuccess = 0
            mediapipeFail = 0
            blurSum = 0.0
            lightSum = 0.0
            steadySum = 0.0
            return perf
        }
    }
    private fun computeCoverageRatio(
        detection: FingerDetectionResult,
        roi: Rect,
        width: Int,
        height: Int,
    ): Double {
        val bbox = detection.boundingBox ?: return 0.0
        val roiLeft = roi.left.toFloat() / width
        val roiTop = roi.top.toFloat() / height
        val roiRight = roi.right.toFloat() / width
        val roiBottom = roi.bottom.toFloat() / height
        val interLeft = max(roiLeft, bbox.left)
        val interTop = max(roiTop, bbox.top)
        val interRight = min(roiRight, bbox.right)
        val interBottom = min(roiBottom, bbox.bottom)
        val interWidth = (interRight - interLeft).coerceAtLeast(0f)
        val interHeight = (interBottom - interTop).coerceAtLeast(0f)
        val interArea = interWidth * interHeight
        val roiArea = ((roiRight - roiLeft).coerceAtLeast(0f)) * ((roiBottom - roiTop).coerceAtLeast(0f))
        return if (roiArea > 0f) (interArea / roiArea).toDouble() else 0.0
    }

    private fun computeStabilityVariance(center: Pair<Float, Float>?): Double {
        val prev = previousCenter
        previousCenter = center
        if (center == null || prev == null) {
            return DefaultConfig.value.stabilityMax + 1
        }
        return hypot((center.first - prev.first).toDouble(), (center.second - prev.second).toDouble())
    }

    private fun computeDetectionCenter(
        detection: FingerDetectionResult,
        roi: Rect,
        width: Int,
        height: Int,
    ): Pair<Float, Float>? {
        val tipPoints = detection.landmarks.filter {
            it.type in listOf(
                com.sitta.core.vision.LandmarkType.INDEX_TIP,
                com.sitta.core.vision.LandmarkType.MIDDLE_TIP,
                com.sitta.core.vision.LandmarkType.RING_TIP,
                com.sitta.core.vision.LandmarkType.PINKY_TIP,
            )
        }
        return if (tipPoints.isNotEmpty()) {
            val avgX = tipPoints.map { it.x }.average().toFloat() * width
            val avgY = tipPoints.map { it.y }.average().toFloat() * height
            Pair(avgX, avgY)
        } else {
            val boundingBox = detection.boundingBox ?: return null
            Pair(
                (boundingBox.left + boundingBox.right) / 2f * width,
                (boundingBox.top + boundingBox.bottom) / 2f * height,
            )
        }
    }

    private fun computeCenterScore(
        detection: FingerDetectionResult,
        roi: Rect,
        width: Int,
        height: Int,
    ): Int {
        val center = computeDetectionCenter(detection, roi, width, height) ?: return 0
        return computeCenterScoreFromPoint(center, roi)
    }

    private fun computeCenterScoreFromPoint(center: Pair<Float, Float>, roi: Rect): Int {
        val roiCenterX = roi.exactCenterX()
        val roiCenterY = roi.exactCenterY()
        val dx = (center.first - roiCenterX) / roi.width()
        val dy = (center.second - roiCenterY) / roi.height()
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        return ((1.0 - (dist / 0.75)).coerceIn(0.0, 1.0) * 100).toInt()
    }

}

data class TrackAUiState(
    val qualityResult: QualityResult? = null,
    val stablePass: Boolean = false,
    val captureEnabled: Boolean = false,
    val autoCaptureRequested: Boolean = false,
    val livenessResult: LivenessResult? = null,
    val detection: FingerDetectionResult? = null,
    val centerScore: Int = 0,
    val overlayLandmarks: List<com.sitta.core.vision.FingerLandmark> = emptyList(),
    val debugOverlayEnabled: Boolean = false,
    val focusScore: Int = 0,
    val lightScore: Int = 0,
    val steadyScore: Int = 0,
    val fingerScalePx: Float = 0f,
    val focusRequested: Boolean = false,
    val message: String? = null,
    val captureNotice: String? = null,
    val captureSource: CaptureSource? = null,
    val lastSessionId: String? = null,
)

enum class CaptureSource { MANUAL, AUTO }
