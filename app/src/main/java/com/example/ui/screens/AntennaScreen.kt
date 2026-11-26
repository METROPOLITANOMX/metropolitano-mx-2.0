package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.engine.VisionTimingEngine
import com.example.i18n.MxStrings
import com.example.ui.components.CarbonStadiumBackdrop
import com.example.ui.components.GlassRaceCard
import com.example.ui.theme.LocalMxColors
import com.example.ui.viewmodel.MxProViewModel
import com.example.ui.viewmodel.MxUiState
import java.util.concurrent.Executors

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
    var lastSnapshotSentMs by remember { mutableLongStateOf(0L) }
    var lastDetectedColors by remember { mutableStateOf<List<LabSample>>(emptyList()) }
    var localCalibrated by remember { mutableStateOf(false) }

    val activeConfig = state.antennas[state.selectedAntennaStation.id]
        ?: AntennaConfig(antennaId = state.selectedAntennaStation.id, circuitId = state.activeCircuit.id)

    LaunchedEffect(activeConfig.calibrateCommandAtMs) {
        if (activeConfig.calibrateCommandAtMs > lastHandledCalCmd && activeConfig.calibrateCommandAtMs > 0L) {
            lastHandledCalCmd = activeConfig.calibrateCommandAtMs
            visionEngine.requestLocalCalibration()
            localCalibrated = true
            viewModel.sendAntennaHeartbeat(state.selectedAntennaStation, isCalibrated = true, status = "READY")
        }
    }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
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
                Spacer(Modifier.height(12.dp))
                Text(strings.antennaChooseStation, style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(6.dp))
                Text(strings.antennaRemoteHint, style = MaterialTheme.typography.bodyMedium, color = mx.subtleText)
                Spacer(Modifier.height(22.dp))

                val stations = listOf(
                    AntennaStation.ANTENA_1 to (if (strings.isSpanish) "Antena 1 (Meta Principal)" else "Antenna 1 (Main Finish)"),
                    AntennaStation.ANTENA_2 to (if (strings.isSpanish) "Antena 2 (Refuerzo)" else "Antenna 2 (Backup)"),
                    AntennaStation.ANTENA_TEMP to (if (strings.isSpanish) "Antena Temporal / Piloto" else "Temporary / Rider Antenna")
                )
                stations.forEach { (station, label) ->
                    Button(
                        onClick = {
                            viewModel.selectAntennaStation(station)
                            stationChosen = true
                            if (!hasCameraPerm) camPermLauncher.launch(Manifest.permission.CAMERA)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = mx.goldAccent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("select_${station.id}")
                    ) {
                        Text(label, style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = onBack, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Text(if (strings.isSpanish) "Salir" else "Exit")
                }
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPerm) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            val now = viewModel.syncedNowMs()
                            val sendSnap = (now - lastSnapshotSentMs) > 2500L
                            val res = visionEngine.processFrame(
                                image = imageProxy,
                                config = activeConfig,
                                profiles = state.colorProfiles,
                                syncedNowMs = now,
                                isSpanish = strings.isSpanish,
                                captureLiveSnapshot = sendSnap
                            )
                            if (res != null) {
                                if (sendSnap && res.snapshotBase64.isNotBlank()) {
                                    lastSnapshotSentMs = now
                                    viewModel.pushAntennaLiveSnapshot(state.selectedAntennaStation, res.snapshotBase64)
                                }
                                if (res.triggered) {
                                    lastDetectedColors = res.detectedColors
                                    viewModel.handleVisionDetection(state.selectedAntennaStation, res)
                                }
                            }
                            imageProxy.close()
                        }
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis
                            )
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
                    if (strings.isSpanish) "Se requiere permiso de cámara para la Antena" else "Camera permission required for Antenna",
                    color = Color.White
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = { camPermLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(if (strings.isSpanish) "Conceder Cámara" else "Grant Camera")
                }
            }
        }

        // Translucent ROI "Efecto Túnel" + Trigger Line Overlay (100% remotely configured by Director)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val roiW = size.width * activeConfig.roiSize.widthFrac
            val roiH = size.height * activeConfig.roiSize.heightFrac
            val roiLeft = (size.width - roiW) / 2f
            val roiTop = (size.height * activeConfig.roiHeight.centerYFrac - roiH / 2f).coerceIn(0f, size.height - roiH)

            // Dim outside tunnel
            drawRect(Color.Black.copy(alpha = 0.42f))
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
                        text = state.selectedAntennaStation.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = mx.goldAccent
                    )
                    Text(
                        text = if (!localCalibrated) {
                            if (strings.isSpanish) "Esperando Calibración de Dirección" else "Waiting for Director Calibration"
                        } else {
                            val modeLabel = if (activeConfig.detectionMode == DetectionMode.FAIRING_COLOR) "CIE Lab Color" else "Any Pass"
                            "${if (strings.isSpanish) "LISTA" else "READY"} • $modeLabel • ROI ${activeConfig.roiSize.name}"
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
                            Text(sample.colorName, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
