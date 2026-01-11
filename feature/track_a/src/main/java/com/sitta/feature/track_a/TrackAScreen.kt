@file:Suppress("DEPRECATION")

package com.sitta.feature.track_a

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sitta.feature.track_a.BuildConfig
import com.sitta.core.common.DefaultConfig
import com.sitta.core.data.AuthManager
import com.sitta.core.data.SessionRepository
import com.sitta.core.data.SettingsRepository
import com.sitta.core.domain.LivenessDetector
import com.sitta.core.domain.QualityAnalyzer
import com.sitta.core.vision.FingerDetector
import com.sitta.core.vision.FingerMasker
import com.sitta.core.vision.FingerSceneAnalyzer
import java.util.concurrent.Executors

class TrackAViewModelFactory(
    private val sessionRepository: SessionRepository,
    private val authManager: AuthManager,
    private val settingsRepository: SettingsRepository,
    private val qualityAnalyzer: QualityAnalyzer,
    private val livenessDetector: LivenessDetector,
    private val fingerDetector: FingerDetector,
    private val fingerSceneAnalyzer: FingerSceneAnalyzer,
    private val fingerMasker: FingerMasker,
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
                fingerDetector,
                fingerSceneAnalyzer,
                fingerMasker,
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
    fingerDetector: FingerDetector,
    fingerSceneAnalyzer: FingerSceneAnalyzer,
    fingerMasker: FingerMasker,
    onCaptureComplete: () -> Unit,
    onBack: () -> Unit,
    enableCamera: Boolean = true,
) {
    val viewModel: TrackAViewModel = viewModel(
        factory = TrackAViewModelFactory(
            sessionRepository,
            authManager,
            settingsRepository,
            qualityAnalyzer,
            livenessDetector,
            fingerDetector,
            fingerSceneAnalyzer,
            fingerMasker,
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
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var useFrontCamera by remember { mutableStateOf(false) }

    val cameraControlState = remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, enableCamera, useFrontCamera) {
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
                    val roi = buildLeftEllipseRoi(bitmap.width, bitmap.height)
                    viewModel.onFrame(bitmap, roi, System.currentTimeMillis())
                } catch (t: Throwable) {
                    Log.w("TrackA", "Frame analysis failed", t)
                } finally {
                    imageProxy.close()
                }
            }
            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                cameraControlState.value = camera.cameraControl
            } catch (t: Throwable) {
                Log.e("TrackA", "Camera binding failed", t)
            }
        }, executor)

        onDispose {
            runCatching { cameraProviderFuture.get().unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    val quality = uiState.qualityResult
    val blurTarget = DefaultConfig.value.blurThreshold
    val focusScore = quality?.metrics?.blurScore?.let {
        ((it / blurTarget) * 100).toInt().coerceIn(0, 100)
    } ?: 0
    val lightScore = quality?.metrics?.illuminationMean?.let {
        val min = DefaultConfig.value.illuminationMin
        val max = DefaultConfig.value.illuminationMax
        when {
            it < min -> ((it / min) * 100).toInt()
            it > max -> ((max / it) * 100).toInt()
            else -> 100
        }.coerceIn(0, 100)
    } ?: 0
    val centerScore = uiState.centerScore

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { previewView },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0xFF0B0D11).copy(alpha = 0.35f),
                            0.45f to Color.Transparent,
                            1f to Color(0xFF0B0D11).copy(alpha = 0.55f),
                        ),
                    ),
            )
            CameraOverlay(isReady = uiState.captureEnabled)
            if (BuildConfig.DEBUG) {
                LandmarkOverlay(landmarks = uiState.detection?.landmarks.orEmpty())
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    TopBar(onBack = onBack)
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0B0F14).copy(alpha = 0.9f), RoundedCornerShape(18.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(horizontalArrangement = Arrangement.Center) {
                            StatusChip("Focus", focusScore, quality?.passes?.focus == true)
                            Spacer(modifier = Modifier.width(8.dp))
                            StatusChip("Light", lightScore, quality?.passes?.light == true)
                            Spacer(modifier = Modifier.width(8.dp))
                            StatusChip("Center", centerScore, quality?.passes?.coverage == true)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Cumulative ${quality?.score0To100 ?: 0}",
                            color = Color(0xFFB8C0CC),
                            fontSize = 12.sp,
                        )
                    }
                }

                StatusBanner(
                    isReady = uiState.captureEnabled,
                    message = uiState.message,
                )

                BottomCaptureBar(
                    captureEnabled = uiState.captureEnabled,
                    onCapture = { viewModel.capture() },
                    onFlashToggle = {
                        torchEnabled = !torchEnabled
                        cameraControlState.value?.enableTorch(torchEnabled)
                    },
                    torchEnabled = torchEnabled,
                    onSwitchCamera = { useFrontCamera = !useFrontCamera },
                )
            }
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(icon = Icons.Outlined.ArrowBack, contentDescription = "Back", onClick = onBack)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Biometric Capture",
                color = Color(0xFFB7BDC7),
                fontSize = 11.sp,
                letterSpacing = 1.2.sp,
            )
        }
        Spacer(modifier = Modifier.size(44.dp))
    }
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(Color(0xCC0B0D11), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = Color.White)
    }
}

@Composable
private fun CameraOverlay(isReady: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val guideWidth = size.width * 0.52f
        val guideHeight = size.height * 0.68f
        val left = size.width * 0.08f
        val top = (size.height - guideHeight) / 2f
        val borderColor = if (isReady) Color(0xFF10B981) else Color(0xFFF97316)

        drawOval(
            color = borderColor,
            topLeft = Offset(left, top),
            size = Size(guideWidth, guideHeight),
            style = Stroke(width = 4.dp.toPx()),
        )

        val crossX = left + guideWidth * 0.5f
        val crossY = top + guideHeight * 0.5f
        drawLine(
            color = Color.White.copy(alpha = 0.35f),
            start = Offset(crossX - 18.dp.toPx(), crossY),
            end = Offset(crossX + 18.dp.toPx(), crossY),
            strokeWidth = 2.dp.toPx(),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.35f),
            start = Offset(crossX, crossY - 18.dp.toPx()),
            end = Offset(crossX, crossY + 18.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

@Composable
private fun StatusBanner(isReady: Boolean, message: String?) {
    val text = when {
        isReady -> "✓ Ready to capture"
        message != null -> message
        else -> "Adjusting..."
    }
    val color = when {
        isReady -> Color(0xFF34D399)
        message != null -> Color(0xFFFBBF24)
        else -> Color(0xFFF97316)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text = text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BottomCaptureBar(
    captureEnabled: Boolean,
    onCapture: () -> Unit,
    onFlashToggle: () -> Unit,
    torchEnabled: Boolean,
    onSwitchCamera: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        SideActionButton(
            icon = if (torchEnabled) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
            contentDescription = "Toggle flash",
            onClick = onFlashToggle,
        )
        Spacer(modifier = Modifier.width(24.dp))
        CaptureButton(enabled = captureEnabled, onClick = onCapture)
        Spacer(modifier = Modifier.width(24.dp))
        SideActionButton(
            icon = Icons.Outlined.FlipCameraAndroid,
            contentDescription = "Switch camera",
            onClick = onSwitchCamera,
        )
    }
}

@Composable
private fun SideActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .background(Color(0x401F2937), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = Color.White)
    }
}

@Composable
private fun CaptureButton(enabled: Boolean, onClick: () -> Unit) {
    val outerColor = if (enabled) Color(0xFF14B8A6) else Color(0xFF6B7280)
    val innerColor = if (enabled) Color(0xFF14B8A6) else Color(0x4DFFFFFF)
    Box(
        modifier = Modifier
            .size(84.dp)
            .background(Color.Transparent, CircleShape)
            .semantics { contentDescription = "Capture" }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(color = outerColor, style = Stroke(width = 4.dp.toPx()))
            drawCircle(color = innerColor, radius = size.minDimension / 2.6f)
        }
    }
}

@Composable
private fun StatusChip(label: String, score: Int, pass: Boolean) {
    val colors = when {
        pass -> AssistChipDefaults.assistChipColors(
            containerColor = Color(0x1A10B981),
            labelColor = Color(0xFF34D399),
        )
        score >= 60 -> AssistChipDefaults.assistChipColors(
            containerColor = Color(0x1AF97316),
            labelColor = Color(0xFFFBBF24),
        )
        else -> AssistChipDefaults.assistChipColors(
            containerColor = Color(0x1AEF4444),
            labelColor = Color(0xFFF87171),
        )
    }
    AssistChip(
        onClick = {},
        label = { Text(text = "$label ${score}%", fontSize = 12.sp) },
        colors = colors,
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun LandmarkOverlay(landmarks: List<com.sitta.core.vision.FingerLandmark>) {
    if (landmarks.isEmpty()) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val pairs = listOf(
            com.sitta.core.vision.LandmarkType.INDEX_DIP to com.sitta.core.vision.LandmarkType.INDEX_TIP,
            com.sitta.core.vision.LandmarkType.MIDDLE_DIP to com.sitta.core.vision.LandmarkType.MIDDLE_TIP,
            com.sitta.core.vision.LandmarkType.RING_DIP to com.sitta.core.vision.LandmarkType.RING_TIP,
            com.sitta.core.vision.LandmarkType.PINKY_DIP to com.sitta.core.vision.LandmarkType.PINKY_TIP,
        )
        pairs.forEach { (dipType, tipType) ->
            val dip = landmarks.firstOrNull { it.type == dipType }
            val tip = landmarks.firstOrNull { it.type == tipType }
            if (dip != null && tip != null) {
                val dipPt = Offset(dip.x * size.width, dip.y * size.height)
                val tipPt = Offset(tip.x * size.width, tip.y * size.height)
                drawLine(
                    color = Color(0xFF38D39F),
                    start = dipPt,
                    end = tipPt,
                    strokeWidth = 2.dp.toPx(),
                )
                drawCircle(Color(0xFF38D39F), radius = 4.dp.toPx(), center = tipPt)
            }
        }
    }
}
