package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AntennaStation
import com.example.data.model.FlagState
import com.example.data.model.GameMode
import com.example.data.model.SessionPhase
import com.example.data.model.formatDeltaMs
import com.example.data.model.formatLapMs
import com.example.i18n.MxStrings
import com.example.ui.components.AnimatedCheckeredRibbon
import com.example.ui.components.CarbonStadiumBackdrop
import com.example.ui.components.FlagAndRecordFrameOverlay
import com.example.ui.components.GlassRaceCard
import com.example.ui.components.Mx3dButton
import com.example.ui.components.Mx3dOutlinedButton
import com.example.ui.components.MxTopHeader
import com.example.ui.components.RiderDorsalBadge
import com.example.ui.components.SupercrossStartGateCard
import com.example.ui.theme.LocalMxColors
import com.example.ui.theme.contrastTextOn
import com.example.ui.theme.mxChipColors
import com.example.ui.viewmodel.MxProViewModel
import com.example.ui.viewmodel.MxUiState
import kotlinx.coroutines.delay

@Composable
fun PilotScreen(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel,
    onBack: () -> Unit
) {
    val mx = LocalMxColors.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var transmitterMode by remember { mutableStateOf(false) }
    var pocketMode by remember { mutableStateOf(false) }
    var soloAntennaMode by remember { mutableStateOf(false) }

    var liveNowMs by remember { mutableLongStateOf(viewModel.syncedNowMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            liveNowMs = viewModel.syncedNowMs()
            delay(80L)
        }
    }

    val rider = state.currentRider
    val roomRider = rider?.let { state.roomState.activeRiders[it.id] }
    val sortedStandings = state.roomState.activeRiders.values
        .sortedWith(compareByDescending<com.example.data.model.ActiveRoomRider> { it.lapsCount }.thenBy { if (it.bestLapMs <= 0) Long.MAX_VALUE else it.bestLapMs })
    val myPosition = if (rider != null) sortedStandings.indexOfFirst { it.riderId == rider.id } + 1 else 0
    val currentLapElapsedMs = if (roomRider != null && roomRider.lastPassTimestampMs > 0L) {
        (liveNowMs - roomRider.lastPassTimestampMs).coerceAtLeast(0L)
    } else 0L

    // Solo Pilot Phone-as-Antenna Mode
    if (soloAntennaMode) {
        PilotSoloAntennaOverlay(
            state = state,
            strings = strings,
            viewModel = viewModel,
            onClose = { soloAntennaMode = false }
        )
        return
    }

    // OLED Black Pocket Mode (locked against touch, double-tap to unlock)
    if (pocketMode) {
        BackHandler { pocketMode = false }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { pocketMode = false })
                }
                .testTag("pocket_mode_overlay"),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF475569), modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(14.dp))
                Text(
                    text = strings.pocketModeLocked,
                    color = Color(0xFF64748B),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    // High-contrast Radio Transmitter Mode ("Modo Emisora")
    if (transmitterMode) {
        BackHandler { transmitterMode = false }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF05070A))
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("P${if (myPosition > 0) myPosition else "-"} • ${rider?.name ?: ""}", style = MaterialTheme.typography.displayMedium, color = mx.goldBright)
                OutlinedButton(onClick = { transmitterMode = false }) { Text(if (strings.isSpanish) "Salir" else "Exit") }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(strings.liveLap.uppercase(), style = MaterialTheme.typography.titleLarge, color = Color(0xFF94A3B8))
                Text(
                    text = formatLapMs(currentLapElapsedMs),
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp, lineHeight = 76.sp),
                    color = Color.White
                )
                Spacer(Modifier.height(10.dp))
                val lastDelta = if (roomRider != null && roomRider.bestLapMs > 0 && roomRider.lastLapMs > 0) {
                    roomRider.lastLapMs - roomRider.bestLapMs
                } else 0L
                Text(
                    text = "${strings.bestLap}: ${formatLapMs(roomRider?.bestLapMs ?: 0L)}  (${formatDeltaMs(lastDelta)})",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (lastDelta <= 0L) mx.telemetryGreen else mx.racingRed
                )
            }
            Mx3dButton(
                onClick = { viewModel.recordManualLapForCurrentRider() },
                containerColor = mx.goldAccent,
                contentColor = contrastTextOn(mx.goldAccent),
                modifier = Modifier.fillMaxWidth().height(72.dp).testTag("transmitter_manual_lap")
            ) {
                Text(strings.manualLap.uppercase(), style = MaterialTheme.typography.headlineMedium)
            }
        }
        return
    }

    val lastHotlapAtMs = if (state.roomState.lastPaAnnouncement?.isHotlap == true) {
        state.roomState.lastPaAnnouncement.timestampMs
    } else 0L

    CarbonStadiumBackdrop {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                MxTopHeader(
                    title = "${rider?.name ?: strings.iamPilot} • ${state.activeCircuit.name}",
                    isOnline = state.isOnline,
                    sunMode = state.sunMode,
                    isSpanish = strings.isSpanish,
                    onBack = onBack,
                    onToggleSunMode = { viewModel.toggleSunMode() },
                    onToggleLang = { viewModel.toggleLanguageOverride() },
                    paMuted = state.paAudioMuted,
                    onTogglePaMute = { viewModel.togglePaAudioMute() }
                )

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = mx.goldAccent
                ) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, selectedContentColor = mx.goldAccent, unselectedContentColor = MaterialTheme.colorScheme.onSurface, text = { Text(strings.tabTrackRoom) })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, selectedContentColor = mx.goldAccent, unselectedContentColor = MaterialTheme.colorScheme.onSurface, text = { Text(strings.tabGaragePro) })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, selectedContentColor = mx.goldAccent, unselectedContentColor = MaterialTheme.colorScheme.onSurface, text = { Text(strings.tabTelemetry) })
                }

                when (selectedTab) {
                    0 -> PilotTrackRoomTab(
                        state = state,
                        strings = strings,
                        viewModel = viewModel,
                        liveNowMs = liveNowMs,
                        myPosition = myPosition,
                        currentLapElapsedMs = currentLapElapsedMs,
                        onEnableTransmitterMode = { transmitterMode = true },
                        onEnablePocketMode = { pocketMode = true },
                        onEnableSoloAntennaMode = { soloAntennaMode = true },
                        onLogout = {
                            viewModel.logoutRider()
                            onBack()
                        }
                    )
                    1 -> PilotGarageProTab(state = state, strings = strings, viewModel = viewModel)
                    else -> PilotTelemetryTab(state = state, strings = strings)
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
private fun PilotTrackRoomTab(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel,
    liveNowMs: Long,
    myPosition: Int,
    currentLapElapsedMs: Long,
    onEnableTransmitterMode: () -> Unit,
    onEnablePocketMode: () -> Unit,
    onEnableSoloAntennaMode: () -> Unit,
    onLogout: () -> Unit
) {
    val mx = LocalMxColors.current
    val room = state.roomState
    val ant1Online = (state.antennas[AntennaStation.ANTENA_1.id]?.lastHeartbeatMs ?: 0L) > liveNowMs - 15_000L
    val ant2Online = (state.antennas[AntennaStation.ANTENA_2.id]?.lastHeartbeatMs ?: 0L) > liveNowMs - 15_000L
    val rider = state.currentRider
    val roomRider = rider?.let { room.activeRiders[it.id] }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AnimatedCheckeredRibbon()
        // Antennas + Track Status + Flag + Game Mode
        GlassRaceCard {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "${strings.antennasOnline}: A1 ${if (ant1Online) "●" else "○"}  A2 ${if (ant2Online) "●" else "○"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (ant1Online || ant2Online) mx.telemetryGreen else mx.subtleText
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Flag,
                            contentDescription = null,
                            tint = if (room.flagState == FlagState.YELLOW) mx.goldBright else mx.telemetryGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(room.flagState.name, style = MaterialTheme.typography.labelMedium)
                    }
                }
                val modeName = when (room.gameMode) {
                    GameMode.NORMAL -> if (strings.isSpanish) "Carrera Normal" else "Normal Race"
                    GameMode.TIME_BOMB_45 -> if (strings.isSpanish) "Bomba Reloj (45s)" else "Time Bomb (45s)"
                    GameMode.ELIMINATION_60 -> if (strings.isSpanish) "Eliminación (60s)" else "Elimination (60s)"
                    GameMode.KING_OF_TRACK -> if (strings.isSpanish) "Rey de la Pista" else "King of Track"
                    GameMode.CONSISTENCY_DUEL -> if (strings.isSpanish) "Duelo Metrónomo (%)" else "Metronome Consistency (%)"
                    GameMode.TURBO_SPRINT_3 -> if (strings.isSpanish) "Turbo Sprint (3 Vueltas)" else "Turbo Sprint (3 Laps)"
                    GameMode.PURSUIT_HUNTER -> if (strings.isSpanish) "Caza al Líder" else "Pursuit Hunter"
                }
                Text(
                    text = "${room.sessionPhase.name} • $modeName",
                    style = MaterialTheme.typography.titleMedium,
                    color = mx.goldAccent
                )
                if (room.sessionPhase == SessionPhase.COUNTDOWN_30 && room.phaseEndTimeMs > liveNowMs) {
                    val rem = ((room.phaseEndTimeMs - liveNowMs) / 1000L).coerceAtLeast(0L)
                    Text(
                        text = "GATE DROP IN ${rem}s",
                        style = MaterialTheme.typography.displayMedium,
                        color = mx.racingRed
                    )
                } else if (room.sessionPhase == SessionPhase.WARMUP && room.phaseEndTimeMs > liveNowMs) {
                    val remSec = ((room.phaseEndTimeMs - liveNowMs) / 1000L).coerceAtLeast(0L)
                    val mins = remSec / 60L
                    val secs = remSec % 60L
                    Text(
                        text = if (strings.isSpanish) {
                            "WARM-UP CAPTURA: %02d:%02d".format(mins, secs)
                        } else {
                            "WARM-UP CAPTURE: %02d:%02d".format(mins, secs)
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        color = mx.smBadge
                    )
                } else if (room.sessionPhase == SessionPhase.RACING && room.phaseEndTimeMs > liveNowMs) {
                    val remSec = ((room.phaseEndTimeMs - liveNowMs) / 1000L).coerceAtLeast(0L)
                    val mins = remSec / 60L
                    val secs = remSec % 60L
                    Text(
                        text = if (strings.isSpanish) {
                            "TIEMPO RESTANTE: %02d:%02d".format(mins, secs)
                        } else {
                            "RACE REMAINING: %02d:%02d".format(mins, secs)
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        color = mx.telemetryGreen
                    )
                }
            }
        }

        SupercrossStartGateCard(
            sessionPhase = room.sessionPhase,
            phaseEndTimeMs = room.phaseEndTimeMs,
            phaseStartTimeMs = room.phaseStartTimeMs,
            nowMs = liveNowMs,
            isSpanish = strings.isSpanish
        )

        // Live Chronometer + Position + Dorsal + Manual Lap
        val myColorProfile = rider?.let { r -> state.colorProfiles.firstOrNull { it.riderId == r.id } }
        GlassRaceCard(accentBorder = mx.goldAccent.copy(alpha = 0.65f)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RiderDorsalBadge(
                            dorsal = roomRider?.dorsal ?: state.activeBike?.dorsal ?: "01",
                            discipline = roomRider?.discipline ?: state.activeCircuit.surface,
                            colorProfile = myColorProfile,
                            position = if (myPosition > 0) myPosition else null
                        )
                        Text("${strings.currentPos}: P${if (myPosition > 0) myPosition else "-"}", style = MaterialTheme.typography.titleLarge, color = mx.goldAccent)
                    }
                    Text("${strings.totalLaps}: ${roomRider?.lapsCount ?: 0}", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(10.dp))
                Text(strings.liveLap, style = MaterialTheme.typography.labelMedium, color = mx.subtleText)
                Text(
                    text = formatLapMs(currentLapElapsedMs),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${strings.bestLap}: ${formatLapMs(roomRider?.bestLapMs ?: 0L)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = mx.telemetryGreen
                )
                if (roomRider != null && roomRider.sessionStartMs > 0L) {
                    val lipoMin = ((liveNowMs - roomRider.sessionStartMs) / 60_000L).toInt()
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Icon(
                            Icons.Default.BatteryAlert,
                            contentDescription = null,
                            tint = if (lipoMin >= room.lipoWarnMinutes) mx.racingRed else mx.subtleText,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "LiPo: $lipoMin / ${room.lipoWarnMinutes} min",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (lipoMin >= room.lipoWarnMinutes) mx.racingRed else mx.subtleText
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Mx3dButton(
                    onClick = { viewModel.recordManualLapForCurrentRider() },
                    containerColor = mx.goldAccent,
                    contentColor = contrastTextOn(mx.goldAccent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pilot_manual_lap_button")
                ) {
                    Icon(Icons.Default.TouchApp, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(strings.manualLap, style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        // Transmitter Mode, Pocket Mode & Solo Phone-as-Antenna Mode
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Mx3dOutlinedButton(
                onClick = onEnableTransmitterMode,
                borderColor = mx.goldAccent,
                modifier = Modifier.weight(1f).testTag("pilot_transmitter_mode_button")
            ) {
                Icon(Icons.Default.Radio, contentDescription = null, modifier = Modifier.size(18.dp), tint = mx.goldAccent)
                Spacer(Modifier.width(6.dp))
                Text(strings.transmitterMode, maxLines = 1)
            }
            Mx3dOutlinedButton(
                onClick = onEnablePocketMode,
                borderColor = mx.smBadge,
                modifier = Modifier.weight(1f).testTag("pilot_pocket_mode_button")
            ) {
                Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(18.dp), tint = mx.smBadge)
                Spacer(Modifier.width(6.dp))
                Text(strings.pocketMode, maxLines = 1)
            }
        }

        Mx3dButton(
            onClick = onEnableSoloAntennaMode,
            containerColor = mx.smBadge,
            contentColor = contrastTextOn(mx.smBadge),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("pilot_solo_antenna_button")
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (strings.isSpanish) "Mi Móvil como Antena (Rodar Solo y Publicar Tiempos)"
                else "Use My Phone as Solo Antenna (Publish Live Laps)"
            )
        }

        // Friendly Mode (No Director)
        GlassRaceCard {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.friendlyModeTitle, style = MaterialTheme.typography.titleLarge, color = mx.smBadge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(GameMode.entries) { gm ->
                        FilterChip(
                            selected = room.gameMode == gm,
                            onClick = { viewModel.updateRoomSettings(gameMode = gm) },
                            label = { Text(gm.name.replace("_", " ")) },
                            colors = mxChipColors()
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Mx3dOutlinedButton(
                        onClick = { viewModel.runFriendly10sCalibration() },
                        borderColor = mx.telemetryGreen,
                        modifier = Modifier.weight(1f)
                    ) { Text(strings.calibrate10s, maxLines = 1) }
                    Mx3dOutlinedButton(
                        onClick = { viewModel.startFriendlyWarmup() },
                        borderColor = mx.goldAccent,
                        modifier = Modifier.weight(1f)
                    ) { Text(strings.startWarmup, maxLines = 1) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Mx3dOutlinedButton(
                        onClick = { viewModel.startFriendly30sGate() },
                        borderColor = mx.racingRed,
                        modifier = Modifier.weight(1f)
                    ) { Text(strings.startLight30s, maxLines = 1) }
                    Mx3dOutlinedButton(
                        onClick = { viewModel.startFriendlyRandomGate() },
                        borderColor = mx.smBadge,
                        modifier = Modifier.weight(1f)
                    ) { Text(strings.randomGate, maxLines = 1) }
                }
                if (room.sessionPhase == SessionPhase.WARMUP ||
                    room.sessionPhase == SessionPhase.RACING ||
                    room.sessionPhase == SessionPhase.COUNTDOWN_30
                ) {
                    Mx3dButton(
                        onClick = { viewModel.stopSession() },
                        containerColor = mx.racingRed,
                        contentColor = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("friendly_stop_session_button")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (room.sessionPhase == SessionPhase.WARMUP) {
                                "STOP WARM-UP"
                            } else {
                                if (strings.isSpanish) "STOP CARRERA" else "STOP RACE"
                            }
                        )
                    }
                }
                Mx3dButton(
                    onClick = { viewModel.spinCrazyMxRoulette() },
                    containerColor = mx.racingRed,
                    contentColor = Color.White,
                    modifier = Modifier.fillMaxWidth().testTag("crazy_roulette_button")
                ) {
                    Icon(Icons.Default.Casino, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(strings.crazyRoulette)
                }
                if (state.activeRouletteChallenge != null) {
                    Text(
                        text = state.activeRouletteChallenge,
                        style = MaterialTheme.typography.titleMedium,
                        color = mx.goldBright
                    )
                }
            }
        }

        Mx3dOutlinedButton(
            onClick = onLogout,
            borderColor = mx.glassBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(18.dp), tint = mx.racingRed)
            Spacer(Modifier.width(6.dp))
            Text(if (strings.isSpanish) "Cerrar Sesión de Piloto" else "Sign Out Rider")
        }
    }
}
