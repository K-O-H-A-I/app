package com.sitta.feature.track_b

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sitta.core.common.AppResult
import com.sitta.core.common.ArtifactFilenames
import com.sitta.core.common.SessionInfo
import com.sitta.core.data.AuthManager
import com.sitta.core.data.SessionRepository
import com.sitta.core.domain.EnhancementPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TrackBViewModel(
    private val sessionRepository: SessionRepository,
    private val authManager: AuthManager,
    private val enhancementPipeline: EnhancementPipeline,
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
            val rawFile = sessionRepository.loadBitmap(tenantId, session.sessionId, ArtifactFilenames.RAW)
            if (rawFile == null) {
                _uiState.value = _uiState.value.copy(message = "raw.png not found")
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
            _uiState.value = _uiState.value.copy(message = "Saved to session")
        }
    }

    private suspend fun processEnhancement(bitmap: Bitmap, session: SessionInfo, strength: Float) {
        val enhanced = enhancementPipeline.enhance(bitmap, strength)
        _uiState.value = _uiState.value.copy(enhancedBitmap = enhanced)
        sessionRepository.saveBitmap(session, ArtifactFilenames.ENHANCED, enhanced)
    }
}

data class TrackBUiState(
    val rawBitmap: Bitmap? = null,
    val enhancedBitmap: Bitmap? = null,
    val sharpenStrength: Float = 1f,
    val session: SessionInfo? = null,
    val message: String? = null,
)
