package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.model.AntennaConfig
import com.example.data.model.AntennaStation
import com.example.data.model.AntiVibrationLevel
import com.example.data.model.DetectionMode
import com.example.data.model.FlagState
import com.example.data.model.GameMode
import com.example.data.model.LineOrientation
import com.example.data.model.RoiHeight
import com.example.data.model.RoiSize
import com.example.data.model.SessionPhase
import com.example.i18n.MxStrings
import com.example.ui.components.Base64ImageView
import com.example.ui.components.CarbonStadiumBackdrop
import com.example.ui.components.GlassRaceCard
import com.example.ui.components.MxTopHeader
import com.example.ui.theme.LocalMxColors
import com.example.ui.viewmodel.MxProViewModel
import com.example.ui.viewmodel.MxUiState
import kotlinx.coroutines.delay

@Composable
fun DirectorScreen(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel,
    onBack: () -> Unit
) {
    val mx = LocalMxColors.current
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var isTalkingPa by remember { mutableStateOf(false) }

    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    var liveNowMs by remember { mutableLongStateOf(viewModel.syncedNowMs()) }
    LaunchedEffect(Unit) {
        viewModel.loadAdminReports()
        while (true) {
            liveNowMs = viewModel.syncedNowMs()
            delay(100L)
        }
    }

    // Fullscreen 30s Starting Lights overlay when active
    if (state.roomState.sessionPhase == SessionPhase.COUNTDOWN_30 &&
        state.roomState.phaseEndTimeMs > liveNowMs
    ) {
        val remSec = ((state.roomState.phaseEndTimeMs - liveNowMs) / 1000L).toInt().coerceAtLeast(0)
        val litCount = when {
            remSec <= 1 -> 5
            remSec <= 3 -> 4
            remSec <= 5 -> 3
            remSec <= 8 -> 2
            remSec <= 12 -> 1
            else -> 0
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (i in 1..5) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (i <= litCount) Color(0xFFE63946) else Color(0xFF27272A))
                                .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                        )
                    }
                }
                Text(
                    text = "${remSec}s",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp),
                    color = Color.White
                )
                OutlinedButton(onClick = { viewModel.resetHeat() }) {
                    Text(if (strings.isSpanish) "Cancelar Semáforo" else "Cancel Start Gate")
                }
            }
        }
        return
    }

    CarbonStadiumBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            MxTopHeader(
                title = strings.directorConsole,
                isOnline = state.isOnline,
                sunMode = state.sunMode,
                isSpanish = strings.isSpanish,
                onBack = onBack,
                onToggleSunMode = { viewModel.toggleSunMode() },
                onToggleLang = { viewModel.toggleLanguageOverride() }
            )

            // Live PA Walkie-Talkie Hold-to-Talk Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isTalkingPa) mx.racingRed else mx.goldAccent.copy(alpha = 0.22f))
                    .border(1.dp, if (isTalkingPa) mx.racingRed else mx.goldAccent, RoundedCornerShape(14.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                                if (!granted) {
                                    audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    return@detectTapGestures
                                }
                                isTalkingPa = viewModel.voiceEngine.startPaRecording()
                                tryAwaitRelease()
                                if (isTalkingPa) {
                                    isTalkingPa = false
                                    val b64 = viewModel.voiceEngine.stopPaRecordingToBase64()
                                    if (!b64.isNullOrBlank()) {
                                        viewModel.broadcastPaAudioClip(b64)
                                    }
                                }
                            }
                        )
                    }
                    .padding(vertical = 12.dp)
                    .testTag("director_pa_hold_button"),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = strings.paBroadcastHold,
                        tint = if (isTalkingPa) Color.White else mx.goldAccent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isTalkingPa) "ON AIR..." else strings.paBroadcastHold,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isTalkingPa) Color.White else MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 8.dp) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(strings.roomAndHeats) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(strings.antennaMonitor) })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text(strings.colorWarmup) })
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text(strings.setupWizard) })
                Tab(selected = selectedTab == 4, onClick = { selectedTab = 4 }, text = { Text(strings.clubAdmin) })
            }

            when (selectedTab) {
                0 -> DirectorRoomControlTab(state, strings, viewModel)
                1 -> DirectorAntennaMonitorTab(state, strings, viewModel)
                2 -> DirectorColorWarmupTab(state, strings, viewModel)
                3 -> DirectorSetupWizardTab(strings)
                else -> DirectorClubAdminTab(state, strings, viewModel)
            }
        }
    }
}

@Composable
private fun DirectorRoomControlTab(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel
) {
    val mx = LocalMxColors.current
    val room = state.roomState

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassRaceCard {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (strings.isSpanish) "Modo de Juego" else "Game Mode", style = MaterialTheme.typography.titleMedium, color = mx.goldAccent)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(GameMode.entries) { gm ->
                        FilterChip(
                            selected = room.gameMode == gm,
                            onClick = { viewModel.updateRoomSettings(gameMode = gm) },
                            label = { Text(gm.name.replace("_", " ")) }
                        )
                    }
                }

                Text(if (strings.isSpanish) "Duración de Manga" else "Heat Duration", style = MaterialTheme.typography.titleMedium, color = mx.goldAccent)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(180, 300, 480, 600).forEach { sec ->
                        FilterChip(
                            selected = room.heatDurationSec == sec,
                            onClick = { viewModel.updateRoomSettings(heatDurationSec = sec) },
                            label = { Text("${sec / 60}m") }
                        )
                    }
                }

                Text(if (strings.isSpanish) "Sensibilidad Anti-Vibración" else "Anti-Vibration Sensitivity", style = MaterialTheme.typography.titleMedium, color = mx.goldAccent)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AntiVibrationLevel.entries.forEach { lvl ->
                        FilterChip(
                            selected = room.antiVibration == lvl,
                            onClick = { viewModel.updateRoomSettings(antiVibration = lvl) },
                            label = { Text(lvl.name) }
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { viewModel.startWarmup(friendly = false) },
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("director_warmup_btn")
            ) { Text(strings.startWarmup) }
            Button(
                onClick = { viewModel.start30sCountdown(friendly = false) },
                colors = ButtonDefaults.buttonColors(containerColor = mx.racingRed),
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("director_30s_gate_btn")
            ) { Text(strings.startLight30s) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { viewModel.startRandomMxGate(friendly = false) },
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
            ) { Text(strings.randomGate) }
            OutlinedButton(
                onClick = {
                    val next = if (room.flagState == FlagState.GREEN) FlagState.YELLOW else FlagState.GREEN
                    viewModel.updateRoomSettings(flagState = next)
                },
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.Flag, contentDescription = null, tint = if (room.flagState == FlagState.YELLOW) mx.goldBright else mx.telemetryGreen)
                Spacer(Modifier.width(4.dp))
                Text(if (room.flagState == FlagState.YELLOW) "Green Flag" else "Yellow Flag")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { viewModel.lockActiveCircuitGpsHere() },
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.GpsFixed, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (strings.isSpanish) "Fijar GPS Circuito" else "Lock Track GPS")
            }
            OutlinedButton(
                onClick = { viewModel.resetHeat() },
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (strings.isSpanish) "Reiniciar Manga" else "Reset Heat")
            }
        }

        // Campeonatos MXGP
        var newChampName by remember { mutableStateOf("") }
        var newChampHeats by remember { mutableIntStateOf(3) }
        val activeChamp = state.championships.firstOrNull { it.id == state.activeChampionshipId }
        GlassRaceCard(accentBorder = mx.goldAccent.copy(alpha = 0.6f)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = mx.goldAccent)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (strings.isSpanish) "Campeonatos por Mangas (Puntos MXGP)" else "Multi-Heat Championships (MXGP Points)",
                        style = MaterialTheme.typography.titleMedium,
                        color = mx.goldAccent
                    )
                }
                if (state.championships.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.championships) { ch ->
                            FilterChip(
                                selected = ch.id == state.activeChampionshipId,
                                onClick = { viewModel.selectActiveChampionship(ch.id) },
                                label = { Text("${ch.name} (${ch.completedHeats}/${ch.totalHeats})") }
                            )
                        }
                    }
                }
                if (activeChamp != null) {
                    Button(
                        onClick = { viewModel.awardChampionshipHeatPoints() },
                        colors = ButtonDefaults.buttonColors(containerColor = mx.telemetryGreen),
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("director_award_champ_points")
                    ) {
                        Text(
                            if (strings.isSpanish) "Cerrar Manga y Sumar Puntos a '${activeChamp.name}'"
                            else "Finish Heat & Award Points to '${activeChamp.name}'"
                        )
                    }
                    activeChamp.standings.values
                        .sortedByDescending { it.points }
                        .take(5)
                        .forEachIndexed { idx, st ->
                            Text(
                                "${idx + 1}. ${st.riderName} #${st.dorsal} — ${st.points} pts (${st.heatsRaced}M)",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newChampName,
                        onValueChange = { newChampName = it },
                        label = { Text(if (strings.isSpanish) "Nuevo Campeonato" else "New Championship") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = true,
                        onClick = { newChampHeats = if (newChampHeats >= 6) 2 else newChampHeats + 1 },
                        label = { Text("${newChampHeats}M") }
                    )
                }
                OutlinedButton(
                    onClick = {
                        viewModel.createChampionship(newChampName, newChampHeats)
                        newChampName = ""
                    },
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                ) {
                    Text(if (strings.isSpanish) "Crear Campeonato" else "Create Championship")
                }
            }
        }
    }
}

@Composable
private fun DirectorAntennaMonitorTab(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel
) {
    val mx = LocalMxColors.current
    var selectedAntennaId by remember { mutableStateOf(AntennaStation.ANTENA_1.id) }
    val cfg = state.antennas[selectedAntennaId]
        ?: AntennaConfig(antennaId = selectedAntennaId, circuitId = state.activeCircuit.id)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedAntennaId == AntennaStation.ANTENA_1.id,
                onClick = { selectedAntennaId = AntennaStation.ANTENA_1.id },
                label = { Text("Antena 1") }
            )
            FilterChip(
                selected = selectedAntennaId == AntennaStation.ANTENA_2.id,
                onClick = { selectedAntennaId = AntennaStation.ANTENA_2.id },
                label = { Text("Antena 2") }
            )
            FilterChip(
                selected = selectedAntennaId == AntennaStation.ANTENA_TEMP.id,
                onClick = { selectedAntennaId = AntennaStation.ANTENA_TEMP.id },
                label = { Text("Temp") }
            )
        }

        GlassRaceCard {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${cfg.antennaId.uppercase()} • ${cfg.status}", style = MaterialTheme.typography.titleMedium, color = mx.goldAccent)
                if (cfg.lastSnapshotBase64.isNotBlank()) {
                    Base64ImageView(
                        base64 = cfg.lastSnapshotBase64,
                        contentDescription = "Live Antenna Preview",
                        modifier = Modifier.fillMaxWidth().height(165.dp).clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (strings.isSpanish) "Sin imagen en vivo aún (conecta teléfono Antena)" else "No live feed yet (connect Antenna phone)",
                            color = mx.subtleText
                        )
                    }
                }

                Button(
                    onClick = { viewModel.triggerRemoteCalibration(selectedAntennaId) },
                    colors = ButtonDefaults.buttonColors(containerColor = mx.telemetryGreen),
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("director_calibrate_antenna_btn")
                ) {
                    Text(strings.calibrateEmptyTrack)
                }

                Text(if (strings.isSpanish) "Modo de Detección" else "Detection Mode", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = cfg.detectionMode == DetectionMode.ANY_PASS,
                        onClick = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, detectionMode = DetectionMode.ANY_PASS) },
                        label = { Text(if (strings.isSpanish) "Cualquier Paso" else "Any Pass") }
                    )
                    FilterChip(
                        selected = cfg.detectionMode == DetectionMode.FAIRING_COLOR,
                        onClick = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, detectionMode = DetectionMode.FAIRING_COLOR) },
                        label = { Text(if (strings.isSpanish) "Color Carenado" else "Fairing Color") }
                    )
                }

                Text(if (strings.isSpanish) "Orientación y Posición Línea (${(cfg.linePosition * 100).toInt()}%)" else "Line Orientation & Position (${(cfg.linePosition * 100).toInt()}%)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = cfg.lineOrientation == LineOrientation.VERTICAL,
                        onClick = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, lineOrientation = LineOrientation.VERTICAL) },
                        label = { Text("Vertical") }
                    )
                    FilterChip(
                        selected = cfg.lineOrientation == LineOrientation.HORIZONTAL,
                        onClick = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, lineOrientation = LineOrientation.HORIZONTAL) },
                        label = { Text("Horizontal") }
                    )
                }
                Slider(
                    value = cfg.linePosition,
                    onValueChange = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, linePosition = it) },
                    valueRange = 0.1f..0.9f
                )

                Text(if (strings.isSpanish) "Tamaño Túnel ROI" else "ROI Tunnel Size")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoiSize.entries.forEach { sz ->
                        FilterChip(
                            selected = cfg.roiSize == sz,
                            onClick = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, roiSize = sz) },
                            label = { Text(sz.name) }
                        )
                    }
                }
                Text(if (strings.isSpanish) "Altura Túnel ROI" else "ROI Tunnel Height")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoiHeight.entries.forEach { rh ->
                        FilterChip(
                            selected = cfg.roiHeight == rh,
                            onClick = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, roiHeight = rh) },
                            label = { Text(rh.name) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectorSetupWizardTab(strings: MxStrings) {
    val steps = if (strings.isSpanish) {
        listOf(
            "Colocar Antena 1 en línea de meta y Antena 2 de refuerzo en trípode firme.",
            "Situar panel gris o negro mate al fondo para contraste limpio de carenados.",
            "Ajustar rectángulo Túnel ROI (S/M/L) y orientación de línea desde Antenas.",
            "Pulsar Calibrar Pista Vacía sin motos en el visor.",
            "Lanzar Warm-Up 3 min y asignar muestras CIE Lab de cada moto.",
            "Fijar GPS oficial del circuito y dar Semáforo 30s o Valla MX Aleatoria."
        )
    } else {
        listOf(
            "Mount Antenna 1 at finish line & Antenna 2 backup on steady tripod.",
            "Place matte gray/black backdrop panel behind finish line.",
            "Adjust ROI Tunnel size (S/M/L) and line orientation remotely.",
            "Tap Calibrate Empty Track with track clear.",
            "Launch 3-min Warm-Up and assign CIE Lab color samples per bike.",
            "Lock official Circuit GPS and trigger 30s Start Gate or Random MX Gate."
        )
    }
    val checked = remember { mutableStateListOf(false, false, false, false, false, false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(strings.setupWizard, style = MaterialTheme.typography.headlineMedium)
        steps.forEachIndexed { idx, txt ->
            GlassRaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked[idx], onCheckedChange = { checked[idx] = it })
                    Spacer(Modifier.width(8.dp))
                    Text("${idx + 1}. $txt", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
