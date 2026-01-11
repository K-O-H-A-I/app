package com.sitta.feature.track_a

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sitta.core.data.AuthManager
import com.sitta.core.data.SessionRepository
import com.sitta.core.data.SettingsRepository
import com.sitta.core.domain.LivenessDetector
import com.sitta.core.domain.QualityAnalyzer
import java.util.concurrent.Executors

class TrackAViewModelFactory(
    private val sessionRepository: SessionRepository,
    private val authManager: AuthManager,
    private val settingsRepository: SettingsRepository,
    private val qualityAnalyzer: QualityAnalyzer,
    private val livenessDetector: LivenessDetector,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TrackAViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TrackAViewModel(
                sessionRepository,
                authManager,
                settingsRepository,
                qualityAnalyzer,
                livenessDetector,
            ) as T
        }
        error("Unknown ViewModel class")
    }
}

@Composable
fun TrackAScreen(
    sessionRepository: SessionRepository,
    authManager: AuthManager,
    settingsRepository: SettingsRepository,
    qualityAnalyzer: QualityAnalyzer,
    livenessDetector: LivenessDetector,
    onCaptureComplete: () -> Unit,
    enableCamera: Boolean = true,
) {
    val viewModel: TrackAViewModel = viewModel(
        factory = TrackAViewModelFactory(
            sessionRepository,
            authManager,
            settingsRepository,
            qualityAnalyzer,
            livenessDetector,
        ),
    )
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.lastSessionId) {
        if (uiState.lastSessionId != null) {
            onCaptureComplete()
        }
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner, enableCamera) {
        if (!enableCamera) {
            return@DisposableEffect onDispose { }
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                try {
                    val bitmap = imageProxy.toBitmap()
                    val roi = buildCenteredRoi(bitmap.width, bitmap.height)
                    viewModel.onFrame(bitmap, roi, System.currentTimeMillis())
                } catch (t: Throwable) {
                    Log.w("TrackA", "Frame analysis failed", t)
                } finally {
                    imageProxy.close()
                }
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (t: Throwable) {
                Log.e("TrackA", "Camera binding failed", t)
            }
        }, executor)

        onDispose {
            runCatching { cameraProviderFuture.get().unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { previewView },
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val roiWidth = size.width * 0.6f
                val roiHeight = size.height * 0.6f
                val left = (size.width - roiWidth) / 2f
                val top = (size.height - roiHeight) / 2f
                val borderColor = if (uiState.captureEnabled) Color(0xFF2E7D32) else Color(0xFFD32F2F)
                drawRect(
                    color = borderColor,
                    topLeft = Offset(left, top),
                    size = Size(roiWidth, roiHeight),
                    style = Stroke(width = 6.dp.toPx()),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val passes = uiState.qualityResult?.passes
                        MetricChip("Focus", passes?.focus == true)
                        MetricChip("Light", passes?.light == true)
                        MetricChip("Coverage", passes?.coverage == true)
                        MetricChip("Steady", passes?.steady == true)
                    }
                    uiState.qualityResult?.let { result ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Score ${result.score0To100} | ${result.topReason}",
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val metrics = result.metrics
                        Text(
                            text = "Blur ${"%.1f".format(metrics.blurScore)} " +
                                "Light ${"%.1f".format(metrics.illuminationMean)} " +
                                "Cover ${"%.2f".format(metrics.coverageRatio)} " +
                                "Stable ${"%.2f".format(metrics.stabilityVariance)}",
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    uiState.message?.let { message ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = message, color = MaterialTheme.colorScheme.error)
                    }
                }
                Button(
                    onClick = { viewModel.capture() },
                    enabled = uiState.captureEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text(text = "Capture")
                }
            }
        }
    }
}

@Composable
private fun MetricChip(label: String, pass: Boolean) {
    val colors = if (pass) {
        AssistChipDefaults.assistChipColors(containerColor = Color(0xFF2E7D32), labelColor = Color.White)
    } else {
        AssistChipDefaults.assistChipColors(containerColor = Color(0xFFD32F2F), labelColor = Color.White)
    }
    AssistChip(
        onClick = {},
        label = { Text(text = label) },
        colors = colors,
        shape = RoundedCornerShape(12.dp),
    )
}
