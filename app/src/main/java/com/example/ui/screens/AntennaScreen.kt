package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.ViewGroup
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.data.model.AntennaStation
import com.example.data.model.DetectionMode
import com.example.data.model.LabSample
import com.example.data.model.LineOrientation
import com.example.data.model.PassingDirection
import com.example.engine.CameraFpsHelper
import com.example.engine.VisionTimingEngine
import com.example.i18n.MxStrings
import com.example.ui.components.AnimatedCheckeredRibbon
import com.example.ui.components.CarbonStadiumBackdrop
import com.example.ui.components.GlassRaceCard
import com.example.ui.components.Mx3dButton
import com.example.ui.components.Mx3dOutlinedButton
import com.example.ui.theme.LocalMxColors
import com.example.ui.theme.contrastTextOn
import com.example.ui.viewmodel.MxProViewModel
import com.example.ui.viewmodel.MxUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@Composable
fun AntennaScreen(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mx = LocalMxColors.current

    var stationChosen by remember { mutableStateOf(false) }
    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val camPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPerm = granted }

    val visionEngine = remember { VisionTimingEngine() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var lastHandledCalCmd by remember { mutableLongStateOf(0L) }
    val lastSnapshotMonoMs = remember { AtomicLong(0L) }
    var lastDetectedColors by remember { mutableStateOf<List<LabSample>>(emptyList()) }
    var lastDorsalHint by remember { mutableStateOf("") }
    var lastFpsEstimate by remember { mutableIntStateOf(30) }
    var lastMatchConfidence by remember { mutableFloatStateOf(0f) }
    var localCalibrated by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var boundProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    val activeConfig = state.antennas[state.selectedAntennaStation.id]
        ?: AntennaConfig(antennaId = state.selectedAntennaStation.id, circuitId = state.activeCircuit.id)

    val latestConfig by rememberUpdatedState(activeConfig)
    val latestProfiles by rememberUpdatedState(state.colorProfiles)
    val latestStation by rememberUpdatedState(state.selectedAntennaStation)
    val latestIsSpanish by rememberUpdatedState(strings.isSpanish)

    LaunchedEffect(activeConfig.calibrateCommandAtMs) {
        if (activeConfig.calibrateCommandAtMs > lastHandledCalCmd && activeConfig.calibrateCommandAtMs > 0L) {
            lastHandledCalCmd = activeConfig.calibrateCommandAtMs
            visionEngine.requestLocalCalibration()
            localCalibrated = true
            viewModel.sendAntennaHeartbeat(state.selectedAntennaStation, isCalibrated = true, status = "READY")
        }
    }

    LaunchedEffect(stationChosen, state.selectedAntennaStation, localCalibrated) {
        if (stationChosen) {
            while (isActive) {
                viewModel.sendAntennaHeartbeat(
                    station = latestStation,
                    isCalibrated = localCalibrated,
                    status = if (localCalibrated) "READY" else "WAITING_CALIBRATION"
                )
                delay(4000L)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                boundProvider?.unbindAll()
            } catch (_: Exception) {
            }
            analysisExecutor.shutdown()
        }
    }

    if (!stationChosen) {
        CarbonStadiumBackdrop {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = mx.goldAccent, modifier = Modifier.size(52.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    strings.antennaChooseStation,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                AnimatedCheckeredRibbon(modifier = Modifier.fillMaxWidth(0.7f))
                Spacer(Modifier.height(10.dp))
                Text(
                    "${strings.antennaRemoteHint}\n${state.activeCircuit.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = mx.subtleText
                )
                Spacer(Modifier.height(20.dp))

                val stations = listOf(
                    AntennaStation.ANTENA_1 to (if (strings.isSpanish) "Antena 1 (Meta Principal)" else "Antenna 1 (Main Finish)"),
                    AntennaStation.ANTENA_2 to (if (strings.isSpanish) "Antena 2 (Refuerzo)" else "Antenna 2 (Backup)"),
                    AntennaStation.ANTENA_TEMP to (if (strings.isSpanish) "Antena Temporal / Piloto" else "Temporary / Rider Antenna")
                )
                stations.forEach { (station, label) ->
                    Mx3dButton(
                        onClick = {
                            viewModel.selectAntennaStation(station)
                            stationChosen = true
                            if (!hasCameraPerm) camPermLauncher.launch(Manifest.permission.CAMERA)
                        },
                        containerColor = mx.goldAccent,
                        contentColor = contrastTextOn(mx.goldAccent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag("select_${station.id}")
                    ) {
                        Text(label, style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Mx3dOutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(0.6f)
                ) {
                    Text(if (strings.isSpanish) "Salir" else "Exit")
                }
            }
        }
        return
    }

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
                                    val nowMono = SystemClock.elapsedRealtime()
                                    val sendSnap = (nowMono - lastSnapshotMonoMs.get()) > 1200L
                                    val res = visionEngine.processFrame(
                                        image = imageProxy,
                                        config = latestConfig,
                                        profiles = latestProfiles,
                                        syncedNowMs = viewModel.syncedNowMs(),
                                        isSpanish = latestIsSpanish,
                                        captureLiveSnapshot = sendSnap
                                    )
                                    if (res != null) {
                                        lastFpsEstimate = res.estimatedFps
                                        if (sendSnap && res.snapshotBase64.isNotBlank()) {
                                            lastSnapshotMonoMs.set(nowMono)
                                            viewModel.pushAntennaLiveSnapshot(
                                                latestStation,
                                                res.snapshotBase64,
                                                res.estimatedFps
                                            )
                                        }
                                        if (res.triggered) {
                                            lastDetectedColors = res.detectedColors
                                            lastDorsalHint = res.detectedDorsal
                                            lastMatchConfidence = res.matchConfidence
                                            viewModel.handleVisionDetection(latestStation, res)
                                        }
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
                                    // Fallback without strict dual-stream FPS hint for older Camera2 HALs
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
                                cameraError = null
                            } catch (e: Exception) {
                                cameraError = e.localizedMessage ?: "Camera error"
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (cameraError != null) {
                    Text(
                        text = "Camera error: $cameraError",
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Red.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                            .padding(14.dp)
                    )
                }
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (strings.isSpanish) "Se requiere permiso de cámara para la Antena" else "Camera permission required for Antenna",
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

            // Translucent ROI "Efecto Túnel" + Trigger Line Overlay (bright inside tunnel!)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val roiW = size.width * activeConfig.roiSize.widthFrac
                val roiH = size.height * activeConfig.roiSize.heightFrac
                val roiLeft = (size.width - roiW) / 2f
                val roiTop = (size.height * activeConfig.roiHeight.centerYFrac - roiH / 2f).coerceIn(0f, size.height - roiH)
                val roiRight = roiLeft + roiW
                val roiBottom = roiTop + roiH
                val dim = Color.Black.copy(alpha = 0.24f)

                // Dim ONLY outside the tunnel so the pass zone stays crystal clear
                drawRect(dim, topLeft = Offset.Zero, size = Size(size.width, roiTop))
                drawRect(dim, topLeft = Offset(0f, roiBottom), size = Size(size.width, (size.height - roiBottom).coerceAtLeast(0f)))
                drawRect(dim, topLeft = Offset(0f, roiTop), size = Size(roiLeft, roiH))
                drawRect(dim, topLeft = Offset(roiRight, roiTop), size = Size((size.width - roiRight).coerceAtLeast(0f), roiH))

                drawRect(
                    color = if (localCalibrated) Color(0xFF22C55E) else Color(0xFFF5B731),
                    topLeft = Offset(roiLeft, roiTop),
                    size = Size(roiW, roiH),
                    style = Stroke(width = 4.dp.toPx())
                )

            // Trigger Line
            if (activeConfig.lineOrientation == LineOrientation.VERTICAL) {
                val lx = roiLeft + roiW * activeConfig.linePosition
                drawLine(
                    color = Color(0xFFE63946),
                    start = Offset(lx, roiTop),
                    end = Offset(lx, roiTop + roiH),
                    strokeWidth = 4.dp.toPx()
                )
            } else {
                val ly = roiTop + roiH * activeConfig.linePosition
                drawLine(
                    color = Color(0xFFE63946),
                    start = Offset(roiLeft, ly),
                    end = Offset(roiLeft + roiW, ly),
                    strokeWidth = 4.dp.toPx()
                )
            }
        }

        // Minimal remote-only HUD (No calibration sliders or adjusters on Antenna phone!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(14.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassRaceCard {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = "${state.selectedAntennaStation.name} • ${state.activeCircuit.name}",
                        style = MaterialTheme.typography.titleMedium,
                        color = mx.goldAccent
                    )
                    Text(
                        text = if (!localCalibrated) {
                            if (strings.isSpanish) "Esperando Calibración de Dirección" else "Waiting for Director Calibration"
                        } else {
                            val modeLabel = when (activeConfig.detectionMode) {
                                DetectionMode.FAIRING_COLOR -> "CIE Lab"
                                DetectionMode.SINGLE_COLOR -> if (strings.isSpanish) "1 Color" else "Single Color"
                                DetectionMode.ANY_PASS -> if (strings.isSpanish) "Cualq. Paso" else "Any Pass"
                            }
                            val dirLabel = if (activeConfig.directionFilter != PassingDirection.ANY) " • ${activeConfig.directionFilter.name.take(5)}" else ""
                            val aiLabel = if (activeConfig.aiStewardEnabled) " • IA" else ""
                            val dorsalPart = if (lastDorsalHint.isNotBlank()) " • #$lastDorsalHint" else ""
                            "${if (strings.isSpanish) "LISTA" else "READY"} • ${lastFpsEstimate}fps • $modeLabel$dirLabel$aiLabel$dorsalPart"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (localCalibrated) mx.telemetryGreen else mx.racingRed
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(
                    onClick = { viewModel.toggleAntennaVoice() },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .minimumInteractiveComponentSize()
                        .testTag("antenna_voice_toggle")
                ) {
                    Icon(
                        imageVector = if (state.antennaVoiceEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = if (state.antennaVoiceEnabled) strings.voiceOn else strings.voiceOff,
                        tint = Color.White
                    )
                }
                IconButton(
                    onClick = { stationChosen = false },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .minimumInteractiveComponentSize()
                        .testTag("antenna_change_station")
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = "Station", tint = Color.White)
                }
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .minimumInteractiveComponentSize()
                        .testTag("antenna_exit_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Exit", tint = Color.White)
                }
            }
        }

        // Bottom detected colors readout
        if (lastDetectedColors.isNotEmpty()) {
            GlassRaceCard(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    lastDetectedColors.forEach { sample ->
                        val c = try { Color(android.graphics.Color.parseColor(sample.hex)) } catch (_: Exception) { Color.White }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(1.dp, Color.White, CircleShape)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                sample.colorName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
    }
}
