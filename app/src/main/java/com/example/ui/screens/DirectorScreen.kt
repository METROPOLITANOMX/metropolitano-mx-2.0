package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.SportsMotorsports
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.data.model.PassingDirection
import com.example.data.model.RoiHeight
import com.example.data.model.RoiSize
import com.example.data.model.SessionPhase
import com.example.data.model.formatLapMs
import com.example.data.model.predefinedMotoColors
import com.example.i18n.MxStrings
import com.example.ui.components.AnimatedCheckeredRibbon
import com.example.ui.components.Base64ImageView
import com.example.ui.components.CarbonStadiumBackdrop
import com.example.ui.components.FlagAndRecordFrameOverlay
import com.example.ui.components.GlassRaceCard
import com.example.ui.components.Mx3dButton
import com.example.ui.components.Mx3dOutlinedButton
import com.example.ui.components.MxTopHeader
import com.example.ui.components.PocketModeOverlay
import com.example.ui.components.PulsingLiveDot
import com.example.ui.components.RiderDorsalBadge
import com.example.ui.components.SupercrossStartGateCard
import com.example.ui.theme.LocalMxColors
import com.example.ui.theme.contrastTextOn
import com.example.ui.theme.mxChipColors
import com.example.ui.theme.mxTextFieldColors
import com.example.ui.viewmodel.MxProViewModel
import com.example.ui.viewmodel.MxUiState
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun DirectorScreen(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel,
    onBack: () -> Unit,
    onNavigatePilot: () -> Unit = {}
) {
    val mx = LocalMxColors.current
    val context = LocalContext.current
    val showClubAdminTab = !state.installCodeUnlocked
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var isTalkingPa by remember { mutableStateOf(false) }
    var pocketMode by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.installCodeUnlocked) {
        if (state.installCodeUnlocked && selectedTab >= 4) {
            selectedTab = 0
        }
    }

    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    var liveNowMs by remember { mutableLongStateOf(viewModel.syncedNowMs()) }
    DisposableEffect(Unit) {
        viewModel.startDirectorLanBridge()
        onDispose { viewModel.stopDirectorLanBridge() }
    }
    LaunchedEffect(Unit) {
        viewModel.refreshDirectorDashboard()
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

    // Fullscreen 30s Starting Lights overlay when active (COUNTDOWN_30 or RANDOM_GATE)
    if ((state.roomState.sessionPhase == SessionPhase.COUNTDOWN_30 ||
            state.roomState.sessionPhase == SessionPhase.RANDOM_GATE) &&
        state.roomState.phaseEndTimeMs > liveNowMs
    ) {
        val nominalEndMs = if (state.roomState.sessionPhase == SessionPhase.RANDOM_GATE &&
            state.roomState.phaseStartTimeMs > 0L
        ) {
            state.roomState.phaseStartTimeMs + 30_000L
        } else {
            state.roomState.phaseEndTimeMs
        }
        val remSec = ((nominalEndMs - liveNowMs + 200L) / 1000L).toInt().coerceAtLeast(0)
        val isRandomHold = state.roomState.sessionPhase == SessionPhase.RANDOM_GATE && remSec <= 2
        val isAmberStage = remSec > 20
        val litCount = when {
            remSec <= 2 -> 5
            remSec <= 5 -> 4
            remSec <= 10 -> 3
            remSec <= 15 -> 2
            remSec <= 20 -> 1
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
                        val ledColor = when {
                            isAmberStage -> Color(0xFFF59E0B)
                            i <= litCount -> Color(0xFFE63946)
                            else -> Color(0xFF27272A)
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(ledColor)
                                .border(2.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                        )
                    }
                }
                Text(
                    text = if (isRandomHold) {
                        if (strings.isSpanish) "¡ATENTOS!" else "WATCH!"
                    } else {
                        "${remSec}s"
                    },
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = if (isRandomHold) 56.sp else 84.sp),
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
                Mx3dButton(
                    onClick = { viewModel.stopSession(isDirector = true) },
                    containerColor = mx.racingRed,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (strings.isSpanish) "STOP SEMÁFORO" else "STOP START GATE")
                }
            }
        }
        return
    }

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

    var selectedAntennaId by rememberSaveable { mutableStateOf(AntennaStation.ANTENA_1.id) }

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

                // Live PA Walkie-Talkie Hold-to-Talk Bar + Pocket Mode Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
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
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isTalkingPa) "ON AIR..." else if (strings.isSpanish) "MANTENER PA" else "HOLD PA MIC",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isTalkingPa) Color.White else MaterialTheme.colorScheme.onBackground,
                                maxLines = 1
                            )
                        }
                    }

                    Mx3dOutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            viewModel.setPocketModeAudioActive(true)
                            pocketMode = true
                        },
                        borderColor = mx.smBadge,
                        modifier = Modifier.testTag("director_pocket_mode_button")
                    ) {
                        Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(16.dp), tint = mx.smBadge)
                        Spacer(Modifier.width(4.dp))
                        Text(strings.pocketMode, maxLines = 1)
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

                // Persistent Active Session Bar (Countdown + STOP visible on ALL 5 tabs)
                val activeRoom = state.roomState
                if (activeRoom.sessionPhase != SessionPhase.IDLE) {
                    val remSec = if (activeRoom.phaseEndTimeMs > liveNowMs) {
                        ((activeRoom.phaseEndTimeMs - liveNowMs) / 1000L).toInt().coerceAtLeast(0)
                    } else 0
                    val phaseTag = when (activeRoom.sessionPhase) {
                        SessionPhase.WARMUP -> "WARM-UP"
                        SessionPhase.RACING -> if (strings.isSpanish) "CARRERA" else "RACING"
                        SessionPhase.RANDOM_GATE -> if (strings.isSpanish) "VALLA MX" else "MX GATE"
                        SessionPhase.PAUSED_YELLOW -> if (strings.isSpanish) "B. AMARILLA" else "YELLOW FLAG"
                        else -> activeRoom.sessionPhase.name
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(mx.racingRed.copy(alpha = 0.18f))
                            .border(1.dp, mx.racingRed, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            PulsingLiveDot()
                            Spacer(Modifier.width(8.dp))
                            val clockStr = if (activeRoom.phaseEndTimeMs > 0L) {
                                " • %d:%02d".format(remSec / 60, remSec % 60)
                            } else ""
                            Text(
                                text = "$phaseTag$clockStr",
                                style = MaterialTheme.typography.titleSmall,
                                color = mx.goldBright,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Mx3dButton(
                            onClick = { viewModel.stopSession(isDirector = true) },
                            containerColor = mx.racingRed,
                            contentColor = Color.White,
                            modifier = Modifier.testTag("director_top_stop_btn")
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("STOP", style = MaterialTheme.typography.labelLarge, maxLines = 1)
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

                                val safeTab = selectedTab.coerceAtMost(if (showClubAdminTab) 4 else 3)
                                ScrollableTabRow(
                                    selectedTabIndex = safeTab,
                                    edgePadding = 4.dp,
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    contentColor = mx.goldAccent
                                ) {
                                    Tab(selected = safeTab == 0, onClick = { selectedTab = 0 }, selectedContentColor = mx.goldAccent, unselectedContentColor = MaterialTheme.colorScheme.onSurface, text = { Text(strings.roomAndHeats) })
                                    Tab(selected = safeTab == 1, onClick = { selectedTab = 1 }, selectedContentColor = mx.goldAccent, unselectedContentColor = MaterialTheme.colorScheme.onSurface, text = { Text(strings.antennaMonitor) })
                                    Tab(selected = safeTab == 2, onClick = { selectedTab = 2 }, selectedContentColor = mx.goldAccent, unselectedContentColor = MaterialTheme.colorScheme.onSurface, text = { Text(strings.colorWarmup) })
                                    Tab(selected = safeTab == 3, onClick = { selectedTab = 3 }, selectedContentColor = mx.goldAccent, unselectedContentColor = MaterialTheme.colorScheme.onSurface, text = { Text(strings.setupWizard) })
                                    if (showClubAdminTab) {
                                        Tab(selected = safeTab == 4, onClick = { selectedTab = 4 }, selectedContentColor = mx.goldAccent, unselectedContentColor = MaterialTheme.colorScheme.onSurface, text = { Text(strings.clubAdmin) })
                                    }
                                }
                                when (safeTab) {
                                    0 -> DirectorRoomControlTab(state, strings, viewModel, liveNowMs, showInlineCamera = false, selectedAntennaId = selectedAntennaId, onSelectAntenna = { selectedAntennaId = it }, onNavigatePilot = onNavigatePilot)
                                    1 -> DirectorAntennaMonitorTab(state, strings, viewModel, liveNowMs, selectedAntennaId) { selectedAntennaId = it }
                                    2 -> DirectorColorWarmupTab(state, strings, viewModel, liveNowMs)
                                    3 -> DirectorSetupWizardTab(strings)
                                    else -> if (showClubAdminTab) {
                                        DirectorClubAdminTab(state, strings, viewModel)
                                    } else {
                                        DirectorRoomControlTab(state, strings, viewModel, liveNowMs, showInlineCamera = false, selectedAntennaId = selectedAntennaId, onSelectAntenna = { selectedAntennaId = it }, onNavigatePilot = onNavigatePilot)
                                    }
                                }
                            }
                        }
                    } else {
                        val safeTab = selectedTab.coerceAtMost(if (showClubAdminTab) 4 else 3)
                        Column(modifier = Modifier.fillMaxSize()) {
                            ScrollableTabRow(
                                selectedTabIndex = safeTab,
                                edgePadding = 8.dp,
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = mx.goldAccent
                            ) {
                                Tab(selected = safeTab == 0, onClick = { selectedTab = 0 }, selectedContentColor = mx.goldAccent, unselectedContentColor = MaterialTheme.colorScheme.onSurface, text = { Text(strings.roomAndHeats) })
                                Tab(selected = safeTab == 1, onClick = { selectedTab = 1 }, selectedContentColor = mx.goldAccent, unselectedContentColor = MaterialTheme.colorScheme.onSurface, text = { Text(strings.antennaMonitor) })
                                Tab(selected = safeTab == 2, onClick = { selectedTab = 2 }, selectedContentColor = mx.goldAccent, unselectedContentColor = MaterialTheme.colorScheme.onSurface, text = { Text(strings.colorWarmup) })
                                Tab(selected = safeTab == 3, onClick = { selectedTab = 3 }, selectedContentColor = mx.goldAccent, unselectedContentColor = MaterialTheme.colorScheme.onSurface, text = { Text(strings.setupWizard) })
                                if (showClubAdminTab) {
                                    Tab(selected = safeTab == 4, onClick = { selectedTab = 4 }, selectedContentColor = mx.goldAccent, unselectedContentColor = MaterialTheme.colorScheme.onSurface, text = { Text(strings.clubAdmin) })
                                }
                            }
                            when (safeTab) {
                                0 -> DirectorRoomControlTab(state, strings, viewModel, liveNowMs, showInlineCamera = true, selectedAntennaId = selectedAntennaId, onSelectAntenna = { selectedAntennaId = it }, onNavigatePilot = onNavigatePilot)
                                1 -> DirectorAntennaMonitorTab(state, strings, viewModel, liveNowMs, selectedAntennaId) { selectedAntennaId = it }
                                2 -> DirectorColorWarmupTab(state, strings, viewModel, liveNowMs)
                                3 -> DirectorSetupWizardTab(strings)
                                else -> if (showClubAdminTab) {
                                    DirectorClubAdminTab(state, strings, viewModel)
                                } else {
                                    DirectorRoomControlTab(state, strings, viewModel, liveNowMs, showInlineCamera = true, selectedAntennaId = selectedAntennaId, onSelectAntenna = { selectedAntennaId = it }, onNavigatePilot = onNavigatePilot)
                                }
                            }
                        }
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
        }
    }
}

@Composable
private fun DirectorRoomControlTab(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel,
    liveNowMs: Long,
    showInlineCamera: Boolean,
    selectedAntennaId: String,
    onSelectAntenna: (String) -> Unit,
    onNavigatePilot: () -> Unit
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
        if (showInlineCamera) {
            DirectorAntennaCameraPreviewCard(
                state = state,
                strings = strings,
                viewModel = viewModel,
                selectedAntennaId = selectedAntennaId,
                onSelectAntenna = onSelectAntenna,
                liveNowMs = liveNowMs
            )
            AnimatedCheckeredRibbon()
        }

        // Director also participates as Rider ("Director también es Piloto")
        GlassRaceCard(accentBorder = mx.goldAccent.copy(alpha = 0.55f)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SportsMotorsports, contentDescription = null, tint = mx.goldAccent)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (strings.isSpanish) "Director en Pista (También soy Piloto)" else "Director Also Racing (Rider Mode)",
                        style = MaterialTheme.typography.titleMedium,
                        color = mx.goldAccent
                    )
                }
                if (state.riders.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.riders.take(12)) { r ->
                            val selected = state.currentRider?.id == r.id
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.directorJoinRoomAsPilot(r) },
                                label = { Text(r.name) },
                                colors = mxChipColors()
                            )
                        }
                    }
                }
                val myDirRoomRider = state.currentRider?.let { room.activeRiders[it.id] }
                val myDirChronoRunning = (myDirRoomRider?.lastPassTimestampMs ?: 0L) > 0L
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Mx3dOutlinedButton(
                        enabled = state.currentRider != null,
                        onClick = { viewModel.recordManualLapForCurrentRider() },
                        borderColor = mx.goldAccent,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(16.dp), tint = mx.goldAccent)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (myDirChronoRunning) {
                                state.currentRider?.let { "${strings.manualLap} (${it.name})" } ?: strings.manualLap
                            } else {
                                if (strings.isSpanish) "Iniciar Crono" else "Start Timer"
                            },
                            maxLines = 1
                        )
                    }
                    Mx3dButton(
                        enabled = myDirChronoRunning,
                        onClick = { viewModel.stopManualChronoForCurrentRider() },
                        containerColor = mx.racingRed,
                        contentColor = Color.White
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("STOP", maxLines = 1)
                    }
                    Mx3dButton(
                        enabled = state.currentRider != null,
                        onClick = {
                            viewModel.enterTrackAsLoggedRider()
                            onNavigatePilot()
                        },
                        containerColor = mx.smBadge,
                        contentColor = contrastTextOn(mx.smBadge),
                        modifier = Modifier.weight(1f).testTag("director_jump_pilot_btn")
                    ) {
                        Text(if (strings.isSpanish) "Ir a Piloto" else "Rider Room", maxLines = 1)
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
                    Mx3dButton(
                        onClick = { viewModel.stopSession(isDirector = true) },
                        containerColor = mx.racingRed,
                        contentColor = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
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

        val sessionBusy = room.sessionPhase != SessionPhase.IDLE
        GlassRaceCard {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (strings.isSpanish) "Modo de Juego" else "Game Mode", style = MaterialTheme.typography.titleMedium, color = mx.goldAccent)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(GameMode.entries) { gm ->
                        FilterChip(
                            selected = room.gameMode == gm,
                            enabled = !sessionBusy,
                            onClick = { viewModel.updateRoomSettings(gameMode = gm) },
                            label = { Text(gm.name.replace("_", " ")) },
                            colors = mxChipColors()
                        )
                    }
                }

                Text(if (strings.isSpanish) "Duración de Manga" else "Heat Duration", style = MaterialTheme.typography.titleMedium, color = mx.goldAccent)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(180, 300, 480, 600).forEach { sec ->
                        FilterChip(
                            selected = room.heatDurationSec == sec,
                            enabled = !sessionBusy,
                            onClick = { viewModel.updateRoomSettings(heatDurationSec = sec) },
                            label = { Text("${sec / 60}m") },
                            colors = mxChipColors()
                        )
                    }
                }

                Text(if (strings.isSpanish) "Sensibilidad Anti-Vibración" else "Anti-Vibration Sensitivity", style = MaterialTheme.typography.titleMedium, color = mx.goldAccent)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AntiVibrationLevel.entries.forEach { lvl ->
                        FilterChip(
                            selected = room.antiVibration == lvl,
                            onClick = { viewModel.updateRoomSettings(antiVibration = lvl) },
                            label = { Text(lvl.name) },
                            colors = mxChipColors()
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Mx3dButton(
                enabled = !sessionBusy,
                onClick = { viewModel.startWarmup(friendly = false) },
                containerColor = mx.goldAccent,
                contentColor = contrastTextOn(mx.goldAccent),
                modifier = Modifier.weight(1f).testTag("director_warmup_btn")
            ) { Text(strings.startWarmup, maxLines = 1) }
            Mx3dButton(
                enabled = !sessionBusy,
                onClick = { viewModel.start30sCountdown(friendly = false) },
                containerColor = mx.racingRed,
                contentColor = Color.White,
                modifier = Modifier.weight(1f).testTag("director_30s_gate_btn")
            ) { Text(strings.startLight30s, maxLines = 1) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Mx3dOutlinedButton(
                enabled = !sessionBusy,
                onClick = { viewModel.startRandomMxGate(friendly = false) },
                borderColor = mx.smBadge,
                modifier = Modifier.weight(1f)
            ) { Text(strings.randomGate, maxLines = 1) }
            Mx3dOutlinedButton(
                onClick = {
                    val next = if (room.flagState == FlagState.GREEN) FlagState.YELLOW else FlagState.GREEN
                    viewModel.updateRoomSettings(flagState = next)
                },
                borderColor = if (room.flagState == FlagState.YELLOW) mx.telemetryGreen else mx.goldBright,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Flag, contentDescription = null, tint = if (room.flagState == FlagState.YELLOW) mx.goldBright else mx.telemetryGreen)
                Spacer(Modifier.width(4.dp))
                Text(if (room.flagState == FlagState.YELLOW) "Green Flag" else "Yellow Flag", maxLines = 1)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Mx3dOutlinedButton(
                onClick = { viewModel.lockActiveCircuitGpsHere() },
                borderColor = mx.goldAccent,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.GpsFixed, contentDescription = null, modifier = Modifier.size(16.dp), tint = mx.goldAccent)
                Spacer(Modifier.width(4.dp))
                Text(if (strings.isSpanish) "Fijar GPS Circuito" else "Lock Track GPS", maxLines = 1)
            }
            Mx3dOutlinedButton(
                onClick = { viewModel.resetHeat() },
                borderColor = mx.racingRed,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = mx.racingRed)
                Spacer(Modifier.width(4.dp))
                Text(if (strings.isSpanish) "Reiniciar Manga" else "Reset Heat", maxLines = 1)
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
                                label = { Text("${ch.name} (${ch.completedHeats}/${ch.totalHeats})") },
                                colors = mxChipColors()
                            )
                        }
                    }
                }
                if (activeChamp != null) {
                    Mx3dButton(
                        onClick = { viewModel.awardChampionshipHeatPoints() },
                        containerColor = mx.telemetryGreen,
                        contentColor = contrastTextOn(mx.telemetryGreen),
                        modifier = Modifier.fillMaxWidth().testTag("director_award_champ_points")
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
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newChampName,
                        onValueChange = { newChampName = it },
                        label = { Text(if (strings.isSpanish) "Nuevo Campeonato" else "New Championship") },
                        singleLine = true,
                        colors = mxTextFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = true,
                        onClick = { newChampHeats = if (newChampHeats >= 6) 2 else newChampHeats + 1 },
                        label = { Text("${newChampHeats}M") },
                        colors = mxChipColors()
                    )
                }
                Mx3dOutlinedButton(
                    onClick = {
                        viewModel.createChampionship(newChampName, newChampHeats)
                        newChampName = ""
                    },
                    modifier = Modifier.fillMaxWidth()
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
    val nowMono = SystemClock.elapsedRealtime()
    fun checkLive(antId: String): Boolean {
        val c = state.antennas[antId]
        val hbOk = c != null && c.lastHeartbeatMs > 0L && abs(liveNowMs - c.lastHeartbeatMs) < 35_000L
        val monoOk = (nowMono - (state.antennaLastChangeMonoMs[antId] ?: 0L)) in 0L..25_000L
        return hbOk || monoOk
    }
    val ant1Online = checkLive(AntennaStation.ANTENA_1.id)
    val ant2Online = checkLive(AntennaStation.ANTENA_2.id)
    val tempOnline = checkLive(AntennaStation.ANTENA_TEMP.id)
    val selectedOnline = checkLive(selectedAntennaId)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val rtdbErr = state.lastRtdbError ?: state.antennaLastSyncError
        if (rtdbErr?.contains("401") == true) {
            GlassRaceCard(accentBorder = mx.racingRed) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (strings.isSpanish) "⚠ Firebase RTDB bloqueado (HTTP 401)" else "⚠ Firebase RTDB blocked (HTTP 401)",
                        style = MaterialTheme.typography.titleSmall,
                        color = mx.racingRed,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (strings.isSpanish) {
                            "Las reglas de Firebase RTDB caducaron o exigen auth. Activa '.read': true / '.write': true en Firebase Console -> Realtime Database -> Reglas, o conecta Antena y Director en la misma red Wi-Fi / Hotspot."
                        } else {
                            "Firebase RTDB rules expired or blocked access. Enable '.read': true / '.write': true in Firebase Console -> Realtime Database -> Rules, or keep Antenna & Director on the same Wi-Fi/Hotspot."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = mx.subtleText
                    )
                }
            }
        }
        state.otherCircuitOnlineAntenna?.let { (otherCircId, otherAntId) ->
            val targetCirc = state.circuits.firstOrNull { it.id == otherCircId }
            GlassRaceCard(accentBorder = mx.goldAccent) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (strings.isSpanish) {
                            "Antena '$otherAntId' detectada en circuito: ${targetCirc?.name ?: otherCircId}"
                        } else {
                            "Antenna '$otherAntId' active on circuit: ${targetCirc?.name ?: otherCircId}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = mx.goldBright,
                        modifier = Modifier.weight(1f)
                    )
                    if (targetCirc != null) {
                        Spacer(Modifier.width(8.dp))
                        Mx3dButton(
                            onClick = { viewModel.selectCircuit(targetCirc) },
                            containerColor = mx.goldAccent,
                            contentColor = contrastTextOn(mx.goldAccent)
                        ) {
                            Text(if (strings.isSpanish) "Cambiar" else "Switch", maxLines = 1)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${if (strings.isSpanish) "Circuito" else "Circuit"}: ${state.activeCircuit.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = mx.subtleText
                )
                state.directorLanEndpoint?.let { ep ->
                    Text(
                        text = if (strings.isSpanish) "Wi-Fi / Hotspot Directo activo: $ep" else "Direct Wi-Fi / Hotspot bridge: $ep",
                        style = MaterialTheme.typography.labelSmall,
                        color = mx.telemetryGreen
                    )
                }
            }
            IconButton(onClick = { viewModel.refreshAntennasAndCaptures() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = mx.goldAccent)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedAntennaId == AntennaStation.ANTENA_1.id,
                onClick = { onSelectAntenna(AntennaStation.ANTENA_1.id) },
                label = { Text("A1 ${if (ant1Online) "●" else "○"}") },
                colors = mxChipColors()
            )
            FilterChip(
                selected = selectedAntennaId == AntennaStation.ANTENA_2.id,
                onClick = { onSelectAntenna(AntennaStation.ANTENA_2.id) },
                label = { Text("A2 ${if (ant2Online) "●" else "○"}") },
                colors = mxChipColors()
            )
            FilterChip(
                selected = selectedAntennaId == AntennaStation.ANTENA_TEMP.id,
                onClick = { onSelectAntenna(AntennaStation.ANTENA_TEMP.id) },
                label = { Text("Temp ${if (tempOnline) "●" else "○"}") },
                colors = mxChipColors()
            )
        }

        GlassRaceCard {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${cfg.antennaId.uppercase()} • ${cfg.activeFps} FPS" +
                            (if (cfg.lastDetectedDorsal.isNotBlank()) " • #${cfg.lastDetectedDorsal}" else "") +
                            (if (cfg.lastConfidence > 0f) " (${(cfg.lastConfidence * 100).toInt()}%)" else ""),
                        style = MaterialTheme.typography.titleMedium,
                        color = mx.goldAccent
                    )
                    val secAgo = if (cfg.lastHeartbeatMs > 0L) ((liveNowMs - cfg.lastHeartbeatMs) / 1000L).coerceAtLeast(0L) else -1L
                    val calText = if (cfg.isCalibrated) (if (strings.isSpanish) "CALIBRADA" else "CALIBRATED") else (if (strings.isSpanish) "SIN CALIBRAR" else "UNCALIBRATED")
                    Text(
                        text = if (selectedOnline) {
                            if (strings.isSpanish) "CONECTADA (${secAgo}s) • $calText" else "ONLINE (${secAgo}s) • $calText"
                        } else {
                            if (strings.isSpanish) "SIN SEÑAL" else "OFFLINE"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selectedOnline) (if (cfg.isCalibrated) mx.telemetryGreen else mx.goldBright) else mx.racingRed,
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
                            // Full-Screen Finish Line edge-to-edge
                            if (cfg.lineOrientation == LineOrientation.VERTICAL) {
                                val lx = (size.width * cfg.linePosition).coerceIn(4f, size.width - 4f)
                                drawLine(Color(0xFFE63946), Offset(lx, 0f), Offset(lx, size.height), strokeWidth = 2.5.dp.toPx())
                            } else {
                                val ly = (size.height * cfg.linePosition).coerceIn(4f, size.height - 4f)
                                drawLine(Color(0xFFE63946), Offset(0f, ly), Offset(size.width, ly), strokeWidth = 2.5.dp.toPx())
                            }
                            // Independent Color Capture Zone ("Zona Color")
                            val zoneW = size.width * (cfg.roiSize.widthFrac * 0.55f).coerceIn(0.18f, 0.48f)
                            val zoneH = size.height * (cfg.roiSize.heightFrac * 0.55f).coerceIn(0.18f, 0.45f)
                            val zoneLeft = (size.width * cfg.colorZoneX - zoneW / 2f).coerceIn(4f, size.width - zoneW - 4f)
                            val zoneTop = (size.height * cfg.colorZoneY - zoneH / 2f).coerceIn(4f, size.height - zoneH - 4f)
                            drawRect(
                                color = if (cfg.isCalibrated) Color(0xFF00E5FF) else Color(0xFFF5B731),
                                topLeft = Offset(zoneLeft, zoneTop),
                                size = Size(zoneW, zoneH),
                                style = Stroke(width = 2.5.dp.toPx())
                            )
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

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Mx3dButton(
                        onClick = { viewModel.triggerRemoteCalibration(selectedAntennaId) },
                        containerColor = mx.telemetryGreen,
                        contentColor = contrastTextOn(mx.telemetryGreen),
                        modifier = Modifier.weight(1f).testTag("director_calibrate_antenna_btn")
                    ) {
                        Text(strings.calibrateEmptyTrack, maxLines = 1)
                    }
                    val auditPhoto = cfg.lastPhotoFinishBase64.ifBlank { cfg.lastSnapshotBase64 }
                    Mx3dOutlinedButton(
                        enabled = auditPhoto.isNotBlank() && !state.aiStewardBusy,
                        onClick = {
                            viewModel.inspectPhotoWithAiSteward(
                                photoBase64 = auditPhoto,
                                detectedSamples = cfg.lastDetectedLab,
                                ocrDorsalHint = cfg.lastDetectedDorsal,
                                autoAssignWarmupSample = true
                            )
                        },
                        borderColor = mx.hotlapPurple,
                        modifier = Modifier.weight(0.75f).testTag("director_ai_audit_photo")
                    ) {
                        Text(
                            when {
                                state.aiStewardBusy -> "IA..."
                                strings.isSpanish -> "Comisario IA"
                                else -> "AI Steward"
                            },
                            maxLines = 1
                        )
                    }
                }

                val verdict = state.lastAiVerdict ?: state.roomState.lastAiVerdict
                if (state.aiStewardError != null) {
                    Text(
                        text = state.aiStewardError,
                        style = MaterialTheme.typography.labelSmall,
                        color = mx.racingRed
                    )
                } else if (verdict != null) {
                    val vText = if (strings.isSpanish) verdict.reasoningEs else verdict.reasoningEn
                    Text(
                        text = "🤖 ${if (verdict.isRealPass) "#${verdict.detectedDorsal.ifBlank { "?" }} (${verdict.confidencePercent}%)" else "Falso paso"} • $vText",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (verdict.isRealPass) mx.hotlapPurple else mx.racingRed
                    )
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
    val mx = LocalMxColors.current
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

                Text(
                    if (strings.isSpanish) "Modo de Detección" else "Detection Mode",
                    style = MaterialTheme.typography.labelLarge,
                    color = mx.goldAccent
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = cfg.detectionMode == DetectionMode.ANY_PASS,
                            onClick = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, detectionMode = DetectionMode.ANY_PASS) },
                            label = { Text(if (strings.isSpanish) "Cualquier Paso" else "Any Pass") },
                            colors = mxChipColors()
                        )
                    }
                    item {
                        FilterChip(
                            selected = cfg.detectionMode == DetectionMode.FAIRING_COLOR,
                            onClick = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, detectionMode = DetectionMode.FAIRING_COLOR) },
                            label = { Text(if (strings.isSpanish) "Multi-Color CIE" else "Multi-Color") },
                            colors = mxChipColors()
                        )
                    }
                    item {
                        FilterChip(
                            selected = cfg.detectionMode == DetectionMode.SINGLE_COLOR,
                            onClick = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, detectionMode = DetectionMode.SINGLE_COLOR) },
                            label = { Text(if (strings.isSpanish) "Solo 1 Color" else "Single Color") },
                            colors = mxChipColors()
                        )
                    }
                }

                if (cfg.detectionMode == DetectionMode.SINGLE_COLOR) {
                    val palette = remember(strings.isSpanish) { predefinedMotoColors(strings.isSpanish) }
                    Text(
                        if (strings.isSpanish) "Color Único Objetivo: ${cfg.singleTargetColorName.ifBlank { "Cualquier perfil" }}"
                        else "Single Target Color: ${cfg.singleTargetColorName.ifBlank { "Any profile" }}",
                        style = MaterialTheme.typography.labelLarge,
                        color = mx.telemetryGreen
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(palette) { sample ->
                            val isSel = cfg.singleTargetColorName.equals(sample.colorName, ignoreCase = true)
                            val c = try { Color(android.graphics.Color.parseColor(sample.hex)) } catch (_: Exception) { Color.White }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    viewModel.updateRemoteAntennaConfig(selectedAntennaId, singleTargetColorName = sample.colorName)
                                }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(c)
                                        .border(if (isSel) 3.dp else 1.dp, if (isSel) mx.goldAccent else Color.White, CircleShape)
                                )
                                Text(sample.colorName, style = MaterialTheme.typography.labelSmall, color = if (isSel) mx.goldAccent else mx.subtleText)
                            }
                        }
                    }
                }

                Text(
                    if (strings.isSpanish) "Sentido de Paso + Comisario IA Gemini" else "Passing Direction + Gemini AI Steward",
                    style = MaterialTheme.typography.labelLarge,
                    color = mx.goldAccent
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = cfg.aiStewardEnabled,
                            onClick = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, aiStewardEnabled = !cfg.aiStewardEnabled) },
                            label = { Text(if (strings.isSpanish) "Comisario IA ${if (cfg.aiStewardEnabled) "ON" else "OFF"}" else "AI Steward ${if (cfg.aiStewardEnabled) "ON" else "OFF"}") },
                            colors = mxChipColors()
                        )
                    }
                    items(PassingDirection.entries) { dir ->
                        val dirLabel = when (dir) {
                            PassingDirection.ANY -> if (strings.isSpanish) "Cualquier Sentido" else "Any Dir"
                            PassingDirection.LEFT_TO_RIGHT -> "Izq → Der"
                            PassingDirection.RIGHT_TO_LEFT -> "Der → Izq"
                            PassingDirection.TOP_TO_BOTTOM -> "Arr → Abj"
                            PassingDirection.BOTTOM_TO_TOP -> "Abj → Arr"
                        }
                        FilterChip(
                            selected = cfg.directionFilter == dir,
                            onClick = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, directionFilter = dir) },
                            label = { Text(dirLabel) },
                            colors = mxChipColors()
                        )
                    }
                }

                Text(
                    if (strings.isSpanish) "Línea de Meta (Pantalla Completa • ${(cfg.linePosition * 100).toInt()}%)"
                    else "Finish Line (Full Screen • ${(cfg.linePosition * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = cfg.lineOrientation == LineOrientation.VERTICAL,
                        onClick = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, lineOrientation = LineOrientation.VERTICAL) },
                        label = { Text("Vertical") },
                        colors = mxChipColors()
                    )
                    FilterChip(
                        selected = cfg.lineOrientation == LineOrientation.HORIZONTAL,
                        onClick = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, lineOrientation = LineOrientation.HORIZONTAL) },
                        label = { Text("Horizontal") },
                        colors = mxChipColors()
                    )
                }
                Slider(
                    value = cfg.linePosition,
                    onValueChange = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, linePosition = it) },
                    valueRange = 0.1f..0.9f,
                    colors = SliderDefaults.colors(
                        thumbColor = mx.racingRed,
                        activeTrackColor = mx.racingRed,
                        inactiveTrackColor = mx.glassBorder
                    )
                )

                Text(
                    if (strings.isSpanish) "Zona de Color Independiente (X ${(cfg.colorZoneX * 100).toInt()}% • Y ${(cfg.colorZoneY * 100).toInt()}%)"
                    else "Separate Color Zone (X ${(cfg.colorZoneX * 100).toInt()}% • Y ${(cfg.colorZoneY * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF00E5FF)
                )
                Slider(
                    value = cfg.colorZoneX,
                    onValueChange = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, colorZoneX = it) },
                    valueRange = 0.15f..0.85f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E5FF),
                        activeTrackColor = Color(0xFF00E5FF),
                        inactiveTrackColor = mx.glassBorder
                    )
                )
                Slider(
                    value = cfg.colorZoneY,
                    onValueChange = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, colorZoneY = it) },
                    valueRange = 0.18f..0.82f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E5FF),
                        activeTrackColor = Color(0xFF00E5FF),
                        inactiveTrackColor = mx.glassBorder
                    )
                )

                Text(
                    if (strings.isSpanish) "Tamaño Zona de Color" else "Color Zone Size",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoiSize.entries.forEach { sz ->
                        FilterChip(
                            selected = cfg.roiSize == sz,
                            onClick = { viewModel.updateRemoteAntennaConfig(selectedAntennaId, roiSize = sz) },
                            label = { Text(sz.name) },
                            colors = mxChipColors()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectorSetupWizardTab(strings: MxStrings) {
    val mx = LocalMxColors.current
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
        Text(strings.setupWizard, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        steps.forEachIndexed { idx, txt ->
            GlassRaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked[idx],
                        onCheckedChange = { checked[idx] = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = mx.goldAccent,
                            checkmarkColor = contrastTextOn(mx.goldAccent),
                            uncheckedColor = mx.subtleText
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${idx + 1}. $txt", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
