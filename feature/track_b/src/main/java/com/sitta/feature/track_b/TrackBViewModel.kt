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
import com.sitta.core.vision.NormalModeRidgeExtractor
import com.sitta.core.vision.FingerSkeletonizer
import com.sitta.core.vision.IsoPngWriter
import com.sitta.core.vision.OpenCvUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

class TrackBViewModel(
    private val sessionRepository: SessionRepository,
    private val authManager: AuthManager,
    private val enhancementPipeline: EnhancementPipeline,
    private val qualityAnalyzer: QualityAnalyzer,
    private val ridgeExtractor: NormalModeRidgeExtractor,
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
                ?: sessionRepository.loadBitmap(tenantId, session.sessionId, ArtifactFilenames.RAW)
            val roiFile = sessionRepository.loadBitmap(tenantId, session.sessionId, ArtifactFilenames.ROI)
                ?: sessionRepository.loadBitmap(tenantId, session.sessionId, ArtifactFilenames.SEGMENTED)
                ?: rawFile
            val maskFile = sessionRepository.loadBitmap(tenantId, session.sessionId, ArtifactFilenames.SEGMENTED)
            if (roiFile == null) {
                _uiState.value = _uiState.value.copy(message = "Capture not found")
                return@launch
            }
            val rawBitmap = rawFile?.let { BitmapFactory.decodeFile(it.absolutePath) }
                ?: BitmapFactory.decodeFile(roiFile.absolutePath)
            val maskBitmap = sessionRepository.loadBitmap(tenantId, session.sessionId, ArtifactFilenames.SEGMENTATION_MASK)
                ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                ?: maskFile?.let { BitmapFactory.decodeFile(it.absolutePath) }
            val processingBitmap = BitmapFactory.decodeFile(roiFile.absolutePath)
            val maskedRaw = if (rawBitmap != null && maskBitmap != null) {
                applyMask(rawBitmap, maskBitmap)
            } else {
                rawBitmap
            }
            _uiState.value = _uiState.value.copy(rawBitmap = maskedRaw, session = session, message = null)
            processEnhancement(processingBitmap, maskBitmap, session, _uiState.value.sharpenStrength)
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

    fun exportEnhanced(format: ExportFormat) {
        val skeleton = _uiState.value.skeletonBitmap
        if (skeleton == null) {
            _uiState.value = _uiState.value.copy(message = "Skeleton not available to export")
            return
        }
        val session = _uiState.value.session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val message = when (format) {
                ExportFormat.PNG -> {
                    sessionRepository.saveBitmap(session, ArtifactFilenames.SKELETON, skeleton)
                    val saved = saveToGallery(skeleton, "image/png", "png")
                    if (saved != null) "Saved PNG skeleton to session and gallery" else "Saved PNG skeleton to session"
                }
                ExportFormat.TIFF -> {
                    val tiffSaved = saveTiffToSession(session, ArtifactFilenames.SKELETON_TIFF, skeleton)
                    val saved = if (tiffSaved != null) {
                        saveFileToGallery(tiffSaved, "image/tiff", "tiff")
                    } else {
                        null
                    }
                    if (saved != null) "Saved TIFF skeleton to session and gallery" else "Saved TIFF skeleton to session"
                }
            }
            _uiState.value = _uiState.value.copy(message = message)
        }
    }

    private suspend fun processEnhancement(bitmap: Bitmap, maskBitmap: Bitmap?, session: SessionInfo, strength: Float) {
        runCatching {
            val result = enhancementPipeline.enhance(bitmap, strength)
            val ridgeResult = ridgeExtractor.extractRidges(result.bitmap, maskBitmap)
            if (ridgeResult == null) {
                _uiState.value = _uiState.value.copy(message = "Ridge extraction failed")
                return
            }
            val ridgeBitmap = ridgeResult.ridgeBitmap
            val ridgeDensity = computeRidgeDensity(ridgeBitmap)
            val skeletonBitmap = if (ridgeDensity in 0.15..0.6) {
                skeletonizer.skeletonize(ridgeBitmap)
            } else {
                skeletonizer.skeletonize(ridgeBitmap)
            }
            val t0 = System.nanoTime()
            val quality = qualityAnalyzer.analyze(
                result.bitmap,
                android.graphics.Rect(0, 0, result.bitmap.width, result.bitmap.height),
            )
            val qualityMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1)
            val message = if (ridgeDensity !in 0.15..0.6) {
                "Ridge quality too low for skeleton (exporting anyway)"
            } else null
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
            sessionRepository.saveBitmap(session, ArtifactFilenames.RIDGES, ridgeBitmap)
            if (skeletonBitmap != null) {
                sessionRepository.saveBitmap(session, ArtifactFilenames.SKELETON, skeletonBitmap)
            }
            saveIsoArtifacts(session, result.bitmap, ridgeBitmap)
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

    private fun saveToGallery(bitmap: Bitmap, mimeType: String, ext: String): Uri? {
        val resolver = appContext.contentResolver
        val name = "sitta_enhanced_${System.currentTimeMillis()}.$ext"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
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

    private fun saveFileToGallery(file: File, mimeType: String, ext: String): Uri? {
        val resolver = appContext.contentResolver
        val name = "sitta_enhanced_${System.currentTimeMillis()}.$ext"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SITTA")
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            } ?: return null
            uri
        } catch (_: Throwable) {
            null
        }
    }

    private fun saveTiffToSession(session: SessionInfo, filename: String, bitmap: Bitmap): File? {
        return runCatching {
            val dir = sessionRepository.sessionDir(session)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, filename)
            if (!OpenCvUtils.saveBitmapAsTiff(bitmap, file)) null else file
        }.getOrNull()
    }

    private fun saveIsoArtifacts(session: SessionInfo, enhanced: Bitmap, ridges: Bitmap) {
        runCatching {
            val dir = sessionRepository.sessionDir(session)
            if (!dir.exists()) dir.mkdirs()
            val enhancedIso = IsoPngWriter.resampleToIso(enhanced, 500, 72)
            IsoPngWriter.savePngWithDpi(enhancedIso, File(dir, ArtifactFilenames.ENHANCED_500DPI), 500)
            val ridgesIso = IsoPngWriter.resampleToIso(ridges, 500, 72)
            IsoPngWriter.savePngWithDpi(ridgesIso, File(dir, ArtifactFilenames.RIDGES_500DPI), 500)
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun applyMask(source: Bitmap, mask: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(source, 0f, 0f, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        val scaledMask = if (mask.width != source.width || mask.height != source.height) {
            Bitmap.createScaledBitmap(mask, source.width, source.height, false)
        } else {
            mask
        }
        canvas.drawBitmap(scaledMask, 0f, 0f, paint)
        paint.xfermode = null
        return out
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

enum class ExportFormat {
    PNG,
    TIFF,
}
