package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.model.ActiveRoomRider
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
import com.example.data.model.formatLapMs
import com.example.i18n.MxStrings
import com.example.ui.components.Base64ImageView
import com.example.ui.components.CarbonStadiumBackdrop
import com.example.ui.components.FlagAndRecordFrameOverlay
import com.example.ui.components.GlassRaceCard
import com.example.ui.components.MxTopHeader
import com.example.ui.components.PulsingLiveDot
import com.example.ui.components.RiderDorsalBadge
import com.example.ui.components.SupercrossStartGateCard
import com.example.ui.theme.LocalMxColors
import com.example.ui.theme.contrastTextOn
import com.example.ui.viewmodel.MxProViewModel
import com.example.ui.viewmodel.MxUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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

    LaunchedEffect(state.activeCircuit.id) {
        while (isActive) {
            viewModel.refreshAntennasAndCaptures()
            delay(1500L)
        }
    }

    val lastHotlapAtMs = if (state.roomState.lastPaAnnouncement?.isHotlap == true) {
        state.roomState.lastPaAnnouncement.timestampMs
    } else 0L

    // Fullscreen 30s Starting Lights overlay when active
    if (state.roomState.sessionPhase == SessionPhase.COUNTDOWN_30 &&
        state.roomState.phaseEndTimeMs > liveNowMs
    ) {
        val remSec = ((state.roomState.phaseEndTimeMs - liveNowMs) / 1000L).toInt().coerceAtLeast(0)
        val litCount = when {
            remSec <= 1 -> 5
            remSec <= 3 -> 4
            remSec <= 6 -> 3
            remSec <= 10 -> 2
            remSec <= 16 -> 1
            else -> 0
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (i in 1..5) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (i <= litCount) Color(0xFFE63946) else Color(0xFF27272A))
                                .border(2.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                        )
                    }
                }
                Text(
                    text = "${remSec}s",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp),
                    color = Color.White
                )
                SupercrossStartGateCard(
                    sessionPhase = state.roomState.sessionPhase,
                    phaseEndTimeMs = state.roomState.phaseEndTimeMs,
                    phaseStartTimeMs = state.roomState.phaseStartTimeMs,
                    nowMs = liveNowMs,
                    isSpanish = strings.isSpanish,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                OutlinedButton(onClick = { viewModel.resetHeat() }) {
                    Text(if (strings.isSpanish) "Cancelar Semáforo" else "Cancel Start Gate", color = Color.White)
                }
            }
        }
        return
    }

    var selectedAntennaId by remember { mutableStateOf(AntennaStation.ANTENA_1.id) }

    CarbonStadiumBackdrop {
        Box(modifier = Modifier.fillMaxSize()) {
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
                    onToggleLang = { viewModel.toggleLanguageOverride() },
                    paMuted = state.paAudioMuted,
                    onTogglePaMute = { viewModel.togglePaAudioMute() }
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
                        .padding(vertical = 10.dp)
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

                // Live Spoken PA Ticker (shows what pilots & antennas are hearing)
                val lastPa = state.roomState.lastPaAnnouncement
                if (lastPa != null) {
                    val paText = if (strings.isSpanish) lastPa.textEs else lastPa.textEn
                    if (paText.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (lastPa.isHotlap) mx.hotlapPurple.copy(alpha = 0.20f)
                                    else mx.glassBorder.copy(alpha = 0.28f)
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Campaign,
                                contentDescription = null,
                                tint = if (lastPa.isHotlap) mx.hotlapPurple else mx.goldBright,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "PA: $paText",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (lastPa.isHotlap) mx.hotlapPurple else mx.goldBright,
                                maxLines = 1
                            )
                        }
                    }
                }

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isLandscapeSplit = maxWidth >= 600.dp
                    if (isLandscapeSplit) {
                        // SPLIT-SCREEN LANDSCAPE: Camera left, Timing & Director Controls right
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(0.45f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                DirectorAntennaCameraPreviewCard(
                                    state = state,
                                    strings = strings,
                                    viewModel = viewModel,
                                    selectedAntennaId = selectedAntennaId,
                                    onSelectAntenna = { selectedAntennaId = it },
                                    liveNowMs = liveNowMs
                                )
                                SupercrossStartGateCard(
                                    sessionPhase = state.roomState.sessionPhase,
                                    phaseEndTimeMs = state.roomState.phaseEndTimeMs,
                                    phaseStartTimeMs = state.roomState.phaseStartTimeMs,
                                    nowMs = liveNowMs,
                                    isSpanish = strings.isSpanish
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .weight(0.55f)
                                    .fillMaxHeight()
                            ) {
                                // Live Standings Ribbon on top right
                                val standings = state.roomState.activeRiders.values.sortedWith(
                                    compareByDescending<ActiveRoomRider> { it.lapsCount }
                                        .thenBy { if (it.bestLapMs <= 0L) Long.MAX_VALUE else it.bestLapMs }
                                )
                                if (standings.isNotEmpty()) {
                                    LazyRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(standings.take(6)) { r ->
                                            val pos = standings.indexOf(r) + 1
                                            val cp = state.colorProfiles.firstOrNull { it.riderId == r.riderId }
                                            GlassRaceCard {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    RiderDorsalBadge(
                                                        dorsal = r.dorsal,
                                                        discipline = r.discipline,
                                                        colorProfile = cp,
                                                        position = pos
                                                    )
                                                    Column {
                                                        Text(r.riderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                                        Text("${r.lapsCount}L • ${formatLapMs(r.bestLapMs)}", style = MaterialTheme.typography.labelSmall, color = mx.telemetryGreen)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 4.dp) {
                                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(strings.roomAndHeats) })
                                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(strings.antennaMonitor) })
                                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text(strings.colorWarmup) })
                                    Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text(strings.setupWizard) })
                                    Tab(selected = selectedTab == 4, onClick = { selectedTab = 4 }, text = { Text(strings.clubAdmin) })
                                }
                                when (selectedTab) {
                                    0 -> DirectorRoomControlTab(state, strings, viewModel, liveNowMs)
                                    1 -> DirectorAntennaMonitorTab(state, strings, viewModel, liveNowMs, selectedAntennaId) { selectedAntennaId = it }
                                    2 -> DirectorColorWarmupTab(state, strings, viewModel, liveNowMs)
                                    3 -> DirectorSetupWizardTab(strings)
                                    else -> DirectorClubAdminTab(state, strings, viewModel)
                                }
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 8.dp) {
                                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(strings.roomAndHeats) })
                                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(strings.antennaMonitor) })
                                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text(strings.colorWarmup) })
                                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text(strings.setupWizard) })
                                Tab(selected = selectedTab == 4, onClick = { selectedTab = 4 }, text = { Text(strings.clubAdmin) })
                            }
                            when (selectedTab) {
                                0 -> DirectorRoomControlTab(state, strings, viewModel, liveNowMs)
                                1 -> DirectorAntennaMonitorTab(state, strings, viewModel, liveNowMs, selectedAntennaId) { selectedAntennaId = it }
                                2 -> DirectorColorWarmupTab(state, strings, viewModel, liveNowMs)
                                3 -> DirectorSetupWizardTab(strings)
                                else -> DirectorClubAdminTab(state, strings, viewModel)
                            }
                        }
                    }
                }
            }

            FlagAndRecordFrameOverlay(
                flagState = state.roomState.flagState,
                lastHotlapAtMs = lastHotlapAtMs,
                nowMs = liveNowMs
            )
        }
    }
}

@Composable
private fun DirectorRoomControlTab(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel,
    liveNowMs: Long
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
        SupercrossStartGateCard(
            sessionPhase = room.sessionPhase,
            phaseEndTimeMs = room.phaseEndTimeMs,
            phaseStartTimeMs = room.phaseStartTimeMs,
            nowMs = liveNowMs,
            isSpanish = strings.isSpanish
        )
        // Active Session Status + Countdown + STOP Button
        if (room.sessionPhase != SessionPhase.IDLE) {
            val remSec = if (room.phaseEndTimeMs > liveNowMs) {
                ((room.phaseEndTimeMs - liveNowMs) / 1000L).toInt().coerceAtLeast(0)
            } else 0
            val mm = remSec / 60
            val ss = remSec % 60
            val phaseLabel = when (room.sessionPhase) {
                SessionPhase.WARMUP -> if (strings.isSpanish) "WARM-UP EN CURSO (CAPTURA DE COLORES)" else "WARM-UP IN PROGRESS (COLOR CAPTURE)"
                SessionPhase.RACING -> if (strings.isSpanish) "CARRERA EN VIVO" else "LIVE RACE"
                SessionPhase.PAUSED_YELLOW -> if (strings.isSpanish) "BANDERA AMARILLA (PAUSA)" else "YELLOW FLAG (PAUSED)"
                SessionPhase.RANDOM_GATE -> if (strings.isSpanish) "VALLA MX ACTIVA" else "MX GATE ACTIVE"
                else -> room.sessionPhase.name
            }
            GlassRaceCard(accentBorder = mx.racingRed) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            PulsingLiveDot()
                            Spacer(Modifier.width(8.dp))
                            Text(phaseLabel, style = MaterialTheme.typography.titleMedium, color = mx.goldBright)
                        }
                        if (room.phaseEndTimeMs > 0L) {
                            Text(
                                text = String.format("%d:%02d", mm, ss),
                                style = MaterialTheme.typography.headlineMedium,
                                color = mx.telemetryGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Button(
                        onClick = { viewModel.stopSession() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = mx.racingRed,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("director_stop_session_btn")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (room.sessionPhase == SessionPhase.WARMUP) {
                                if (strings.isSpanish) "STOP WARM-UP (A PARRILLA)" else "STOP WARM-UP (TO GRID)"
                            } else {
                                if (strings.isSpanish) "STOP CARRERA / DETENER SESIÓN" else "STOP RACE / SESSION"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

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
                colors = ButtonDefaults.buttonColors(
                    containerColor = mx.goldAccent,
                    contentColor = contrastTextOn(mx.goldAccent)
                ),
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("director_warmup_btn")
            ) { Text(strings.startWarmup) }
            Button(
                onClick = { viewModel.start30sCountdown(friendly = false) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = mx.racingRed,
                    contentColor = Color.White
                ),
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
                        colors = ButtonDefaults.buttonColors(
                            containerColor = mx.telemetryGreen,
                            contentColor = contrastTextOn(mx.telemetryGreen)
                        ),
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
private fun DirectorAntennaCameraPreviewCard(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel,
    selectedAntennaId: String,
    onSelectAntenna: (String) -> Unit,
    liveNowMs: Long
) {
    val mx = LocalMxColors.current
    val cfg = state.antennas[selectedAntennaId]
        ?: AntennaConfig(antennaId = selectedAntennaId, circuitId = state.activeCircuit.id)
    val ant1Online = (state.antennas[AntennaStation.ANTENA_1.id]?.lastHeartbeatMs ?: 0L) > liveNowMs - 15_000L
    val ant2Online = (state.antennas[AntennaStation.ANTENA_2.id]?.lastHeartbeatMs ?: 0L) > liveNowMs - 15_000L
    val tempOnline = (state.antennas[AntennaStation.ANTENA_TEMP.id]?.lastHeartbeatMs ?: 0L) > liveNowMs - 15_000L
    val selectedOnline = cfg.lastHeartbeatMs > liveNowMs - 15_000L

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${if (strings.isSpanish) "Circuito" else "Circuit"}: ${state.activeCircuit.name}",
                style = MaterialTheme.typography.labelMedium,
                color = mx.subtleText
            )
            IconButton(onClick = { viewModel.refreshAntennasAndCaptures() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = mx.goldAccent)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedAntennaId == AntennaStation.ANTENA_1.id,
                onClick = { onSelectAntenna(AntennaStation.ANTENA_1.id) },
                label = { Text("A1 ${if (ant1Online) "●" else "○"}") }
            )
            FilterChip(
                selected = selectedAntennaId == AntennaStation.ANTENA_2.id,
                onClick = { onSelectAntenna(AntennaStation.ANTENA_2.id) },
                label = { Text("A2 ${if (ant2Online) "●" else "○"}") }
            )
            FilterChip(
                selected = selectedAntennaId == AntennaStation.ANTENA_TEMP.id,
                onClick = { onSelectAntenna(AntennaStation.ANTENA_TEMP.id) },
                label = { Text("Temp ${if (tempOnline) "●" else "○"}") }
            )
        }

        GlassRaceCard {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${cfg.antennaId.uppercase()} • ${cfg.status}", style = MaterialTheme.typography.titleMedium, color = mx.goldAccent)
                    Text(
                        text = if (selectedOnline) (if (strings.isSpanish) "CONECTADA" else "ONLINE")
                        else (if (strings.isSpanish) "SIN SEÑAL" else "OFFLINE"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selectedOnline) mx.telemetryGreen else mx.racingRed,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(175.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (cfg.lastSnapshotBase64.isNotBlank()) {
                        Base64ImageView(
                            base64 = cfg.lastSnapshotBase64,
                            contentDescription = "Live Antenna Preview",
                            modifier = Modifier.fillMaxSize()
                        )
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val roiW = size.width * cfg.roiSize.widthFrac
                            val roiH = size.height * cfg.roiSize.heightFrac
                            val roiLeft = (size.width - roiW) / 2f
                            val roiTop = (size.height * cfg.roiHeight.centerYFrac - roiH / 2f).coerceIn(0f, size.height - roiH)
                            drawRect(
                                color = if (cfg.isCalibrated) Color(0xFF22C55E) else Color(0xFFF5B731),
                                topLeft = Offset(roiLeft, roiTop),
                                size = Size(roiW, roiH),
                                style = Stroke(width = 2.5.dp.toPx())
                            )
                            if (cfg.lineOrientation == LineOrientation.VERTICAL) {
                                val lx = roiLeft + roiW * cfg.linePosition
                                drawLine(Color(0xFFE63946), Offset(lx, roiTop), Offset(lx, roiTop + roiH), strokeWidth = 2.5.dp.toPx())
                            } else {
                                val ly = roiTop + roiH * cfg.linePosition
                                drawLine(Color(0xFFE63946), Offset(roiLeft, ly), Offset(roiLeft + roiW, ly), strokeWidth = 2.5.dp.toPx())
                            }
                        }
                    } else {
                        Text(
                            if (strings.isSpanish) "Sin imagen aún: abre Antenas en el otro móvil dentro del mismo circuito (${state.activeCircuit.name})"
                            else "No live feed yet: open Antennas on the camera phone on circuit (${state.activeCircuit.name})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = mx.subtleText,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Button(
                    onClick = { viewModel.triggerRemoteCalibration(selectedAntennaId) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = mx.telemetryGreen,
                        contentColor = contrastTextOn(mx.telemetryGreen)
                    ),
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("director_calibrate_antenna_btn")
                ) {
                    Text(strings.calibrateEmptyTrack)
                }
            }
        }
    }
}

@Composable
private fun DirectorAntennaMonitorTab(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel,
    liveNowMs: Long,
    selectedAntennaId: String,
    onSelectAntenna: (String) -> Unit
) {
    val cfg = state.antennas[selectedAntennaId]
        ?: AntennaConfig(antennaId = selectedAntennaId, circuitId = state.activeCircuit.id)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DirectorAntennaCameraPreviewCard(
            state = state,
            strings = strings,
            viewModel = viewModel,
            selectedAntennaId = selectedAntennaId,
            onSelectAntenna = onSelectAntenna,
            liveNowMs = liveNowMs
        )

        GlassRaceCard {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

                Text(if (strings.isSpanish) "Modo de Detección" else "Detection Mode", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = cfg.detectionMode == DetectionMode.ANY_PASS,
                            onClick = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, detectionMode = DetectionMode.ANY_PASS) },
                            label = { Text(if (strings.isSpanish) "Cualquier Paso" else "Any Pass") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = cfg.detectionMode == DetectionMode.FAIRING_COLOR,
                            onClick = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, detectionMode = DetectionMode.FAIRING_COLOR) },
                            label = { Text(if (strings.isSpanish) "Multi-Color CIE" else "Multi-Color") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = cfg.detectionMode == DetectionMode.SINGLE_COLOR,
                            onClick = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, detectionMode = DetectionMode.SINGLE_COLOR) },
                            label = { Text(if (strings.isSpanish) "Solo 1 Color" else "Single Color") }
                        )
                    }
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
