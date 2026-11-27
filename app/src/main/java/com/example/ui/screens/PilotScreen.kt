package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material.icons.filled.SportsMotorsports
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
import com.example.ui.components.PilotStartGateIlluminationOverlay
import com.example.ui.components.PocketModeOverlay
import com.example.ui.components.RiderDorsalBadge
import com.example.ui.components.SupercrossStartGateCard
import com.example.ui.theme.LocalMxColors
import com.example.ui.theme.contrastTextOn
import com.example.ui.theme.mxChipColors
import com.example.ui.viewmodel.MxProViewModel
import com.example.ui.viewmodel.MxUiState
import kotlin.math.abs
import kotlinx.coroutines.delay

@Composable
fun PilotScreen(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel,
    onBack: () -> Unit
) {
    val mx = LocalMxColors.current
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var transmitterMode by rememberSaveable { mutableStateOf(false) }
    var pocketMode by rememberSaveable { mutableStateOf(false) }
    var soloAntennaMode by rememberSaveable { mutableStateOf(false) }
    var dismissedGreenForStartMs by remember { mutableLongStateOf(0L) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

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

    // OLED Black Pocket Mode (continuous background audio + speaker/headset routing)
    if (pocketMode) {
        PocketModeOverlay(
            titleLocked = strings.pocketModeLocked,
            isSpanish = strings.isSpanish,
            audioRoute = state.audioOutputRoute,
            onSelectAudioRoute = { viewModel.setAudioOutputRoute(it) },
            onUnlock = {
                pocketMode = false
                viewModel.setPocketModeAudioActive(false)
            },
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    val lastHotlapAtMs = if (state.roomState.lastPaAnnouncement?.isHotlap == true) {
        state.roomState.lastPaAnnouncement.timestampMs
    } else 0L

    // High-contrast Radio Transmitter Mode ("Modo Emisora")
    if (transmitterMode) {
        BackHandler { transmitterMode = false }
        Box(modifier = Modifier.fillMaxSize()) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Mx3dButton(
                        enabled = state.activeBike != null,
                        onClick = { viewModel.recordManualLapForCurrentRider() },
                        containerColor = mx.goldAccent,
                        contentColor = contrastTextOn(mx.goldAccent),
                        modifier = Modifier.weight(1f).height(72.dp).testTag("transmitter_manual_lap")
                    ) {
                        val label = if (currentLapElapsedMs <= 0L) {
                            if (strings.isSpanish) "INICIAR" else "START"
                        } else {
                            strings.manualLap.uppercase()
                        }
                        Text(label, style = MaterialTheme.typography.titleLarge)
                    }
                    Mx3dButton(
                        enabled = currentLapElapsedMs > 0L,
                        onClick = { viewModel.stopManualChronoForCurrentRider() },
                        containerColor = mx.racingRed,
                        contentColor = Color.White,
                        modifier = Modifier.height(72.dp).testTag("transmitter_stop_chrono")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (strings.isSpanish) "PARAR" else "STOP", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            FlagAndRecordFrameOverlay(
                flagState = state.roomState.flagState,
                lastHotlapAtMs = lastHotlapAtMs,
                nowMs = liveNowMs,
                sessionPhase = state.roomState.sessionPhase,
                phaseStartTimeMs = state.roomState.phaseStartTimeMs,
                isSpanish = strings.isSpanish
            )
            PilotStartGateIlluminationOverlay(
                sessionPhase = state.roomState.sessionPhase,
                phaseEndTimeMs = state.roomState.phaseEndTimeMs,
                phaseStartTimeMs = state.roomState.phaseStartTimeMs,
                nowMs = liveNowMs,
                isSpanish = strings.isSpanish,
                dismissedGreenForStartMs = dismissedGreenForStartMs,
                onDismissGreen = { dismissedGreenForStartMs = state.roomState.phaseStartTimeMs }
            )
        }
        return
    }

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
                        onEnablePocketMode = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            viewModel.setPocketModeAudioActive(true)
                            pocketMode = true
                        },
                        onEnableSoloAntennaMode = { soloAntennaMode = true },
                        onOpenGarage = { selectedTab = 1 },
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
                nowMs = liveNowMs,
                sessionPhase = state.roomState.sessionPhase,
                phaseStartTimeMs = state.roomState.phaseStartTimeMs,
                isSpanish = strings.isSpanish
            )
            PilotStartGateIlluminationOverlay(
                sessionPhase = state.roomState.sessionPhase,
                phaseEndTimeMs = state.roomState.phaseEndTimeMs,
                phaseStartTimeMs = state.roomState.phaseStartTimeMs,
                nowMs = liveNowMs,
                isSpanish = strings.isSpanish,
                dismissedGreenForStartMs = dismissedGreenForStartMs,
                onDismissGreen = { dismissedGreenForStartMs = state.roomState.phaseStartTimeMs }
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
    onOpenGarage: () -> Unit,
    onLogout: () -> Unit
) {
    val mx = LocalMxColors.current
    val room = state.roomState
    val nowMono = SystemClock.elapsedRealtime()
    val ant1Cfg = state.antennas[AntennaStation.ANTENA_1.id]
    val ant2Cfg = state.antennas[AntennaStation.ANTENA_2.id]
    val ant1Online = (ant1Cfg != null && abs(liveNowMs - ant1Cfg.lastHeartbeatMs) < 35_000L) ||
        ((nowMono - (state.antennaLastChangeMonoMs[AntennaStation.ANTENA_1.id] ?: 0L)) in 0L..25_000L)
    val ant2Online = (ant2Cfg != null && abs(liveNowMs - ant2Cfg.lastHeartbeatMs) < 35_000L) ||
        ((nowMono - (state.antennaLastChangeMonoMs[AntennaStation.ANTENA_2.id] ?: 0L)) in 0L..25_000L)
    val rider = state.currentRider
    val roomRider = rider?.let { room.activeRiders[it.id] }
    val hasActiveBike = state.activeBike != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AnimatedCheckeredRibbon()
        // Track Bike Selection (Mandatory before riding or launching heats)
        GlassRaceCard(
            accentBorder = if (hasActiveBike) mx.telemetryGreen.copy(alpha = 0.6f) else mx.racingRed
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.SportsMotorsports,
                            contentDescription = null,
                            tint = if (hasActiveBike) mx.telemetryGreen else mx.racingRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (strings.isSpanish) "Moto Seleccionada para Pista" else "Active Track Bike",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (hasActiveBike) mx.goldAccent else mx.racingRed
                        )
                    }
                    state.activeBike?.let { b ->
                        Text(
                            text = "${b.modelName} #${b.dorsal}",
                            style = MaterialTheme.typography.labelLarge,
                            color = mx.telemetryGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (state.myBikes.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.myBikes) { b ->
                            FilterChip(
                                selected = b.id == state.activeBike?.id,
                                onClick = { viewModel.selectActiveBike(b.id) },
                                label = { Text("${b.modelName} #${b.dorsal}") },
                                colors = mxChipColors(),
                                modifier = Modifier.testTag("pilot_track_bike_chip_${b.id}")
                            )
                        }
                    }
                }
                if (!hasActiveBike) {
                    Text(
                        text = if (strings.isSpanish) {
                            "⚠ Obligatorio: selecciona una moto de tu Garaje Pro arriba (o créala si aún no tienes) para entrar a pista, usar antena solo o dar salida."
                        } else {
                            "⚠ Required: select or create a bike in Pro Garage before entering track, timing, or starting a heat."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = mx.racingRed
                    )
                    Mx3dButton(
                        onClick = onOpenGarage,
                        containerColor = mx.goldAccent,
                        contentColor = contrastTextOn(mx.goldAccent),
                        modifier = Modifier.fillMaxWidth().testTag("pilot_goto_garage_button")
                    ) {
                        Text(if (strings.isSpanish) "Elegir o Crear Moto en Garaje Pro" else "Choose / Create Bike in Pro Garage")
                    }
                }
            }
        }
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
                if ((room.sessionPhase == SessionPhase.COUNTDOWN_30 ||
                        room.sessionPhase == SessionPhase.RANDOM_GATE) &&
                    room.phaseEndTimeMs > liveNowMs
                ) {
                    val nominalEndMs = if (room.sessionPhase == SessionPhase.RANDOM_GATE &&
                        room.phaseStartTimeMs > 0L
                    ) {
                        room.phaseStartTimeMs + 30_000L
                    } else {
                        room.phaseEndTimeMs
                    }
                    val rem = ((nominalEndMs - liveNowMs + 200L) / 1000L).coerceAtLeast(0L)
                    Text(
                        text = if (room.sessionPhase == SessionPhase.RANDOM_GATE && rem <= 2L) {
                            if (strings.isSpanish) "¡ATENTOS A LA VALLA!" else "WATCH THE GATE!"
                        } else {
                            if (strings.isSpanish) "SALIDA EN ${rem}s" else "GATE DROP IN ${rem}s"
                        },
                        style = MaterialTheme.typography.displayMedium,
                        color = mx.racingRed
                    )
                } else if (room.sessionPhase == SessionPhase.WARMUP && room.phaseEndTimeMs > liveNowMs) {
                    val remSec = ((room.phaseEndTimeMs - liveNowMs) / 1000L).coerceAtLeast(0L)
                    val mins = remSec / 60L
                    val secs = remSec % 60L
                    Text(
                        text = "WARM-UP: %02d:%02d".format(mins, secs),
                        style = MaterialTheme.typography.displayMedium,
                        color = mx.smBadge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (strings.isSpanish) {
                            "Pasad varias veces por meta para capturar vuestros colores"
                        } else {
                            "Pass finish line multiple times to capture colors"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = mx.goldBright
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
                if (room.sessionPhase != SessionPhase.IDLE && room.friendlyModeActive) {
                    Mx3dButton(
                        onClick = { viewModel.stopSession(isDirector = false) },
                        containerColor = mx.racingRed,
                        contentColor = Color.White,
                        modifier = Modifier.fillMaxWidth().testTag("pilot_track_stop_friendly_btn")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (room.sessionPhase == SessionPhase.WARMUP) {
                                if (strings.isSpanish) "FIN WARM-UP" else "END WARM-UP"
                            } else {
                                if (strings.isSpanish) "FIN MANGA" else "END HEAT"
                            }
                        )
                    }
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

        // Live Chronometer + Position + Dorsal + Manual Lap / STOP
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
                val chronoRunning = (roomRider?.lastPassTimestampMs ?: 0L) > 0L
                val isActivelyRiding = roomRider != null && roomRider.sessionStartMs > 0L && (
                    room.sessionPhase == SessionPhase.RACING ||
                        room.sessionPhase == SessionPhase.WARMUP ||
                        chronoRunning
                    )
                if (isActivelyRiding) {
                    val lipoMin = ((liveNowMs - roomRider!!.sessionStartMs) / 60_000L).toInt().coerceAtLeast(0)
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Mx3dButton(
                        enabled = hasActiveBike,
                        onClick = { viewModel.recordManualLapForCurrentRider() },
                        containerColor = mx.goldAccent,
                        contentColor = contrastTextOn(mx.goldAccent),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pilot_manual_lap_button")
                    ) {
                        Icon(
                            if (chronoRunning) Icons.Default.TouchApp else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (chronoRunning) {
                                strings.manualLap
                            } else {
                                if (strings.isSpanish) "Iniciar Crono" else "Start Timer"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                    }
                    Mx3dButton(
                        enabled = chronoRunning,
                        onClick = { viewModel.stopManualChronoForCurrentRider() },
                        containerColor = mx.racingRed,
                        contentColor = Color.White,
                        modifier = Modifier.testTag("pilot_manual_stop_button")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (strings.isSpanish) "Parar Crono" else "Stop Timer",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                    }
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
            enabled = hasActiveBike,
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

        // Group Heat Mode without Director ("Manga Libre entre Pilotos")
        val lockedByActiveSession = room.sessionPhase != SessionPhase.IDLE
        GlassRaceCard(accentBorder = if (lockedByActiveSession) mx.racingRed.copy(alpha = 0.65f) else mx.smBadge.copy(alpha = 0.55f)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (lockedByActiveSession) Icons.Default.Lock else Icons.Default.Group, contentDescription = null, tint = if (lockedByActiveSession) mx.racingRed else mx.smBadge)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (strings.isSpanish) "Manga entre Pilotos (Sin Director)" else "Group Heat (No Director)",
                            style = MaterialTheme.typography.titleLarge,
                            color = if (lockedByActiveSession) mx.racingRed else mx.smBadge
                        )
                    }
                    Text(
                        "${room.activeRiders.size} ${if (strings.isSpanish) "en pista" else "on track"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = mx.telemetryGreen
                    )
                }
                if (lockedByActiveSession) {
                    Text(
                        text = if (!room.friendlyModeActive) {
                            if (strings.isSpanish) "Sesión controlada por Dirección (${room.sessionPhase.name}) — organización de manga bloqueada hasta finalizar o STOP de Dirección."
                            else "Session controlled by Director (${room.sessionPhase.name}) — group heat setup locked until Director stops."
                        } else {
                            if (strings.isSpanish) "Sesión en curso (${room.sessionPhase.name}) — pulsa FIN MANGA arriba antes de organizar otra."
                            else "Session active (${room.sessionPhase.name}) — tap END HEAT above before starting another."
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = mx.racingRed,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        if (strings.isSpanish) "Los pilotos del circuito os unís aquí y lanzáis la manga juntos:"
                        else "Riders in this circuit join together and start a heat without a Director:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = mx.subtleText
                    )
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(GameMode.entries) { gm ->
                        FilterChip(
                            selected = room.gameMode == gm,
                            enabled = !lockedByActiveSession,
                            onClick = { viewModel.updateRoomSettings(gameMode = gm) },
                            label = { Text(gm.name.replace("_", " ")) },
                            colors = mxChipColors()
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(180, 300, 480).forEach { sec ->
                        FilterChip(
                            selected = room.heatDurationSec == sec,
                            enabled = !lockedByActiveSession,
                            onClick = { viewModel.updateRoomSettings(heatDurationSec = sec) },
                            label = { Text("${sec / 60}m") },
                            colors = mxChipColors()
                        )
                    }
                }
                val latestSample = state.antennas[AntennaStation.ANTENA_1.id]?.lastDetectedLab?.firstOrNull()
                    ?: state.capturedPasses.firstOrNull()?.samples?.firstOrNull()
                if (rider != null && latestSample != null) {
                    Mx3dOutlinedButton(
                        onClick = { viewModel.assignSingleColorToRider(rider.id, rider.name, latestSample) },
                        borderColor = mx.goldAccent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (strings.isSpanish) "Asignarme último color (${latestSample.colorName})"
                            else "Assign latest color (${latestSample.colorName})"
                        )
                    }
                }
                val canLaunchFriendly = !lockedByActiveSession && hasActiveBike
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Mx3dOutlinedButton(
                        enabled = canLaunchFriendly,
                        onClick = { viewModel.runFriendly10sCalibration() },
                        borderColor = mx.telemetryGreen,
                        modifier = Modifier.weight(1f)
                    ) { Text(strings.calibrate10s, maxLines = 1) }
                    Mx3dOutlinedButton(
                        enabled = canLaunchFriendly,
                        onClick = { viewModel.startFriendlyWarmup() },
                        borderColor = mx.goldAccent,
                        modifier = Modifier.weight(1f)
                    ) { Text(strings.startWarmup, maxLines = 1) }
                }
                Mx3dOutlinedButton(
                    enabled = canLaunchFriendly,
                    onClick = { viewModel.startFriendlyRandomGate() },
                    borderColor = mx.racingRed,
                    modifier = Modifier.fillMaxWidth().testTag("friendly_random_gate_button")
                ) {
                    Text(
                        if (strings.isSpanish) "Valla MX Aleatoria (30s → Caída sorpresa)"
                        else "Random MX Gate (30s → Surprise Drop)",
                        maxLines = 1
                    )
                }
                Mx3dButton(
                    enabled = canLaunchFriendly,
                    onClick = { viewModel.startFriendlyDirectHeat(room.heatDurationSec) },
                    containerColor = mx.telemetryGreen,
                    contentColor = contrastTextOn(mx.telemetryGreen),
                    modifier = Modifier.fillMaxWidth().testTag("friendly_start_direct_heat_btn")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (strings.isSpanish) "Lanzar Manga Ya (${room.heatDurationSec / 60} min)"
                        else "Start Group Heat Now (${room.heatDurationSec / 60} min)"
                    )
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
