package com.sitta.feature.track_b

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sitta.core.common.AppResult
import com.sitta.core.common.ArtifactFilenames
import com.sitta.core.common.SessionInfo
import com.sitta.core.data.AuthManager
import com.sitta.core.data.SessionRepository
import com.sitta.core.domain.EnhancementPipeline
import com.sitta.core.domain.QualityAnalyzer
import com.sitta.core.domain.QualityResult
import com.sitta.core.vision.FingerRidgeExtractor
import com.sitta.core.vision.FingerSkeletonizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TrackBViewModel(
    private val sessionRepository: SessionRepository,
    private val authManager: AuthManager,
    private val enhancementPipeline: EnhancementPipeline,
    private val qualityAnalyzer: QualityAnalyzer,
    private val ridgeExtractor: FingerRidgeExtractor,
    private val skeletonizer: FingerSkeletonizer,
    private val appContext: android.content.Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TrackBUiState())
    val uiState: StateFlow<TrackBUiState> = _uiState.asStateFlow()

    fun loadLastCapture() {
        viewModelScope.launch(Dispatchers.IO) {
            val tenantId = authManager.activeTenant.value.id
            val session = sessionRepository.loadLastSession(tenantId)
            if (session == null) {
                _uiState.value = _uiState.value.copy(message = "No session found")
                return@launch
            }
            val roiFile = sessionRepository.loadBitmap(tenantId, session.sessionId, ArtifactFilenames.ROI)
                ?: sessionRepository.loadBitmap(tenantId, session.sessionId, ArtifactFilenames.RAW)
            val maskFile = sessionRepository.loadBitmap(tenantId, session.sessionId, ArtifactFilenames.SEGMENTED)
            if (roiFile == null) {
                _uiState.value = _uiState.value.copy(message = "Capture not found")
                return@launch
            }
            val rawBitmap = BitmapFactory.decodeFile(roiFile.absolutePath)
            val maskBitmap = maskFile?.let { BitmapFactory.decodeFile(it.absolutePath) }
            _uiState.value = _uiState.value.copy(rawBitmap = rawBitmap, session = session, message = null)
            processEnhancement(rawBitmap, maskBitmap, session, _uiState.value.sharpenStrength)
        }
    }

    fun updateSharpenStrength(value: Float) {
        _uiState.value = _uiState.value.copy(sharpenStrength = value)
        val raw = _uiState.value.rawBitmap ?: return
        val mask = _uiState.value.maskBitmap
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            processEnhancement(raw, mask, session, value)
        }
    }

    fun exportEnhanced() {
        val enhanced = _uiState.value.enhancedBitmap ?: return
        val session = _uiState.value.session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            sessionRepository.saveBitmap(session, ArtifactFilenames.ENHANCED, enhanced)
            val saved = saveToGallery(enhanced)
            val message = if (saved != null) {
                "Saved to session and gallery"
            } else {
                "Saved to session (gallery save failed)"
            }
            _uiState.value = _uiState.value.copy(message = message)
        }
    }

    private suspend fun processEnhancement(bitmap: Bitmap, maskBitmap: Bitmap?, session: SessionInfo, strength: Float) {
        runCatching {
            val result = enhancementPipeline.enhance(bitmap, strength)
            val ridgeInput = maskBitmap ?: result.bitmap
            val ridgeBitmap = ridgeExtractor.extractRidge(ridgeInput)
            val ridgeDensity = computeRidgeDensity(ridgeBitmap)
            val skeletonBitmap = if (ridgeDensity in 0.15..0.6) {
                skeletonizer.skeletonize(ridgeBitmap)
            } else {
                null
            }
            val t0 = System.nanoTime()
            val quality = qualityAnalyzer.analyze(
                result.bitmap,
                android.graphics.Rect(0, 0, result.bitmap.width, result.bitmap.height),
            )
            val qualityMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1)
            val message = if (skeletonBitmap == null) {
                "Ridge quality too low for skeleton"
            } else {
                null
            }
            _uiState.value = _uiState.value.copy(
                enhancedBitmap = result.bitmap,
                ridgeBitmap = ridgeBitmap,
                skeletonBitmap = skeletonBitmap,
                maskBitmap = maskBitmap,
                steps = result.steps + com.sitta.core.domain.EnhancementStep("Quality Check", qualityMs),
                qualityResult = quality,
                message = message,
            )
            sessionRepository.saveBitmap(session, ArtifactFilenames.ENHANCED, result.bitmap)
            if (skeletonBitmap != null) {
                sessionRepository.saveBitmap(session, ArtifactFilenames.SKELETON, skeletonBitmap)
            }
        }.onFailure {
            _uiState.value = _uiState.value.copy(message = "Enhancement failed. Please try again.")
        }
    }

    private fun computeRidgeDensity(bitmap: Bitmap): Double {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var active = 0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xff
            val g = (pixel shr 8) and 0xff
            val b = pixel and 0xff
            if (r + g + b > 20) active++
        }
        val total = (width * height).toDouble()
        return if (total > 0) active / total else 0.0
    }

    private fun saveToGallery(bitmap: Bitmap): Uri? {
        val resolver = appContext.contentResolver
        val name = "sitta_enhanced_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SITTA")
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    return null
                }
            } ?: return null
            uri
        } catch (_: Throwable) {
            null
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

data class TrackBUiState(
    val rawBitmap: Bitmap? = null,
    val enhancedBitmap: Bitmap? = null,
    val ridgeBitmap: Bitmap? = null,
    val skeletonBitmap: Bitmap? = null,
    val maskBitmap: Bitmap? = null,
    val steps: List<com.sitta.core.domain.EnhancementStep> = emptyList(),
    val qualityResult: QualityResult? = null,
    val sharpenStrength: Float = 1f,
    val session: SessionInfo? = null,
    val message: String? = null,
)
