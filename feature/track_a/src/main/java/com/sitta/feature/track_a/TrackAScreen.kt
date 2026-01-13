@file:Suppress("DEPRECATION")

package com.sitta.feature.track_a

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
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
            if (uiState.captureSource == CaptureSource.AUTO) {
                kotlinx.coroutines.delay(350L)
            }
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
            val preview = Preview.Builder()
                .setTargetRotation(previewView.display.rotation)
                .build()
                .apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setTargetRotation(previewView.display.rotation)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                try {
                    val bitmap = imageProxy.toBitmap()
                    val roi = buildGuideRoi(bitmap.width, bitmap.height)
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
    val steadyScore = quality?.metrics?.stabilityVariance?.let {
        val max = DefaultConfig.value.stabilityMax
        if (it <= 0.0) {
            100
        } else {
            ((max / it).coerceIn(0.0, 1.0) * 100).toInt()
        }
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
            FingerprintGuideOverlay(visible = !uiState.captureEnabled)
            if (BuildConfig.DEBUG && uiState.debugOverlayEnabled) {
                LandmarkOverlay(landmarks = uiState.overlayLandmarks)
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
                    QualityStateRow(
                        focusScore = focusScore,
                        lightScore = lightScore,
                        steadyScore = steadyScore,
                    )
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
                    message = uiState.captureNotice ?: uiState.message,
                )

                StabilityBar(score = steadyScore)

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
        val guideWidth = (size.width * 0.62f).coerceIn(size.width * 0.55f, size.width * 0.75f)
        val guideHeight = (size.height * 0.68f).coerceIn(size.height * 0.58f, size.height * 0.78f)
        val left = 0f
        val top = ((size.height - guideHeight) / 2f) + size.height * 0.08f
        val safeTop = top.coerceAtLeast(0f)
        val rect = androidx.compose.ui.geometry.Rect(left, safeTop, left + guideWidth, safeTop + guideHeight)
        val radius = (minOf(guideWidth, guideHeight) * 0.28f).coerceAtLeast(18.dp.toPx())

        val scrimPath = androidx.compose.ui.graphics.Path().apply {
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
            addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
            addRoundRect(androidx.compose.ui.geometry.RoundRect(rect, radius, radius))
        }
        drawPath(scrimPath, color = Color.Black.copy(alpha = 0.55f))

        val borderColor = if (isReady) Color(0xFF10B981) else Color(0xFF9CA3AF)
        drawRoundRect(
            color = borderColor,
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
            style = Stroke(width = 3.dp.toPx()),
        )

        val gridColor = Color.White.copy(alpha = 0.12f)
        val thirdX = rect.width / 3f
        val thirdY = rect.height / 3f
        drawLine(gridColor, start = Offset(rect.left + thirdX, rect.top), end = Offset(rect.left + thirdX, rect.bottom), strokeWidth = 1.dp.toPx())
        drawLine(gridColor, start = Offset(rect.left + thirdX * 2f, rect.top), end = Offset(rect.left + thirdX * 2f, rect.bottom), strokeWidth = 1.dp.toPx())
        drawLine(gridColor, start = Offset(rect.left, rect.top + thirdY), end = Offset(rect.right, rect.top + thirdY), strokeWidth = 1.dp.toPx())
        drawLine(gridColor, start = Offset(rect.left, rect.top + thirdY * 2f), end = Offset(rect.right, rect.top + thirdY * 2f), strokeWidth = 1.dp.toPx())

        val bracketColor = Color.White.copy(alpha = 0.6f)
        val bracket = 24.dp.toPx()
        val bracketStroke = 3.dp.toPx()
        val inset = 8.dp.toPx()
        drawLine(bracketColor, Offset(rect.left + inset, rect.top + bracket), Offset(rect.left + inset, rect.top + inset), strokeWidth = bracketStroke)
        drawLine(bracketColor, Offset(rect.left + inset, rect.top + inset), Offset(rect.left + bracket, rect.top + inset), strokeWidth = bracketStroke)

        drawLine(bracketColor, Offset(rect.right - inset, rect.top + bracket), Offset(rect.right - inset, rect.top + inset), strokeWidth = bracketStroke)
        drawLine(bracketColor, Offset(rect.right - inset, rect.top + inset), Offset(rect.right - bracket, rect.top + inset), strokeWidth = bracketStroke)

        drawLine(bracketColor, Offset(rect.left + inset, rect.bottom - bracket), Offset(rect.left + inset, rect.bottom - inset), strokeWidth = bracketStroke)
        drawLine(bracketColor, Offset(rect.left + inset, rect.bottom - inset), Offset(rect.left + bracket, rect.bottom - inset), strokeWidth = bracketStroke)

        drawLine(bracketColor, Offset(rect.right - inset, rect.bottom - bracket), Offset(rect.right - inset, rect.bottom - inset), strokeWidth = bracketStroke)
        drawLine(bracketColor, Offset(rect.right - inset, rect.bottom - inset), Offset(rect.right - bracket, rect.bottom - inset), strokeWidth = bracketStroke)
    }
}

@Composable
private fun FingerprintGuideOverlay(visible: Boolean) {
    if (!visible) return
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val roi = buildGuideRoi(constraints.maxWidth, constraints.maxHeight)
        val density = LocalDensity.current
        val widthDp = with(density) { roi.width().toDp() }
        val heightDp = with(density) { roi.height().toDp() }
        Box(
            modifier = Modifier
                .offset { IntOffset(roi.left, roi.top) }
                .size(widthDp, heightDp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.fingerprint_guide),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(0.55f),
                contentScale = ContentScale.Fit,
                alpha = 0.45f,
            )
        }
    }
}

@Composable
private fun QualityStateRow(focusScore: Int, lightScore: Int, steadyScore: Int) {
    val focusState = scoreToState(focusScore)
    val lightState = scoreToState(lightScore)
    val steadyState = scoreToState(steadyScore)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.Center) {
            StatePill(focusState)
            Spacer(modifier = Modifier.width(8.dp))
            StatePill(lightState)
            Spacer(modifier = Modifier.width(8.dp))
            StatePill(steadyState)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            StateLabel("FOCUS")
            Spacer(modifier = Modifier.width(24.dp))
            StateLabel("LIGHT")
            Spacer(modifier = Modifier.width(24.dp))
            StateLabel("STEADY")
        }
    }
}

private enum class QualityState { GOOD, ADJUST, CHECK }

private fun scoreToState(score: Int): QualityState {
    return when {
        score >= 80 -> QualityState.GOOD
        score >= 55 -> QualityState.ADJUST
        else -> QualityState.CHECK
    }
}

@Composable
private fun StatePill(state: QualityState) {
    val (bg, text, label) = when (state) {
        QualityState.GOOD -> Triple(Color(0xFF10B981), Color.White, "Good")
        QualityState.ADJUST -> Triple(Color(0xFFF59E0B), Color.White, "Adjust")
        QualityState.CHECK -> Triple(Color(0xFF4B5563), Color(0xFFE5E7EB), "Checking")
    }
    Box(
        modifier = Modifier
            .background(bg.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = label, color = text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StateLabel(text: String) {
    Text(text = text, color = Color(0xFF94A3B8), fontSize = 10.sp, letterSpacing = 1.1.sp)
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
private fun StabilityBar(score: Int) {
    val clamped = score.coerceIn(0, 100)
    val barColor = when {
        clamped >= 80 -> Color(0xFF10B981)
        clamped >= 55 -> Color(0xFFF59E0B)
        else -> Color(0xFF6B7280)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "Hold Still", color = Color(0xFFCBD5F5), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(text = "${clamped}%", color = Color(0xFFCBD5F5), fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(Color(0xFF0F172A).copy(alpha = 0.6f), RoundedCornerShape(999.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped / 100f)
                    .height(6.dp)
                    .background(barColor, RoundedCornerShape(999.dp)),
            )
        }
    }
    Spacer(modifier = Modifier.height(10.dp))
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
        val chains = listOf(
            listOf(
                com.sitta.core.vision.LandmarkType.THUMB_CMC,
                com.sitta.core.vision.LandmarkType.THUMB_MCP,
                com.sitta.core.vision.LandmarkType.THUMB_IP,
                com.sitta.core.vision.LandmarkType.THUMB_TIP,
            ),
            listOf(
                com.sitta.core.vision.LandmarkType.INDEX_MCP,
                com.sitta.core.vision.LandmarkType.INDEX_PIP,
                com.sitta.core.vision.LandmarkType.INDEX_DIP,
                com.sitta.core.vision.LandmarkType.INDEX_TIP,
            ),
            listOf(
                com.sitta.core.vision.LandmarkType.MIDDLE_MCP,
                com.sitta.core.vision.LandmarkType.MIDDLE_PIP,
                com.sitta.core.vision.LandmarkType.MIDDLE_DIP,
                com.sitta.core.vision.LandmarkType.MIDDLE_TIP,
            ),
            listOf(
                com.sitta.core.vision.LandmarkType.RING_MCP,
                com.sitta.core.vision.LandmarkType.RING_PIP,
                com.sitta.core.vision.LandmarkType.RING_DIP,
                com.sitta.core.vision.LandmarkType.RING_TIP,
            ),
            listOf(
                com.sitta.core.vision.LandmarkType.PINKY_MCP,
                com.sitta.core.vision.LandmarkType.PINKY_PIP,
                com.sitta.core.vision.LandmarkType.PINKY_DIP,
                com.sitta.core.vision.LandmarkType.PINKY_TIP,
            ),
        )
        chains.forEach { chain ->
            val points = chain.mapNotNull { type ->
                landmarks.firstOrNull { it.type == type }?.let {
                    Offset(it.x * size.width, it.y * size.height)
                }
            }
            for (i in 0 until points.size - 1) {
                drawLine(
                    color = Color(0xFF38D39F),
                    start = points[i],
                    end = points[i + 1],
                    strokeWidth = 2.dp.toPx(),
                )
            }
            points.forEach { pt ->
                drawCircle(Color(0xFF38D39F), radius = 3.5.dp.toPx(), center = pt)
            }
        }
    }
}
