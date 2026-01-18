@file:Suppress("DEPRECATION")

package com.sitta.feature.track_a

import android.util.Log
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.sitta.core.data.AuthManager
import com.sitta.core.data.ConfigRepo
import com.sitta.core.data.SessionRepository
import java.util.concurrent.atomic.AtomicReference
import com.sitta.core.data.SettingsRepository
import com.sitta.core.domain.LivenessDetector
import com.sitta.core.domain.QualityAnalyzer
import com.sitta.core.vision.FrameCrop
import com.sitta.core.vision.FingerDetector
import com.sitta.core.vision.FingerLandmark
import com.sitta.core.vision.FingerMasker
import com.sitta.core.vision.FingerSceneAnalyzer
import com.sitta.core.vision.CloseUpFingerDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import android.graphics.PointF
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TrackAViewModelFactory(
    private val sessionRepository: SessionRepository,
    private val authManager: AuthManager,
    private val configRepo: ConfigRepo,
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
                configRepo,
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
    configRepo: ConfigRepo,
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
            configRepo,
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
    val previewOutputTransformRef = remember { AtomicReference<OutputTransform?>(null) }
    val lastPreviewTransformRef = remember { AtomicReference<OutputTransform?>(null) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    DisposableEffect(previewView) {
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            @OptIn(TransformExperimental::class)
            previewOutputTransformRef.set(previewView.outputTransform)
        }
        previewView.addOnLayoutChangeListener(listener)
        @OptIn(TransformExperimental::class)
        previewOutputTransformRef.set(previewView.outputTransform)
        onDispose {
            previewView.removeOnLayoutChangeListener(listener)
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    val yuvConverter = remember { YuvToRgbConverter() }
    val closeUpDetector = remember { CloseUpFingerDetector() }
    val lumaScratch = remember { RoiLumaScratch() }
    var lastLandmarkAtMs by remember { mutableStateOf(0L) }
    var lastCloseUpAtMs by remember { mutableStateOf(0L) }
    var useFrontCamera by remember { mutableStateOf(false) }
    val captureScope = rememberCoroutineScope()
    var captureBurstInFlight by remember { mutableStateOf(false) }

    val cameraControlState = remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }
    val cameraCaptureState = remember { mutableStateOf<ImageCapture?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }
    var autoTorchEnabled by remember { mutableStateOf(true) }
    var lowLightSince by remember { mutableStateOf(0L) }
    var highLightSince by remember { mutableStateOf(0L) }
    var lastTorchChangeMs by remember { mutableStateOf(0L) }
    var imageToPreviewTransform by remember { mutableStateOf<CoordinateTransform?>(null) }
    var previewToImageTransform by remember { mutableStateOf<CoordinateTransform?>(null) }
    var analysisSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var analysisCropRect by remember { mutableStateOf<Rect?>(null) }
    val transformFactory = remember { ImageProxyTransformFactory() }

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
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(100)
                .setTargetRotation(previewView.display.rotation)
                .build()
            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                try {
                    val now = System.currentTimeMillis()
                    @OptIn(TransformExperimental::class)
                    run {
                        val previewTransform = previewOutputTransformRef.get()
                        if (previewTransform != null && previewView.width > 0 && previewView.height > 0) {
                            val imageTransform = transformFactory.getOutputTransform(imageProxy)
                            val previewChanged = lastPreviewTransformRef.get() !== previewTransform
                            if (analysisSize == null ||
                                analysisSize?.first != imageProxy.width ||
                                analysisSize?.second != imageProxy.height ||
                                previewChanged
                            ) {
                                imageToPreviewTransform = CoordinateTransform(imageTransform, previewTransform)
                                previewToImageTransform = CoordinateTransform(previewTransform, imageTransform)
                                analysisSize = imageProxy.width to imageProxy.height
                                lastPreviewTransformRef.set(previewTransform)
                            }
                            analysisCropRect = imageProxy.cropRect
                        }
                    }
                    val cropRect = if (imageProxy.cropRect.width() > 0 && imageProxy.cropRect.height() > 0) {
                        imageProxy.cropRect
                    } else {
                        Rect(0, 0, imageProxy.width, imageProxy.height)
                    }
                    val roi = run {
                        val previewTransform = previewToImageTransform
                        if (previewTransform != null && previewView.width > 0 && previewView.height > 0) {
                            val guidePreview = buildGuideRoi(previewView.width, previewView.height)
                            mapRectToImage(guidePreview, previewTransform, imageProxy.width, imageProxy.height)
                        } else {
                            val guideInCrop = buildGuideRoi(cropRect.width(), cropRect.height())
                            Rect(
                                (guideInCrop.left + cropRect.left).coerceAtLeast(0),
                                (guideInCrop.top + cropRect.top).coerceAtLeast(0),
                                (guideInCrop.right + cropRect.left).coerceAtMost(imageProxy.width),
                                (guideInCrop.bottom + cropRect.top).coerceAtMost(imageProxy.height),
                            )
                        }
                    }
                    val lumaPrimary = extractRoiLumaInto(imageProxy, roi, lumaScratch)
                    val blurPrimary = computeLaplacianVarianceFast(lumaPrimary.luma, lumaPrimary.width, lumaPrimary.height)
                    val illuminationPrimary = lumaPrimary.mean
                    val useFallback = (lumaPrimary.width == 0 || lumaPrimary.height == 0) ||
                        (illuminationPrimary == 0.0 && blurPrimary == 0.0)
                    val fallbackRoi = if (useFallback) cropRect else roi
                    val luma = if (useFallback) {
                        extractRoiLumaInto(imageProxy, fallbackRoi, lumaScratch)
                    } else {
                        lumaPrimary
                    }
                    var blur = if (useFallback) {
                        computeLaplacianVarianceFast(luma.luma, luma.width, luma.height)
                    } else {
                        blurPrimary
                    }
                    var illumination = luma.mean
                    if ((illumination == 0.0 && blur == 0.0) || luma.width == 0 || luma.height == 0) {
                        val fallbackBitmap = yuvConverter.toBitmap(imageProxy, cropRect, 320)
                        val fallbackMetrics = computeBitmapLumaAndBlur(fallbackBitmap)
                        illumination = fallbackMetrics.mean
                        blur = fallbackMetrics.blur
                    }
                    val innerRoi = insetRect(fallbackRoi, 0.15f)
                    val textureLuma = if (innerRoi.width() > 0 && innerRoi.height() > 0) {
                        extractRoiLumaInto(imageProxy, innerRoi, lumaScratch)
                    } else {
                        luma
                    }
                    val edgeDensity = computeEdgeDensity(textureLuma.luma, textureLuma.width, textureLuma.height)
                    val textureVariance = computeTextureVarianceNormalized(textureLuma.luma, textureLuma.width, textureLuma.height)
                    viewModel.onLumaMetrics(
                        blurScore = blur,
                        illuminationMean = illumination,
                        edgeDensity = edgeDensity,
                        textureVariance = textureVariance,
                        roi = fallbackRoi,
                        frameWidth = imageProxy.width,
                        frameHeight = imageProxy.height,
                        timestampMillis = now,
                    )
                    if (TrackACaptureConfig.closeUpDetectorEnabled &&
                        now - lastCloseUpAtMs >= TrackACaptureConfig.closeUpCadenceMs &&
                        fallbackRoi.width() > 0 &&
                        fallbackRoi.height() > 0
                    ) {
                        val closeUpBitmap = yuvConverter.toBitmap(imageProxy, fallbackRoi, 320)
                        val closeUpResult = closeUpDetector.detect(closeUpBitmap)
                        viewModel.onCloseUpFingerResult(closeUpResult, now)
                        lastCloseUpAtMs = now
                    }

                    val cadenceMs = viewModel.landmarkCadenceMs(blur, illumination, now)
                    if (now - lastLandmarkAtMs >= cadenceMs) {
                        val submitTs = maxOf(now, lastLandmarkAtMs + 1)
                        val cropRect = imageProxy.cropRect
                        val bitmap = yuvConverter.toBitmap(imageProxy, cropRect, 480)
                        viewModel.onLandmarksFrame(
                            bitmap,
                            submitTs,
                            FrameCrop(imageProxy.width, imageProxy.height, cropRect),
                        )
                        lastLandmarkAtMs = submitTs
                    }
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
                    capture,
                )
                cameraControlState.value = camera.cameraControl
                cameraCaptureState.value = capture
            } catch (t: Throwable) {
                Log.e("TrackA", "Camera binding failed", t)
            }
        }, executor)

        onDispose {
            runCatching { cameraProviderFuture.get().unbindAll() }
        }
    }

    val quality = uiState.qualityResult
    val focusScore = uiState.focusScore
    val lightScore = uiState.lightScore
    val steadyScore = uiState.steadyScore
    val centerScore = uiState.centerScore

    LaunchedEffect(lightScore, cameraControlState.value) {
        val control = cameraControlState.value ?: return@LaunchedEffect
        if (!autoTorchEnabled) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (lightScore <= TrackACaptureConfig.torchAutoOnScore) {
            if (lowLightSince == 0L) lowLightSince = now
            highLightSince = 0L
            if (!torchEnabled && now - lowLightSince >= TrackACaptureConfig.torchDebounceMs &&
                now - lastTorchChangeMs >= 1200L
            ) {
                torchEnabled = true
                lastTorchChangeMs = now
                control.enableTorch(true)
            }
        } else if (lightScore >= TrackACaptureConfig.torchAutoOffScore) {
            if (highLightSince == 0L) highLightSince = now
            lowLightSince = 0L
            if (torchEnabled && now - highLightSince >= TrackACaptureConfig.torchDebounceMs &&
                now - lastTorchChangeMs >= TrackACaptureConfig.torchMinOnMs
            ) {
                torchEnabled = false
                lastTorchChangeMs = now
                control.enableTorch(false)
            }
        } else {
            lowLightSince = 0L
            highLightSince = 0L
        }
    }

    LaunchedEffect(uiState.focusRequested, uiState.overlayLandmarks, cameraControlState.value, imageToPreviewTransform, analysisSize, analysisCropRect) {
        if (!uiState.focusRequested) return@LaunchedEffect
        val control = cameraControlState.value ?: return@LaunchedEffect
        if (previewView.width == 0 || previewView.height == 0) return@LaunchedEffect
        val mapped = mapLandmarksToPreview(
            landmarks = uiState.overlayLandmarks,
            imageToPreview = imageToPreviewTransform,
            analysisSize = analysisSize,
            analysisCrop = analysisCropRect,
            previewView = previewView,
        )
        val normalized = computeFocusPoint(mapped) ?: return@LaunchedEffect
        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(
            normalized.x * previewView.width,
            normalized.y * previewView.height,
        )
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(800, TimeUnit.MILLISECONDS)
            .build()
        control.startFocusAndMetering(action).addListener(
            { viewModel.onFocusMeteringSettled() },
            ContextCompat.getMainExecutor(context),
        )
    }

    LaunchedEffect(uiState.autoCaptureRequested, uiState.autoCaptureToken, cameraCaptureState.value) {
        if (!uiState.autoCaptureRequested && uiState.autoCaptureToken == 0L) return@LaunchedEffect
        val imageCapture = cameraCaptureState.value ?: return@LaunchedEffect
        if (captureBurstInFlight) return@LaunchedEffect
        if (!viewModel.startAutoCapture()) return@LaunchedEffect
        captureBurstInFlight = true
        try {
            var best = captureBestOfN(
                imageCapture = imageCapture,
                captureExecutor = captureExecutor,
                converter = yuvConverter,
                n = 1,
                scoreFn = { bitmap ->
                    withContext(Dispatchers.Default) { viewModel.scoreCandidate(bitmap) }
                },
            )
            if (best == null) {
                kotlinx.coroutines.delay(250L)
                best = captureBestOfN(
                    imageCapture = imageCapture,
                    captureExecutor = captureExecutor,
                    converter = yuvConverter,
                    n = 1,
                    scoreFn = { bitmap ->
                        withContext(Dispatchers.Default) { viewModel.scoreCandidate(bitmap) }
                    },
                )
            }
            if (best != null) {
                viewModel.captureFromBitmap(best, CaptureSource.AUTO)
            } else {
                viewModel.capture()
            }
        } catch (t: Throwable) {
            Log.e("TrackA", "Auto capture failed", t)
            viewModel.capture()
        } finally {
            captureBurstInFlight = false
        }
    }

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
            val orientationAngle = uiState.detection?.orientationAngle
            val isHorizontal = orientationAngle?.let { kotlin.math.abs(it) in 45f..135f } ?: true
            val mappedLandmarks = mapLandmarksToPreview(
                landmarks = uiState.overlayLandmarks,
                imageToPreview = imageToPreviewTransform,
                analysisSize = analysisSize,
                analysisCrop = analysisCropRect,
                previewView = previewView,
            )
            CameraOverlay(isReady = uiState.captureEnabled)
            FingerprintGuideOverlay(visible = !uiState.captureEnabled, horizontal = isHorizontal)
            if (BuildConfig.DEBUG && uiState.debugOverlayEnabled) {
                LandmarkOverlay(landmarks = mappedLandmarks)
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
                            StatusChip("Focus", focusScore, focusScore >= TrackACaptureConfig.focusEnterScore)
                            Spacer(modifier = Modifier.width(8.dp))
                            StatusChip("Light", lightScore, lightScore >= TrackACaptureConfig.lightEnterScore)
                            Spacer(modifier = Modifier.width(8.dp))
                            StatusChip("Center", centerScore, centerScore >= TrackACaptureConfig.centerEnterScore)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Cumulative ${quality?.score0To100 ?: 0}",
                            color = Color(0xFFB8C0CC),
                            fontSize = 12.sp,
                        )
                        if (uiState.debugOverlayEnabled) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "CloseUp ${"%.2f".format(uiState.closeUpConfidence)} " +
                                    "skin ${"%.2f".format(uiState.closeUpSkinRatio)} " +
                                    "ridge ${"%.2f".format(uiState.closeUpRidgeScore)} " +
                                    "edge ${"%.2f".format(uiState.closeUpEdgeScore)}",
                                color = Color(0xFF93A3B5),
                                fontSize = 11.sp,
                            )
                        }
                    }
                }

                StatusBanner(
                    isReady = uiState.captureEnabled,
                    message = uiState.captureNotice ?: uiState.message,
                )

                StabilityBar(score = uiState.autoCaptureProgress)

                BottomCaptureBar(
                    captureEnabled = uiState.captureEnabled,
                    onCapture = {
                        val imageCapture = cameraCaptureState.value
                        if (imageCapture == null) {
                            viewModel.capture()
                            return@BottomCaptureBar
                        }
                        if (captureBurstInFlight) return@BottomCaptureBar
                        captureBurstInFlight = true
                        captureScope.launch {
                            try {
                                val best = captureBestOfN(
                                    imageCapture = imageCapture,
                                    captureExecutor = captureExecutor,
                                    converter = yuvConverter,
                                    n = 1,
                                    scoreFn = { bitmap ->
                                        withContext(Dispatchers.Default) { viewModel.scoreCandidate(bitmap) }
                                    },
                                )
                                if (best != null) {
                                    viewModel.captureFromBitmap(best, CaptureSource.MANUAL)
                                } else {
                                    viewModel.capture()
                                }
                            } catch (t: Throwable) {
                                Log.e("TrackA", "Best-of capture failed", t)
                                viewModel.capture()
                            } finally {
                                captureBurstInFlight = false
                            }
                        }
                    },
                    onFlashToggle = {
                        torchEnabled = !torchEnabled
                        autoTorchEnabled = false
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
        val guideWidth = (size.width * 0.68f).coerceIn(size.width * 0.6f, size.width * 0.78f)
        val guideHeight = (size.height * 0.52f).coerceIn(size.height * 0.45f, size.height * 0.62f)
        val left = ((size.width - guideWidth) / 2f).coerceAtLeast(0f)
        val top = ((size.height - guideHeight) / 2f).coerceAtLeast(0f)
        val safeTop = top
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
private fun FingerprintGuideOverlay(visible: Boolean, horizontal: Boolean) {
    if (!visible) return
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val roi = buildGuideRoi(constraints.maxWidth, constraints.maxHeight)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rect = androidx.compose.ui.geometry.Rect(
                roi.left.toFloat(),
                roi.top.toFloat(),
                roi.right.toFloat(),
                roi.bottom.toFloat(),
            )
            val radius = (minOf(rect.width, rect.height) * 0.065f).coerceAtLeast(7.dp.toPx())
            if (horizontal) {
                val baseY = rect.top + rect.height * 0.3f
                val xSteps = listOf(0.20f, 0.40f, 0.60f, 0.80f)
                val yOffsets = listOf(0.02f, 0.0f, -0.01f, 0.01f)
                xSteps.forEachIndexed { index, fx ->
                    val cx = rect.left + rect.width * fx
                    val cy = baseY + rect.height * yOffsets[index]
                    drawCircle(
                        color = Color(0xFF14B8A6).copy(alpha = 0.45f),
                        radius = radius,
                        center = Offset(cx, cy),
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.25f),
                        radius = radius * 0.6f,
                        center = Offset(cx, cy),
                    )
                }
            } else {
                val baseX = rect.left + rect.width * 0.35f
                val ySteps = listOf(0.22f, 0.40f, 0.60f, 0.78f)
                val xOffsets = listOf(0.02f, 0.0f, -0.01f, 0.01f)
                ySteps.forEachIndexed { index, fy ->
                    val cx = baseX + rect.width * xOffsets[index]
                    val cy = rect.top + rect.height * fy
                    drawCircle(
                        color = Color(0xFF14B8A6).copy(alpha = 0.45f),
                        radius = radius,
                        center = Offset(cx, cy),
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.25f),
                        radius = radius * 0.6f,
                        center = Offset(cx, cy),
                    )
                }
            }
        }
    }
}

@Composable
private fun QualityStateRow(focusScore: Int, lightScore: Int, steadyScore: Int) {
    val focusState = scoreToState(focusScore, TrackACaptureConfig.focusEnterScore)
    val lightState = scoreToState(lightScore, TrackACaptureConfig.lightEnterScore)
    val steadyState = scoreToState(steadyScore, TrackACaptureConfig.steadyEnterScore)
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

private fun scoreToState(score: Int, goodThreshold: Int): QualityState {
    return when {
        score >= goodThreshold -> QualityState.GOOD
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
        message != null -> message
        isReady -> "✓ Ready to capture"
        else -> "Adjusting..."
    }
    val color = when {
        message?.startsWith("Auto capture") == true -> Color(0xFF34D399)
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
        Text(text = text, color = color, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StabilityBar(score: Int) {
    val clamped = score.coerceIn(0, 100)
    val barColor = when {
        clamped >= TrackACaptureConfig.steadyEnterScore -> Color(0xFF10B981)
        clamped >= TrackACaptureConfig.steadyExitScore -> Color(0xFFF59E0B)
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

private fun computeFocusPoint(landmarks: List<FingerLandmark>): Offset? {
    if (landmarks.isEmpty()) return null
    val tips = landmarks.filter {
        it.type == com.sitta.core.vision.LandmarkType.INDEX_TIP ||
            it.type == com.sitta.core.vision.LandmarkType.MIDDLE_TIP ||
            it.type == com.sitta.core.vision.LandmarkType.RING_TIP ||
            it.type == com.sitta.core.vision.LandmarkType.PINKY_TIP
    }
    val points = if (tips.isNotEmpty()) tips else landmarks
    val avgX = points.map { it.x }.average().toFloat()
    val avgY = points.map { it.y }.average().toFloat()
    return Offset(avgX.coerceIn(0f, 1f), avgY.coerceIn(0f, 1f))
}

@OptIn(TransformExperimental::class)
private fun mapLandmarksToPreview(
    landmarks: List<FingerLandmark>,
    imageToPreview: CoordinateTransform?,
    analysisSize: Pair<Int, Int>?,
    analysisCrop: Rect?,
    previewView: PreviewView,
): List<FingerLandmark> {
    if (landmarks.isEmpty() || imageToPreview == null || analysisSize == null ||
        previewView.width == 0 || previewView.height == 0
    ) {
        return landmarks
    }
    val (imageW, imageH) = analysisSize
    return landmarks.mapNotNull { lm ->
        val point = PointF(
            lm.x * imageW,
            lm.y * imageH,
        )
        imageToPreview.mapPoint(point)
        if (point.x.isNaN() || point.y.isNaN()) return@mapNotNull null
        val nx = (point.x / previewView.width).coerceIn(0f, 1f)
        val ny = (point.y / previewView.height).coerceIn(0f, 1f)
        FingerLandmark(
            x = nx,
            y = ny,
            z = lm.z,
            type = lm.type,
        )
    }
}

@OptIn(TransformExperimental::class)
private fun mapRectToImage(
    previewRect: Rect,
    previewToImage: CoordinateTransform,
    imageWidth: Int,
    imageHeight: Int,
): Rect {
    if (previewRect.width() <= 0 || previewRect.height() <= 0) {
        return Rect(0, 0, imageWidth, imageHeight)
    }
    val points = arrayOf(
        PointF(previewRect.left.toFloat(), previewRect.top.toFloat()),
        PointF(previewRect.right.toFloat(), previewRect.top.toFloat()),
        PointF(previewRect.right.toFloat(), previewRect.bottom.toFloat()),
        PointF(previewRect.left.toFloat(), previewRect.bottom.toFloat()),
    )
    points.forEach { previewToImage.mapPoint(it) }
    val minX = points.minOf { it.x }.coerceIn(0f, imageWidth.toFloat())
    val maxX = points.maxOf { it.x }.coerceIn(0f, imageWidth.toFloat())
    val minY = points.minOf { it.y }.coerceIn(0f, imageHeight.toFloat())
    val maxY = points.maxOf { it.y }.coerceIn(0f, imageHeight.toFloat())
    return Rect(
        minX.toInt().coerceAtLeast(0),
        minY.toInt().coerceAtLeast(0),
        maxX.toInt().coerceAtMost(imageWidth),
        maxY.toInt().coerceAtMost(imageHeight),
    )
}

private fun centerCropRect(rect: Rect, fraction: Float): Rect {
    val safeFraction = fraction.coerceIn(0.2f, 1f)
    val width = rect.width()
    val height = rect.height()
    if (width <= 0 || height <= 0) return rect
    val cropW = (width * safeFraction).toInt().coerceAtLeast(1)
    val cropH = (height * safeFraction).toInt().coerceAtLeast(1)
    val left = rect.left + (width - cropW) / 2
    val top = rect.top + (height - cropH) / 2
    return Rect(left, top, left + cropW, top + cropH)
}

private data class LumaRoi(
    val luma: ByteArray,
    val width: Int,
    val height: Int,
    val mean: Double,
    val glarePct: Double,
)

private class RoiLumaScratch {
    var buf: ByteArray = ByteArray(0)
    var capacity: Int = 0
}

private fun extractRoiLumaInto(image: ImageProxy, roi: Rect, scratch: RoiLumaScratch): LumaRoi {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val width = roi.width().coerceAtLeast(0)
    val height = roi.height().coerceAtLeast(0)
    if (width == 0 || height == 0) return LumaRoi(ByteArray(0), 0, 0, 0.0, 0.0)

    val needed = width * height
    if (scratch.capacity < needed) {
        scratch.buf = ByteArray(needed)
        scratch.capacity = needed
    }
    val out = scratch.buf

    var sum = 0L
    var bright = 0L
    var outIndex = 0
    val limit = buffer.limit()
    val start = roi.top * rowStride + roi.left * pixelStride

    for (row in 0 until height) {
        val rowStart = start + row * rowStride
        if (pixelStride == 1) {
            for (x in 0 until width) {
                val idx = rowStart + x
                val v = if (idx in 0 until limit) {
                    buffer.get(idx).toInt() and 0xFF
                } else {
                    0
                }
                out[outIndex++] = v.toByte()
                sum += v
                if (v > 245) bright++
            }
        } else {
            for (x in 0 until width) {
                val idx = rowStart + x * pixelStride
                val v = if (idx in 0 until limit) {
                    buffer.get(idx).toInt() and 0xFF
                } else {
                    0
                }
                out[outIndex++] = v.toByte()
                sum += v
                if (v > 245) bright++
            }
        }
    }

    val total = (width * height).toDouble()
    return LumaRoi(
        luma = out,
        width = width,
        height = height,
        mean = if (total > 0) sum / total else 0.0,
        glarePct = if (total > 0) bright / total else 0.0,
    )
}

private fun computeLaplacianVarianceFast(luma: ByteArray, width: Int, height: Int): Double {
    if (width < 3 || height < 3) return 0.0
    var mean = 0.0
    var m2 = 0.0
    var count = 0L
    for (y in 1 until height - 1) {
        val row = y * width
        for (x in 1 until width - 1) {
            val idx = row + x
            val c = luma[idx].toInt() and 0xFF
            val u = luma[idx - width].toInt() and 0xFF
            val d = luma[idx + width].toInt() and 0xFF
            val l = luma[idx - 1].toInt() and 0xFF
            val r = luma[idx + 1].toInt() and 0xFF
            val lap = (u + d + l + r - (c shl 2)).toDouble()
            count++
            val delta = lap - mean
            mean += delta / count.toDouble()
            m2 += delta * (lap - mean)
        }
    }
    return if (count > 1) m2 / count.toDouble() else 0.0
}

private data class BitmapLumaMetrics(
    val mean: Double,
    val blur: Double,
)

private fun computeBitmapLumaAndBlur(bitmap: Bitmap): BitmapLumaMetrics {
    val width = bitmap.width.coerceAtLeast(1)
    val height = bitmap.height.coerceAtLeast(1)
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    var sum = 0L
    for (i in pixels.indices) {
        val c = pixels[i]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        sum += (0.2126 * r + 0.7152 * g + 0.0722 * b).toInt()
    }
    val mean = sum.toDouble() / pixels.size.toDouble()

    if (width < 3 || height < 3) {
        return BitmapLumaMetrics(mean = mean, blur = 0.0)
    }

    var lapMean = 0.0
    var lapM2 = 0.0
    var count = 0L
    fun lumaAt(x: Int, y: Int): Int {
        val c = pixels[y * width + x]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (0.2126 * r + 0.7152 * g + 0.0722 * b).toInt()
    }
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val c = lumaAt(x, y)
            val u = lumaAt(x, y - 1)
            val d = lumaAt(x, y + 1)
            val l = lumaAt(x - 1, y)
            val r = lumaAt(x + 1, y)
            val lap = (u + d + l + r - (c shl 2)).toDouble()
            count++
            val delta = lap - lapMean
            lapMean += delta / count.toDouble()
            lapM2 += delta * (lap - lapMean)
        }
    }
    val blur = if (count > 1) lapM2 / count.toDouble() else 0.0
    return BitmapLumaMetrics(mean = mean, blur = blur)
}

private fun insetRect(rect: Rect, ratio: Float): Rect {
    val dx = (rect.width() * ratio).toInt()
    val dy = (rect.height() * ratio).toInt()
    return Rect(
        (rect.left + dx).coerceAtMost(rect.right),
        (rect.top + dy).coerceAtMost(rect.bottom),
        (rect.right - dx).coerceAtLeast(rect.left),
        (rect.bottom - dy).coerceAtLeast(rect.top),
    )
}

private fun computeEdgeDensity(luma: ByteArray, width: Int, height: Int): Double {
    if (width < 3 || height < 3) return 0.0
    var edgePixels = 0
    var total = 0
    val step = 2
    val marginX = (width * 0.05f).toInt()
    val marginY = (height * 0.05f).toInt()
    val startX = maxOf(1, marginX)
    val endX = (width - 1 - marginX).coerceAtLeast(startX + 1)
    val startY = maxOf(1, marginY)
    val endY = (height - 1 - marginY).coerceAtLeast(startY + 1)
    for (y in startY until endY step step) {
        val row = y * width
        for (x in startX until endX step step) {
            val idx = row + x
            val gx = (luma[idx + 1].toInt() and 0xFF) - (luma[idx - 1].toInt() and 0xFF)
            val gy = (luma[idx + width].toInt() and 0xFF) - (luma[idx - width].toInt() and 0xFF)
            val magnitude = kotlin.math.abs(gx) + kotlin.math.abs(gy)
            if (magnitude > 30) edgePixels++
            total++
        }
    }
    return if (total > 0) edgePixels.toDouble() / total else 0.0
}

private fun computeTextureVarianceNormalized(luma: ByteArray, width: Int, height: Int): Double {
    if (width == 0 || height == 0) return 0.0
    val step = 2
    var mean = 0.0
    var m2 = 0.0
    var count = 0L
    val marginX = (width * 0.05f).toInt()
    val marginY = (height * 0.05f).toInt()
    val startX = marginX.coerceAtLeast(0)
    val endX = (width - marginX).coerceAtLeast(startX + 1)
    val startY = marginY.coerceAtLeast(0)
    val endY = (height - marginY).coerceAtLeast(startY + 1)
    for (y in startY until endY step step) {
        val row = y * width
        for (x in startX until endX step step) {
            val v = luma[row + x].toInt() and 0xFF
            count++
            val delta = v - mean
            mean += delta / count.toDouble()
            m2 += delta * (v - mean)
        }
    }
    val variance = if (count > 1) m2 / count.toDouble() else 0.0
    return (variance / 1000.0).coerceIn(0.0, 1.0)
}

private suspend fun ImageCapture.takePictureSuspend(executor: java.util.concurrent.Executor): androidx.camera.core.ImageProxy =
    suspendCancellableCoroutine { cont ->
        takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                    if (cont.isCancelled) {
                        image.close()
                        return
                    }
                    cont.resume(image)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(exception)
                }
            },
        )
    }

private suspend fun captureBestOfN(
    imageCapture: ImageCapture,
    captureExecutor: java.util.concurrent.Executor,
    converter: YuvToRgbConverter,
    n: Int,
    scoreFn: suspend (android.graphics.Bitmap) -> Double,
): android.graphics.Bitmap? {
    var bestScore = Double.NEGATIVE_INFINITY
    var bestJpeg: ByteArray? = null
    var bestBitmap: android.graphics.Bitmap? = null
    var lastJpeg: ByteArray? = null
    var lastBitmap: android.graphics.Bitmap? = null
    repeat(n) {
        val image = imageCapture.takePictureSuspend(captureExecutor)
        try {
            if (image.format == ImageFormat.JPEG) {
                val bytes = image.jpegBytes()
                lastJpeg = bytes
                val sample = runCatching { decodeSampledBitmap(bytes, 320) }.getOrNull()
                if (sample != null) {
                    val score = scoreFn(sample)
                    if (score > bestScore) {
                        bestScore = score
                        bestJpeg = bytes
                        bestBitmap = null
                    }
                }
            } else {
                val bmp = converter.toBitmap(image, Rect(0, 0, image.width, image.height), 480)
                lastBitmap = bmp
                val score = scoreFn(bmp)
                if (score > bestScore) {
                    bestScore = score
                    bestBitmap = bmp
                    bestJpeg = null
                }
            }
        } finally {
            image.close()
        }
    }
    val jpeg = bestJpeg
    return if (jpeg != null) {
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    } else {
        bestBitmap ?: lastBitmap ?: lastJpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
}

private fun ImageProxy.jpegBytes(): ByteArray {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}

private fun decodeSampledBitmap(bytes: ByteArray, reqMax: Int): android.graphics.Bitmap {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    options.inSampleSize = calculateInSampleSize(options, reqMax, reqMax)
    options.inJustDecodeBounds = false
    options.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        var halfHeight = height / 2
        var halfWidth = width / 2
        while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize.coerceAtLeast(1)
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
            .clickable(onClick = onClick),
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
