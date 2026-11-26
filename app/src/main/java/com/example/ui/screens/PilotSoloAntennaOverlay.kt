package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.AntennaConfig
import com.example.data.model.AntiVibrationLevel
import com.example.data.model.DetectionMode
import com.example.data.model.LineOrientation
import com.example.data.model.RoiHeight
import com.example.data.model.RoiSize
import com.example.data.model.formatLapMs
import com.example.engine.CameraFpsHelper
import com.example.engine.VisionTimingEngine
import com.example.i18n.MxStrings
import com.example.ui.components.GlassRaceCard
import com.example.ui.components.KeepScreenOnEffect
import com.example.ui.components.Mx3dButton
import com.example.ui.theme.LocalMxColors
import com.example.ui.theme.contrastTextOn
import com.example.ui.theme.mxChipColors
import com.example.ui.viewmodel.MxProViewModel
import com.example.ui.viewmodel.MxUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@Composable
fun PilotSoloAntennaOverlay(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel,
    onClose: () -> Unit
) {
    BackHandler { onClose() }
    KeepScreenOnEffect(enabled = true)

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mx = LocalMxColors.current
    val scope = rememberCoroutineScope()

    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val camPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPerm = granted }

    var roiSize by remember { mutableStateOf(RoiSize.M) }
    var lineOrientation by remember { mutableStateOf(LineOrientation.VERTICAL) }
    var calibrated by remember { mutableStateOf(false) }
    var calibratingCountdown by remember { mutableStateOf(0) }
    var lastMatchedLabel by remember { mutableStateOf("") }

    val visionEngine = remember { VisionTimingEngine() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var boundProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    val soloConfig = AntennaConfig(
        antennaId = "solo_phone",
        circuitId = state.activeCircuit.id,
        isCalibrated = calibrated,
        detectionMode = state.soloAntennaDetectionMode,
        roiSize = roiSize,
        roiHeight = RoiHeight.MEDIUM,
        lineOrientation = lineOrientation,
        linePosition = 0.5f,
        antiVibration = AntiVibrationLevel.MEDIUM
    )
    val latestSoloConfig by rememberUpdatedState(soloConfig)
    val latestProfiles by rememberUpdatedState(state.colorProfiles)
    val latestIsSpanish by rememberUpdatedState(strings.isSpanish)

    DisposableEffect(Unit) {
        if (!hasCameraPerm) camPermLauncher.launch(Manifest.permission.CAMERA)
        onDispose {
            try {
                boundProvider?.unbindAll()
            } catch (_: Exception) {
            }
            analysisExecutor.shutdown()
        }
    }

    val rider = state.currentRider
    val roomRider = rider?.let { state.roomState.activeRiders[it.id] }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (hasCameraPerm) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        }
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val analyzerBlock = ImageAnalysis.Analyzer { imageProxy ->
                                try {
                                    val now = viewModel.syncedNowMs()
                                    val res = visionEngine.processFrame(
                                        image = imageProxy,
                                        config = latestSoloConfig,
                                        profiles = latestProfiles,
                                        syncedNowMs = now,
                                        isSpanish = latestIsSpanish,
                                        captureLiveSnapshot = false
                                    )
                                    if (res != null && res.triggered) {
                                        lastMatchedLabel = res.matchedProfile?.riderName?.ifBlank {
                                            res.detectedColors.firstOrNull()?.colorName ?: ""
                                        } ?: (res.detectedColors.firstOrNull()?.colorName ?: "")
                                        viewModel.handleSoloPilotVisionDetection(res)
                                    }
                                } finally {
                                    imageProxy.close()
                                }
                            }
                            try {
                                val cameraProvider = cameraProviderFuture.get()
                                boundProvider = cameraProvider
                                val previewBuilder = Preview.Builder()
                                CameraFpsHelper.applyBestHighFpsRange(ctx, previewBuilder)
                                val preview = previewBuilder.build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }
                                val analysisBuilder = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                CameraFpsHelper.applyBestHighFpsRange(ctx, analysisBuilder)
                                val analysis = analysisBuilder.build().also {
                                    it.setAnalyzer(analysisExecutor, analyzerBlock)
                                }
                                cameraProvider.unbindAll()
                                try {
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        analysis
                                    )
                                } catch (_: Exception) {
                                    val fallbackPreview = Preview.Builder().build().also {
                                        it.surfaceProvider = previewView.surfaceProvider
                                    }
                                    val fallbackAnalysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build().also {
                                            it.setAnalyzer(analysisExecutor, analyzerBlock)
                                        }
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        fallbackPreview,
                                        fallbackAnalysis
                                    )
                                }
                            } catch (_: Exception) {
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (strings.isSpanish) "Permite acceso a cámara para usar tu móvil como antena" else "Grant camera access to use phone as antenna",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Mx3dButton(
                        onClick = { camPermLauncher.launch(Manifest.permission.CAMERA) },
                        containerColor = mx.goldAccent,
                        contentColor = contrastTextOn(mx.goldAccent)
                    ) {
                        Text(if (strings.isSpanish) "Conceder Cámara" else "Grant Camera")
                    }
                }
            }

            // ROI Tunnel & Finish Line Overlay
            Canvas(modifier = Modifier.fillMaxSize()) {
                val roiW = size.width * roiSize.widthFrac
                val roiH = size.height * roiSize.heightFrac
                val roiLeft = (size.width - roiW) / 2f
                val roiTop = (size.height - roiH) / 2f
                val roiRight = roiLeft + roiW
                val roiBottom = roiTop + roiH
                val dim = Color.Black.copy(alpha = 0.22f)
                drawRect(dim, topLeft = Offset.Zero, size = Size(size.width, roiTop))
                drawRect(dim, topLeft = Offset(0f, roiBottom), size = Size(size.width, (size.height - roiBottom).coerceAtLeast(0f)))
                drawRect(dim, topLeft = Offset(0f, roiTop), size = Size(roiLeft, roiH))
                drawRect(dim, topLeft = Offset(roiRight, roiTop), size = Size((size.width - roiRight).coerceAtLeast(0f), roiH))

                drawRect(
                    color = if (calibrated) Color(0xFF22C55E) else Color(0xFFF5B731),
                    topLeft = Offset(roiLeft, roiTop),
                    size = Size(roiW, roiH),
                    style = Stroke(width = 4.dp.toPx())
                )
                if (lineOrientation == LineOrientation.VERTICAL) {
                    val lx = roiLeft + roiW * 0.5f
                    drawLine(Color(0xFFE63946), Offset(lx, roiTop), Offset(lx, roiTop + roiH), strokeWidth = 4.dp.toPx())
                } else {
                    val ly = roiTop + roiH * 0.5f
                    drawLine(Color(0xFFE63946), Offset(roiLeft, ly), Offset(roiLeft + roiW, ly), strokeWidth = 4.dp.toPx())
                }
            }

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassRaceCard {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = if (strings.isSpanish) "ANTENA SOLO • ${rider?.name ?: ""}" else "SOLO ANTENNA • ${rider?.name ?: ""}",
                        style = MaterialTheme.typography.titleMedium,
                        color = mx.goldAccent
                    )
                    Text(
                        text = "${roomRider?.lapsCount ?: 0} laps • Best ${formatLapMs(roomRider?.bestLapMs ?: 0L)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (calibrated) mx.telemetryGreen else mx.goldBright
                    )
                }
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("solo_antenna_close")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Color.White)
            }
        }

        // Bottom Controls for Capture Mode + Solo Calibration + ROI + Orientation
        GlassRaceCard(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(14.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (strings.isSpanish) {
                        "Modo Captura (Solo o en Grupo sin Director):"
                    } else {
                        "Capture Mode (Solo or Group Training):"
                    } + if (lastMatchedLabel.isNotBlank()) " • $lastMatchedLabel" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = mx.goldAccent
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = state.soloAntennaDetectionMode == DetectionMode.ANY_PASS,
                        onClick = { viewModel.updateSoloAntennaMode(DetectionMode.ANY_PASS) },
                        label = { Text(if (strings.isSpanish) "Cualq. Paso" else "Any Pass") },
                        colors = mxChipColors()
                    )
                    FilterChip(
                        selected = state.soloAntennaDetectionMode == DetectionMode.FAIRING_COLOR,
                        onClick = { viewModel.updateSoloAntennaMode(DetectionMode.FAIRING_COLOR) },
                        label = { Text("Multi-Color") },
                        colors = mxChipColors()
                    )
                    FilterChip(
                        selected = state.soloAntennaDetectionMode == DetectionMode.SINGLE_COLOR,
                        onClick = { viewModel.updateSoloAntennaMode(DetectionMode.SINGLE_COLOR) },
                        label = { Text(if (strings.isSpanish) "1 Color" else "1 Color") },
                        colors = mxChipColors()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoiSize.entries.forEach { sz ->
                        FilterChip(
                            selected = roiSize == sz,
                            onClick = { roiSize = sz },
                            label = { Text(sz.name) },
                            colors = mxChipColors()
                        )
                    }
                    FilterChip(
                        selected = lineOrientation == LineOrientation.VERTICAL,
                        onClick = {
                            lineOrientation = if (lineOrientation == LineOrientation.VERTICAL) {
                                LineOrientation.HORIZONTAL
                            } else LineOrientation.VERTICAL
                        },
                        label = { Text(if (lineOrientation == LineOrientation.VERTICAL) "Vert" else "Horiz") },
                        colors = mxChipColors()
                    )
                }
                val btnBg = if (calibrated) mx.telemetryGreen else mx.goldAccent
                Mx3dButton(
                    onClick = {
                        scope.launch {
                            for (s in 3 downTo 1) {
                                calibratingCountdown = s
                                viewModel.voiceEngine.speak(s.toString())
                                delay(1000L)
                            }
                            calibratingCountdown = 0
                            visionEngine.requestLocalCalibration()
                            calibrated = true
                            viewModel.voiceEngine.speak(
                                if (strings.isSpanish) "Antena calibrada, tiempos públicos activos"
                                else "Antenna calibrated, public timing active"
                            )
                        }
                    },
                    containerColor = btnBg,
                    contentColor = contrastTextOn(btnBg),
                    modifier = Modifier.fillMaxWidth().testTag("solo_antenna_calibrate")
                ) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            calibratingCountdown > 0 -> "${calibratingCountdown}s..."
                            calibrated -> (if (strings.isSpanish) "Recalibrar Pista Vacía (3s)" else "Recalibrate Track (3s)")
                            else -> (if (strings.isSpanish) "Calibrar Pista Vacía (3s)" else "Calibrate Empty Track (3s)")
                        }
                    )
                }
            }
        }
    }
    }
}
