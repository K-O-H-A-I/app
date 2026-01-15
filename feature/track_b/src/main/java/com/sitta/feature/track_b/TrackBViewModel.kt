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
            val rawFile = sessionRepository.loadBitmap(tenantId, session.sessionId, ArtifactFilenames.SEGMENTED)
                ?: sessionRepository.loadBitmap(tenantId, session.sessionId, ArtifactFilenames.ROI)
                ?: sessionRepository.loadBitmap(tenantId, session.sessionId, ArtifactFilenames.RAW)
            if (rawFile == null) {
                _uiState.value = _uiState.value.copy(message = "Capture not found")
                return@launch
            }
            val rawBitmap = BitmapFactory.decodeFile(rawFile.absolutePath)
            _uiState.value = _uiState.value.copy(rawBitmap = rawBitmap, session = session, message = null)
            processEnhancement(rawBitmap, session, _uiState.value.sharpenStrength)
        }
    }

    fun updateSharpenStrength(value: Float) {
        _uiState.value = _uiState.value.copy(sharpenStrength = value)
        val raw = _uiState.value.rawBitmap ?: return
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            processEnhancement(raw, session, value)
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

    private suspend fun processEnhancement(bitmap: Bitmap, session: SessionInfo, strength: Float) {
        runCatching {
            val result = enhancementPipeline.enhance(bitmap, strength)
            val ridgeBitmap = ridgeExtractor.extractRidge(result.bitmap)
            val skeletonBitmap = skeletonizer.skeletonize(ridgeBitmap)
            val t0 = System.nanoTime()
            val quality = qualityAnalyzer.analyze(
                result.bitmap,
                android.graphics.Rect(0, 0, result.bitmap.width, result.bitmap.height),
            )
            val qualityMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1)
            _uiState.value = _uiState.value.copy(
                enhancedBitmap = result.bitmap,
                ridgeBitmap = ridgeBitmap,
                skeletonBitmap = skeletonBitmap,
                steps = result.steps + com.sitta.core.domain.EnhancementStep("Quality Check", qualityMs),
                qualityResult = quality,
                message = null,
            )
            sessionRepository.saveBitmap(session, ArtifactFilenames.ENHANCED, result.bitmap)
            sessionRepository.saveBitmap(session, ArtifactFilenames.SKELETON, skeletonBitmap)
        }.onFailure {
            _uiState.value = _uiState.value.copy(message = "Enhancement failed. Please try again.")
        }
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
    val steps: List<com.sitta.core.domain.EnhancementStep> = emptyList(),
    val qualityResult: QualityResult? = null,
    val sharpenStrength: Float = 1f,
    val session: SessionInfo? = null,
    val message: String? = null,
)
