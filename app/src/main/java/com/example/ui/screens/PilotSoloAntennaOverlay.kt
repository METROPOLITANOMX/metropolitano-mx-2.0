package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LockOpen
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.example.data.model.SessionPhase
import com.example.data.model.formatLapMs
import com.example.data.model.predefinedMotoColors
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
    var colorZoneX by remember { mutableFloatStateOf(0.70f) }
    var colorZoneY by remember { mutableFloatStateOf(0.50f) }
    var calibrated by remember { mutableStateOf(false) }
    var calibratingCountdown by remember { mutableIntStateOf(0) }
    var lastMatchedLabel by remember { mutableStateOf("") }
    var batterySaverBlackScreen by remember { mutableStateOf(false) }
    var lastInteractionMonoMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    fun touchActivity() {
        lastInteractionMonoMs = SystemClock.elapsedRealtime()
    }

    LaunchedEffect(lastInteractionMonoMs) {
        delay(60_000L)
        batterySaverBlackScreen = true
    }

    DisposableEffect(batterySaverBlackScreen) {
        val window = (context as? Activity)?.window
        val attrs = window?.attributes
        if (window != null && attrs != null) {
            attrs.screenBrightness = if (batterySaverBlackScreen) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = attrs
        }
        onDispose {
            if (window != null && attrs != null) {
                attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attrs
            }
        }
    }

    var tickerMonoMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.lastCaptureFeedbackAtMs) {
        if (state.lastCaptureFeedbackAtMs > 0L) {
            tickerMonoMs = SystemClock.elapsedRealtime()
            delay(4200L)
            tickerMonoMs = SystemClock.elapsedRealtime()
        }
    }

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
        linePosition = 0.42f,
        colorZoneX = colorZoneX,
        colorZoneY = colorZoneY,
        singleTargetColorName = state.soloTargetColor?.colorName ?: "",
        antiVibration = AntiVibrationLevel.MEDIUM
    )
    val latestSoloConfig by rememberUpdatedState(soloConfig)
    val latestProfiles by rememberUpdatedState(state.colorProfiles)
    val latestIsSpanish by rememberUpdatedState(strings.isSpanish)
    val latestRoomPhase by rememberUpdatedState(state.roomState.sessionPhase)

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
    val palette = remember(strings.isSpanish) { predefinedMotoColors(strings.isSpanish) }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { touchActivity() }) { change, dragAmount ->
                        change.consume()
                        touchActivity()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        colorZoneX = (colorZoneX + dragAmount.x / w).coerceIn(0.15f, 0.85f)
                        colorZoneY = (colorZoneY + dragAmount.y / h).coerceIn(0.18f, 0.82f)
                    }
                }
        ) {
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
                                    val isWarmup = latestRoomPhase == SessionPhase.WARMUP
                                    val res = visionEngine.processFrame(
                                        image = imageProxy,
                                        config = latestSoloConfig,
                                        profiles = latestProfiles,
                                        syncedNowMs = now,
                                        isSpanish = latestIsSpanish,
                                        captureLiveSnapshot = false,
                                        extractColorsForWarmup = isWarmup
                                    )
                                    if (res != null && res.triggered) {
                                        lastMatchedLabel = if (latestSoloConfig.detectionMode == DetectionMode.ANY_PASS && !isWarmup) {
                                            ""
                                        } else {
                                            res.matchedProfile?.riderName?.ifBlank {
                                                res.detectedColors.firstOrNull()?.colorName ?: ""
                                            } ?: (res.detectedColors.firstOrNull()?.colorName ?: "")
                                        }
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

            // Full-Screen Finish Line + Independent Color Zone Overlay
            val showColorZone = state.soloAntennaDetectionMode != DetectionMode.ANY_PASS ||
                state.roomState.sessionPhase == SessionPhase.WARMUP
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (lineOrientation == LineOrientation.VERTICAL) {
                    val lx = size.width * 0.42f
                    drawLine(Color(0xFFE63946).copy(alpha = 0.35f), Offset(lx, 0f), Offset(lx, size.height), strokeWidth = 10.dp.toPx())
                    drawLine(Color(0xFFE63946), Offset(lx, 0f), Offset(lx, size.height), strokeWidth = 3.5.dp.toPx())
                } else {
                    val ly = size.height * 0.42f
                    drawLine(Color(0xFFE63946).copy(alpha = 0.35f), Offset(0f, ly), Offset(size.width, ly), strokeWidth = 10.dp.toPx())
                    drawLine(Color(0xFFE63946), Offset(0f, ly), Offset(size.width, ly), strokeWidth = 3.5.dp.toPx())
                }

                val zoneW = size.width * (roiSize.widthFrac * 0.55f).coerceIn(0.18f, 0.48f)
                val zoneH = size.height * (roiSize.heightFrac * 0.55f).coerceIn(0.18f, 0.45f)
                val zoneLeft = (size.width * colorZoneX - zoneW / 2f).coerceIn(8f, size.width - zoneW - 8f)
                val zoneTop = (size.height * colorZoneY - zoneH / 2f).coerceIn(8f, size.height - zoneH - 8f)

                drawRect(
                    color = if (showColorZone) Color(0xFF00E5FF) else Color(0xFF38BDF8).copy(alpha = 0.35f),
                    topLeft = Offset(zoneLeft, zoneTop),
                    size = Size(zoneW, zoneH),
                    style = Stroke(width = if (showColorZone) 3.5.dp.toPx() else 2.dp.toPx())
                )
            }

            // Top bar + Capture feedback pill
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(12.dp)
                    .align(Alignment.TopCenter),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassRaceCard(modifier = Modifier.weight(1f)) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                text = if (strings.isSpanish) "ANTENA SOLO • ${rider?.name ?: ""}" else "SOLO ANTENNA • ${rider?.name ?: ""}",
                                style = MaterialTheme.typography.titleMedium,
                                color = mx.goldAccent,
                                maxLines = 1
                            )
                            Text(
                                text = "${roomRider?.lapsCount ?: 0} laps • Best ${formatLapMs(roomRider?.bestLapMs ?: 0L)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (calibrated) mx.telemetryGreen else mx.goldBright
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = {
                                touchActivity()
                                batterySaverBlackScreen = true
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.65f))
                                .minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.BatterySaver, contentDescription = "Battery Saver", tint = mx.goldAccent)
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.65f))
                                .minimumInteractiveComponentSize()
                                .testTag("solo_antenna_close")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Color.White)
                        }
                    }
                }

                val showCaptureBanner = state.lastCaptureFeedback != null &&
                    (tickerMonoMs - state.lastCaptureFeedbackAtMs) in 0L..4200L
                if (showCaptureBanner) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(999.dp))
                            .background((if (state.lastCaptureOk) Color(0xFF14532D) else Color(0xFF7F1D1D)).copy(alpha = 0.92f))
                            .border(1.5.dp, if (state.lastCaptureOk) mx.telemetryGreen else mx.racingRed, RoundedCornerShape(999.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (state.lastCaptureOk) mx.telemetryGreen else mx.goldBright,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = state.lastCaptureFeedback.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Bottom Controls for Capture Mode + Single Color Target Picker + Calibration
            GlassRaceCard(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(14.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (strings.isSpanish) {
                            "Modo Captura (Solo o Grupo sin Director):"
                        } else {
                            "Capture Mode (Solo or Group Training):"
                        } + if (lastMatchedLabel.isNotBlank()) " • $lastMatchedLabel" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = mx.goldAccent
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = state.soloAntennaDetectionMode == DetectionMode.ANY_PASS,
                            onClick = {
                                touchActivity()
                                viewModel.updateSoloAntennaMode(DetectionMode.ANY_PASS)
                            },
                            label = { Text(if (strings.isSpanish) "Cualq. Paso" else "Any Pass") },
                            colors = mxChipColors()
                        )
                        FilterChip(
                            selected = state.soloAntennaDetectionMode == DetectionMode.FAIRING_COLOR,
                            onClick = {
                                touchActivity()
                                viewModel.updateSoloAntennaMode(DetectionMode.FAIRING_COLOR)
                            },
                            label = { Text("Multi-Color") },
                            colors = mxChipColors()
                        )
                        FilterChip(
                            selected = state.soloAntennaDetectionMode == DetectionMode.SINGLE_COLOR,
                            onClick = {
                                touchActivity()
                                viewModel.updateSoloAntennaMode(DetectionMode.SINGLE_COLOR)
                                if (state.soloTargetColor == null) {
                                    viewModel.updateSoloTargetColor(palette.firstOrNull())
                                }
                            },
                            label = { Text("1 Color") },
                            colors = mxChipColors()
                        )
                    }

                    if (state.soloAntennaDetectionMode == DetectionMode.SINGLE_COLOR) {
                        Text(
                            text = if (strings.isSpanish) {
                                "Elige el ÚNICO color a detectar: ${state.soloTargetColor?.colorName ?: "Sin elegir"}"
                            } else {
                                "Select the 1 target color: ${state.soloTargetColor?.colorName ?: "None"}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = mx.telemetryGreen
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(palette) { sample ->
                                val selected = state.soloTargetColor?.colorName.equals(sample.colorName, ignoreCase = true)
                                val swatchColor = try { Color(android.graphics.Color.parseColor(sample.hex)) } catch (_: Exception) { Color.White }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable {
                                        touchActivity()
                                        viewModel.updateSoloTargetColor(sample)
                                    }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(swatchColor)
                                            .border(
                                                if (selected) 3.dp else 1.dp,
                                                if (selected) mx.goldAccent else Color.White.copy(alpha = 0.65f),
                                                CircleShape
                                            )
                                    )
                                    Text(
                                        sample.colorName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) mx.goldAccent else mx.subtleText
                                    )
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoiSize.entries.forEach { sz ->
                            FilterChip(
                                selected = roiSize == sz,
                                onClick = {
                                    touchActivity()
                                    roiSize = sz
                                },
                                label = { Text(sz.name) },
                                colors = mxChipColors()
                            )
                        }
                        FilterChip(
                            selected = lineOrientation == LineOrientation.VERTICAL,
                            onClick = {
                                touchActivity()
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
                            touchActivity()
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

            if (batterySaverBlackScreen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(Icons.Default.BatterySaver, contentDescription = null, tint = Color(0xFF4B5563), modifier = Modifier.size(44.dp))
                        Text(
                            text = if (strings.isSpanish) "ANTENA SOLO ACTIVA • PANTALLA NEGRA" else "SOLO ANTENNA ACTIVE • BLACK SCREEN",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF9CA3AF),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "${roomRider?.lapsCount ?: 0} laps • Best ${formatLapMs(roomRider?.bestLapMs ?: 0L)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF6B7280)
                        )
                        Mx3dButton(
                            onClick = {
                                touchActivity()
                                batterySaverBlackScreen = false
                            },
                            containerColor = Color(0xFF1F2937),
                            contentColor = Color.White,
                            modifier = Modifier.fillMaxWidth(0.75f).testTag("solo_unlock_black_screen")
                        ) {
                            Icon(Icons.Default.LockOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (strings.isSpanish) "Desbloquear Pantalla" else "Unlock Screen")
                        }
                    }
                }
            }
        }
    }
}
