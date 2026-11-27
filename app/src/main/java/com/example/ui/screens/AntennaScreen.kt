package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.data.model.AntennaStation
import com.example.data.model.DetectionMode
import com.example.data.model.LabSample
import com.example.data.model.LineOrientation
import com.example.data.model.PassingDirection
import com.example.data.model.SessionPhase
import com.example.engine.CameraFpsHelper
import com.example.engine.VisionTimingEngine
import com.example.i18n.MxStrings
import com.example.ui.components.AnimatedCheckeredRibbon
import com.example.ui.components.CarbonStadiumBackdrop
import com.example.ui.components.GlassRaceCard
import com.example.ui.components.KeepScreenOnEffect
import com.example.ui.components.Mx3dButton
import com.example.ui.components.Mx3dOutlinedButton
import com.example.ui.theme.LocalMxColors
import com.example.ui.theme.contrastTextOn
import com.example.ui.viewmodel.MxProViewModel
import com.example.ui.viewmodel.MxUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    val scope = rememberCoroutineScope()
    var stationChosen by rememberSaveable { mutableStateOf(false) }
    var batterySaverBlackScreen by rememberSaveable { mutableStateOf(false) }
    var showLanIpDialog by remember { mutableStateOf(false) }
    var manualIpInput by remember(state.activeDirectorLanIp) { mutableStateOf(state.activeDirectorLanIp.orEmpty()) }
    var lastInteractionMonoMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    fun touchActivity() {
        lastInteractionMonoMs = SystemClock.elapsedRealtime()
    }

    KeepScreenOnEffect(enabled = stationChosen)

    LaunchedEffect(stationChosen) {
        viewModel.setAntennaRoleActive(stationChosen)
        if (stationChosen) touchActivity() else batterySaverBlackScreen = false
    }

    // 1-minute inactivity auto black battery-saver screen (camera & detection keep running)
    LaunchedEffect(stationChosen, lastInteractionMonoMs, showLanIpDialog) {
        if (stationChosen && !showLanIpDialog) {
            delay(60_000L)
            batterySaverBlackScreen = true
        }
    }

    // Dim screen brightness to minimum during OLED black battery-saver mode
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
    var localCalibrated by rememberSaveable { mutableStateOf(false) }
    var calibratingCountdown by remember { mutableIntStateOf(0) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var boundProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    val remoteConfig = state.antennas[state.selectedAntennaStation.id]
        ?: AntennaConfig(antennaId = state.selectedAntennaStation.id, circuitId = state.activeCircuit.id)
    var localColorZoneX by rememberSaveable(state.selectedAntennaStation.id) { mutableFloatStateOf(remoteConfig.colorZoneX) }
    var localColorZoneY by rememberSaveable(state.selectedAntennaStation.id) { mutableFloatStateOf(remoteConfig.colorZoneY) }
    LaunchedEffect(remoteConfig.colorZoneX, remoteConfig.colorZoneY) {
        localColorZoneX = remoteConfig.colorZoneX
        localColorZoneY = remoteConfig.colorZoneY
    }
    val activeConfig = remoteConfig.copy(colorZoneX = localColorZoneX, colorZoneY = localColorZoneY)

    val latestConfig by rememberUpdatedState(activeConfig)
    val latestProfiles by rememberUpdatedState(state.colorProfiles)
    val latestStation by rememberUpdatedState(state.selectedAntennaStation)
    val latestIsSpanish by rememberUpdatedState(strings.isSpanish)
    val latestCalibrated by rememberUpdatedState(localCalibrated)
    val latestRoomPhase by rememberUpdatedState(state.roomState.sessionPhase)

    LaunchedEffect(stationChosen, activeConfig.calibrateCommandAtMs) {
        if (stationChosen && activeConfig.calibrateCommandAtMs > lastHandledCalCmd && activeConfig.calibrateCommandAtMs > 0L) {
            lastHandledCalCmd = activeConfig.calibrateCommandAtMs
            visionEngine.requestLocalCalibration()
            localCalibrated = true
            viewModel.calibrateAntennaLocally(state.selectedAntennaStation)
        }
    }

    LaunchedEffect(stationChosen, state.selectedAntennaStation) {
        if (stationChosen) {
            while (isActive) {
                viewModel.sendAntennaHeartbeat(
                    station = latestStation,
                    isCalibrated = latestCalibrated,
                    status = if (latestCalibrated) "ONLINE_READY" else "ONLINE_WAIT_CAL"
                )
                delay(3500L)
            }
        }
    }

    // Periodic tick to auto-hide the capture toast pill after ~4s
    var tickerMonoMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.lastCaptureFeedbackAtMs) {
        if (state.lastCaptureFeedbackAtMs > 0L) {
            tickerMonoMs = SystemClock.elapsedRealtime()
            delay(4200L)
            tickerMonoMs = SystemClock.elapsedRealtime()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val isRotating = (context as? Activity)?.isChangingConfigurations == true
            if (!isRotating) {
                viewModel.setAntennaRoleActive(false)
            }
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
                    color = mx.subtleText,
                    textAlign = TextAlign.Center
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
                            touchActivity()
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(stationChosen, activeConfig.linePosition) {
                    detectDragGestures(
                        onDragStart = { touchActivity() },
                        onDragEnd = {
                            viewModel.updateRemoteAntennaConfig(
                                latestStation.id,
                                colorZoneX = localColorZoneX,
                                colorZoneY = localColorZoneY
                            )
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        touchActivity()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        localColorZoneX = (localColorZoneX + dragAmount.x / w).coerceIn(0.15f, 0.85f)
                        localColorZoneY = (localColorZoneY + dragAmount.y / h).coerceIn(0.18f, 0.82f)
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
                                    val nowMono = SystemClock.elapsedRealtime()
                                    val sendSnap = (nowMono - lastSnapshotMonoMs.get()) > 1200L
                                    val isWarmup = latestRoomPhase == SessionPhase.WARMUP
                                    val res = visionEngine.processFrame(
                                        image = imageProxy,
                                        config = latestConfig,
                                        profiles = latestProfiles,
                                        syncedNowMs = viewModel.syncedNowMs(),
                                        isSpanish = latestIsSpanish,
                                        captureLiveSnapshot = sendSnap,
                                        extractColorsForWarmup = isWarmup
                                    )
                                    if (res != null) {
                                        lastFpsEstimate = res.estimatedFps
                                        if (sendSnap && res.snapshotBase64.isNotBlank()) {
                                            lastSnapshotMonoMs.set(nowMono)
                                            viewModel.pushAntennaLiveSnapshot(
                                                latestStation,
                                                res.snapshotBase64,
                                                res.estimatedFps,
                                                latestCalibrated
                                            )
                                        }
                                        if (res.triggered) {
                                            lastDetectedColors = if (latestConfig.detectionMode == DetectionMode.ANY_PASS && !isWarmup) {
                                                emptyList()
                                            } else {
                                                res.detectedColors
                                            }
                                            lastDorsalHint = res.detectedDorsal
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

            // Full-Screen Finish Line + Independent Color Capture Zone Overlay
            val showColorZone = activeConfig.detectionMode != DetectionMode.ANY_PASS ||
                state.roomState.sessionPhase == SessionPhase.WARMUP
            Canvas(modifier = Modifier.fillMaxSize()) {
                // 1. Finish Line ("LÍNEA DE META") spanning the ENTIRE screen edge-to-edge
                if (activeConfig.lineOrientation == LineOrientation.VERTICAL) {
                    val lx = (size.width * activeConfig.linePosition).coerceIn(12f, size.width - 12f)
                    drawLine(
                        color = Color(0xFFE63946).copy(alpha = 0.35f),
                        start = Offset(lx, 0f),
                        end = Offset(lx, size.height),
                        strokeWidth = 10.dp.toPx()
                    )
                    drawLine(
                        color = Color(0xFFE63946),
                        start = Offset(lx, 0f),
                        end = Offset(lx, size.height),
                        strokeWidth = 3.5.dp.toPx()
                    )
                } else {
                    val ly = (size.height * activeConfig.linePosition).coerceIn(12f, size.height - 12f)
                    drawLine(
                        color = Color(0xFFE63946).copy(alpha = 0.35f),
                        start = Offset(0f, ly),
                        end = Offset(size.width, ly),
                        strokeWidth = 10.dp.toPx()
                    )
                    drawLine(
                        color = Color(0xFFE63946),
                        start = Offset(0f, ly),
                        end = Offset(size.width, ly),
                        strokeWidth = 3.5.dp.toPx()
                    )
                }

                // 2. Separate Color Zone ("ZONA COLOR") positioned independently
                val zoneW = size.width * (activeConfig.roiSize.widthFrac * 0.55f).coerceIn(0.18f, 0.48f)
                val zoneH = size.height * (activeConfig.roiSize.heightFrac * 0.55f).coerceIn(0.18f, 0.45f)
                val zoneLeft = (size.width * localColorZoneX - zoneW / 2f).coerceIn(8f, size.width - zoneW - 8f)
                val zoneTop = (size.height * localColorZoneY - zoneH / 2f).coerceIn(8f, size.height - zoneH - 8f)

                val zoneColor = when {
                    !showColorZone -> Color(0xFF38BDF8).copy(alpha = 0.35f)
                    localCalibrated -> Color(0xFF00E5FF)
                    else -> Color(0xFFF5B731)
                }
                drawRect(
                    color = zoneColor,
                    topLeft = Offset(zoneLeft, zoneTop),
                    size = Size(zoneW, zoneH),
                    style = Stroke(width = if (showColorZone) 3.5.dp.toPx() else 2.dp.toPx())
                )
            }

            // Legend pills for Full-Screen Finish Line & Draggable Color Zone
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 180.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.52f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (strings.isSpanish) "━ META PANTALLA COMPLETA" else "━ FULL-SCREEN FINISH",
                    style = MaterialTheme.typography.labelSmall,
                    color = mx.racingRed,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (showColorZone) {
                        if (strings.isSpanish) "▢ ARRASTRA ZONA COLOR" else "▢ DRAG COLOR ZONE"
                    } else {
                        if (strings.isSpanish) "▢ CUALQ. PASO (SIN COLOR)" else "▢ ANY PASS (TIMING ONLY)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF00E5FF),
                    fontWeight = FontWeight.Bold
                )
            }

            // Top HUD: Connection Status + Sync Diagnostics + Local/Remote Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(14.dp)
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
                                text = "${state.selectedAntennaStation.name} • ${state.activeCircuit.name}",
                                style = MaterialTheme.typography.titleMedium,
                                color = mx.goldAccent,
                                maxLines = 1
                            )
                            Text(
                                text = if (!localCalibrated) {
                                    if (strings.isSpanish) "CONECTADA • Falta calibrar (${lastFpsEstimate}fps)"
                                    else "ONLINE • Ready to calibrate (${lastFpsEstimate}fps)"
                                } else {
                                    val modeLabel = when (activeConfig.detectionMode) {
                                        DetectionMode.FAIRING_COLOR -> "Multi-Color CIE"
                                        DetectionMode.SINGLE_COLOR -> if (strings.isSpanish) {
                                            "1 Color${if (activeConfig.singleTargetColorName.isNotBlank()) " (${activeConfig.singleTargetColorName})" else ""}"
                                        } else "1 Color"
                                        DetectionMode.ANY_PASS -> if (strings.isSpanish) "Cualq. Paso" else "Any Pass"
                                    }
                                    val dirLabel = if (activeConfig.directionFilter != PassingDirection.ANY) " • ${activeConfig.directionFilter.name.take(5)}" else ""
                                    val dorsalPart = if (lastDorsalHint.isNotBlank()) " • #$lastDorsalHint" else ""
                                    "${if (strings.isSpanish) "LISTA" else "READY"} • ${lastFpsEstimate}fps • $modeLabel$dirLabel$dorsalPart"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (localCalibrated) mx.telemetryGreen else mx.goldBright
                            )
                            val lanTag = state.activeDirectorLanIp?.let { "Wi-Fi $it" }
                            Text(
                                text = if (state.antennaSyncOk) {
                                    val raceNote = if (state.roomState.sessionPhase != SessionPhase.IDLE) {
                                        if (strings.isSpanish) " • Silencio Carrera" else " • Race Silent"
                                    } else ""
                                    val linkNote = if (lanTag != null) " • $lanTag" else ""
                                    if (strings.isSpanish) "Envío OK (${state.antennaUploadCount})$linkNote$raceNote"
                                    else "Sync OK (${state.antennaUploadCount})$linkNote$raceNote"
                                } else {
                                    if (strings.isSpanish) "Sin enlace nube/Wi-Fi • Toca icono Wi-Fi para IP Director"
                                    else "No cloud/Wi-Fi link • Tap Wi-Fi icon for Director IP"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.antennaSyncOk) mx.telemetryGreen else mx.goldBright,
                                maxLines = 2
                            )
                        }
                    }

                    Spacer(Modifier.width(6.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = {
                                touchActivity()
                                showLanIpDialog = true
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.65f))
                                .minimumInteractiveComponentSize()
                                .testTag("antenna_lan_ip_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = if (strings.isSpanish) "IP Wi-Fi Director" else "Director Wi-Fi IP",
                                tint = if (state.activeDirectorLanIp != null) mx.telemetryGreen else mx.goldAccent
                            )
                        }
                        IconButton(
                            onClick = {
                                touchActivity()
                                batterySaverBlackScreen = true
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.65f))
                                .minimumInteractiveComponentSize()
                                .testTag("antenna_battery_black_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.BatterySaver,
                                contentDescription = if (strings.isSpanish) "Pantalla Negra Ahorro" else "Black Battery Screen",
                                tint = mx.goldAccent
                            )
                        }
                        IconButton(
                            onClick = {
                                touchActivity()
                                viewModel.toggleAntennaVoice()
                            },
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
                            onClick = {
                                touchActivity()
                                stationChosen = false
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.65f))
                                .minimumInteractiveComponentSize()
                                .testTag("antenna_change_station")
                        ) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = "Station", tint = Color.White)
                        }
                        IconButton(
                            onClick = {
                                touchActivity()
                                onBack()
                            },
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

                // "Captura enviada" banner for ~4s after every pass detection
                val showCaptureBanner = state.lastCaptureFeedback != null &&
                    (tickerMonoMs - state.lastCaptureFeedbackAtMs) in 0L..4200L
                if (showCaptureBanner) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                (if (state.lastCaptureOk) Color(0xFF14532D) else Color(0xFF7F1D1D)).copy(alpha = 0.92f)
                            )
                            .border(
                                1.5.dp,
                                if (state.lastCaptureOk) mx.telemetryGreen else mx.racingRed,
                                RoundedCornerShape(999.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("antenna_capture_feedback_pill"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (state.lastCaptureOk) mx.telemetryGreen else mx.goldBright,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = state.lastCaptureFeedback.orEmpty(),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Bottom local calibration & detected colors readout
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (lastDetectedColors.isNotEmpty()) {
                    GlassRaceCard {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
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

                val calBtnBg = if (localCalibrated) mx.telemetryGreen else mx.goldAccent
                Mx3dButton(
                    onClick = {
                        touchActivity()
                        if (calibratingCountdown > 0) return@Mx3dButton
                        scope.launch {
                            for (s in 3 downTo 1) {
                                calibratingCountdown = s
                                if (state.antennaVoiceEnabled) viewModel.voiceEngine.speak(s.toString())
                                delay(1000L)
                            }
                            calibratingCountdown = 0
                            visionEngine.requestLocalCalibration()
                            localCalibrated = true
                            viewModel.calibrateAntennaLocally(state.selectedAntennaStation)
                            if (state.antennaVoiceEnabled) {
                                viewModel.voiceEngine.speak(
                                    if (strings.isSpanish) "Antena calibrada y lista" else "Antenna calibrated and ready"
                                )
                            }
                        }
                    },
                    containerColor = calBtnBg,
                    contentColor = contrastTextOn(calBtnBg),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("antenna_local_calibrate_button")
                ) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            calibratingCountdown > 0 -> "${calibratingCountdown}s..."
                            localCalibrated -> if (strings.isSpanish) "Recalibrar Aquí (3s)" else "Recalibrate Here (3s)"
                            else -> if (strings.isSpanish) "Calibrar Aquí (3s) — O desde Dirección" else "Calibrate Here (3s) — Or from Director"
                        }
                    )
                }
            }

            // OLED Battery Saver Pure Black Overlay (CameraX stays 100% active underneath!)
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
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Default.BatterySaver,
                            contentDescription = null,
                            tint = Color(0xFF4B5563),
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = if (strings.isSpanish) "ANTENA ACTIVA • AHORRO BATERÍA" else "ANTENNA RUNNING • BATTERY SAVER",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF9CA3AF),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "${state.selectedAntennaStation.name} • $lastFpsEstimate FPS • ${state.antennaUploadCount} envíos",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF6B7280),
                            textAlign = TextAlign.Center
                        )
                        if (state.lastCaptureFeedback != null && (tickerMonoMs - state.lastCaptureFeedbackAtMs) in 0L..5000L) {
                            Text(
                                text = state.lastCaptureFeedback.orEmpty(),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (state.lastCaptureOk) Color(0xFF4ADE80) else Color(0xFFF87171)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Mx3dButton(
                            onClick = {
                                touchActivity()
                                batterySaverBlackScreen = false
                            },
                            containerColor = Color(0xFF1F2937),
                            contentColor = Color.White,
                            modifier = Modifier
                                .fillMaxWidth(0.75f)
                                .testTag("antenna_unlock_black_screen")
                        ) {
                            Icon(Icons.Default.LockOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (strings.isSpanish) "Desbloquear Pantalla" else "Unlock Screen")
                        }
                    }
                }
            }

            if (showLanIpDialog) {
                AlertDialog(
                    onDismissRequest = { showLanIpDialog = false },
                    title = {
                        Text(if (strings.isSpanish) "Enlace Wi-Fi Directo con Director" else "Direct Wi-Fi Link to Director")
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (strings.isSpanish) {
                                    "Conecta ambos móviles al mismo Wi-Fi o Zona Wi-Fi. Si no se enlazan solos, escribe la IP que aparece en la pantalla de Dirección:"
                                } else {
                                    "Connect both phones to the same Wi-Fi or Hotspot. If auto-discovery fails, enter the IP shown in Director:"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            OutlinedTextField(
                                value = manualIpInput,
                                onValueChange = { manualIpInput = it },
                                label = { Text("IP Director (ej: 192.168.43.1)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("antenna_manual_ip_input")
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                touchActivity()
                                viewModel.setManualDirectorLanIp(manualIpInput)
                                showLanIpDialog = false
                            }
                        ) {
                            Text(if (strings.isSpanish) "Conectar" else "Connect")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                touchActivity()
                                showLanIpDialog = false
                            }
                        ) {
                            Text(if (strings.isSpanish) "Cerrar" else "Close")
                        }
                    }
                )
            }
        }
    }
}
