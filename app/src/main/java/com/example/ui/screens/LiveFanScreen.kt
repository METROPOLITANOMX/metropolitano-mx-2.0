package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ActiveRoomRider
import com.example.data.model.AntennaStation
import com.example.data.model.SurfaceType
import com.example.data.model.formatDeltaMs
import com.example.data.model.formatLapMs
import com.example.i18n.MxStrings
import com.example.ui.components.AnimatedCheckeredRibbon
import com.example.ui.components.Base64ImageView
import com.example.ui.components.CarbonStadiumBackdrop
import com.example.ui.components.FlagAndRecordFrameOverlay
import com.example.ui.components.GlassRaceCard
import com.example.ui.components.LapTelemetryChartCard
import com.example.ui.components.Mx3dButton
import com.example.ui.components.MxTopHeader
import com.example.ui.components.PulsingLiveDot
import com.example.ui.components.RiderDorsalBadge
import com.example.ui.components.SupercrossStartGateCard
import com.example.ui.components.SurfaceBadge
import com.example.ui.theme.LocalMxColors
import com.example.ui.theme.contrastTextOn
import com.example.ui.theme.mxChipColors
import com.example.ui.theme.mxTextFieldColors
import com.example.ui.viewmodel.MxProViewModel
import com.example.ui.viewmodel.MxUiState
import kotlinx.coroutines.delay

@Composable
fun LiveFanScreen(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel,
    onBack: () -> Unit
) {
    val mx = LocalMxColors.current
    var spyRider by remember { mutableStateOf<ActiveRoomRider?>(null) }
    var pitBoardRiderId by remember { mutableStateOf<String?>(null) }
    var fanName by remember { mutableStateOf(state.currentRider?.name ?: "Aficionado") }
    var cheerInput by remember { mutableStateOf("") }
    var cheerTarget by remember { mutableStateOf("") }

    var liveNowMs by remember { mutableLongStateOf(viewModel.syncedNowMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            liveNowMs = viewModel.syncedNowMs()
            delay(100L)
        }
    }

    var classFilter by remember { mutableStateOf<SurfaceType?>(null) }
    val allLeaderboard = state.roomState.activeRiders.values
        .sortedWith(compareByDescending<ActiveRoomRider> { it.lapsCount }.thenBy { if (it.bestLapMs <= 0) Long.MAX_VALUE else it.bestLapMs })
    val leaderboard = allLeaderboard.filter { classFilter == null || it.discipline == classFilter }
    val leaderBest = leaderboard.filter { it.bestLapMs > 0 }.minOfOrNull { it.bestLapMs } ?: 0L
    val photoFinishB64 = state.antennas[AntennaStation.ANTENA_1.id]?.lastPhotoFinishBase64 ?: ""
    val liveCameraB64 = state.antennas[AntennaStation.ANTENA_1.id]?.lastSnapshotBase64 ?: photoFinishB64
    val lastHotlapAtMs = if (state.roomState.lastPaAnnouncement?.isHotlap == true) {
        state.roomState.lastPaAnnouncement.timestampMs
    } else 0L

    // Dedicated Mechanic Pit-Board Mode ("Modo Mecánico" — Split-Screen in Landscape!)
    val pitRider = pitBoardRiderId?.let { id -> state.roomState.activeRiders[id] }
    if (pitRider != null) {
        val elapsedMs = if (pitRider.lastPassTimestampMs > 0L) (liveNowMs - pitRider.lastPassTimestampMs).coerceAtLeast(0L) else 0L
        val lipoMin = if (pitRider.sessionStartMs > 0L) ((liveNowMs - pitRider.sessionStartMs) / 60_000L).toInt() else 0
        val riderLaps = state.circuitLaps.filter { it.riderId == pitRider.riderId }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF07090D))
                .padding(16.dp)
        ) {
            val isLandscapeSplit = maxWidth >= 600.dp
            if (isLandscapeSplit) {
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Left Pane: Antenna 1 Camera + Rider Plate + Live Stopwatch
                    Column(
                        modifier = Modifier.weight(0.48f).fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("PIT-BOARD • #${pitRider.dorsal} ${pitRider.riderName}", style = MaterialTheme.typography.titleLarge, color = mx.goldAccent)
                            OutlinedButton(onClick = { pitBoardRiderId = null }) { Text(if (strings.isSpanish) "Salir" else "Exit") }
                        }
                        if (liveCameraB64.isNotBlank()) {
                            Base64ImageView(
                                base64 = liveCameraB64,
                                contentDescription = "Antenna Feed",
                                modifier = Modifier.fillMaxWidth().height(145.dp).clip(RoundedCornerShape(12.dp))
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("LIVE LAP", style = MaterialTheme.typography.labelLarge, color = Color(0xFF94A3B8))
                            Text(
                                text = formatLapMs(elapsedMs),
                                style = MaterialTheme.typography.displayLarge.copy(fontSize = 58.sp, lineHeight = 62.sp),
                                color = Color.White
                            )
                        }
                    }

                    // Right Pane: Split Stats + Telemetry Chart
                    Column(
                        modifier = Modifier.weight(0.52f).fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            GlassRaceCard(modifier = Modifier.weight(1f)) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("LAST", style = MaterialTheme.typography.labelSmall, color = mx.subtleText)
                                    Text(formatLapMs(pitRider.lastLapMs), style = MaterialTheme.typography.headlineMedium, color = Color(0xFF38BDF8))
                                }
                            }
                            GlassRaceCard(modifier = Modifier.weight(1f)) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("BEST", style = MaterialTheme.typography.labelSmall, color = mx.subtleText)
                                    Text(formatLapMs(pitRider.bestLapMs), style = MaterialTheme.typography.headlineMedium, color = Color(0xFF4ADE80))
                                }
                            }
                        }
                        GlassRaceCard(accentBorder = if (lipoMin >= state.roomState.lipoWarnMinutes) mx.racingRed else mx.goldAccent) {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("LAPS: ${pitRider.lapsCount}", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "LiPo: $lipoMin / ${state.roomState.lipoWarnMinutes} min",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (lipoMin >= state.roomState.lipoWarnMinutes) mx.racingRed else mx.goldBright
                                )
                            }
                        }
                        LapTelemetryChartCard(laps = riderLaps, isSpanish = strings.isSpanish)
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("PIT-BOARD • #${pitRider.dorsal} ${pitRider.riderName}", style = MaterialTheme.typography.headlineMedium, color = mx.goldAccent)
                        OutlinedButton(onClick = { pitBoardRiderId = null }) { Text(if (strings.isSpanish) "Salir" else "Exit") }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("LIVE LAP", style = MaterialTheme.typography.titleLarge, color = Color(0xFF94A3B8))
                        Text(
                            text = formatLapMs(elapsedMs),
                            style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp, lineHeight = 76.sp),
                            color = Color.White
                        )
                        Spacer(Modifier.height(14.dp))
                        Text("LAST: ${formatLapMs(pitRider.lastLapMs)}", style = MaterialTheme.typography.headlineLarge, color = Color(0xFF38BDF8))
                        Text("BEST: ${formatLapMs(pitRider.bestLapMs)}", style = MaterialTheme.typography.headlineLarge, color = Color(0xFF4ADE80))
                    }
                    GlassRaceCard(accentBorder = if (lipoMin >= state.roomState.lipoWarnMinutes) mx.racingRed else mx.goldAccent) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("LAPS: ${pitRider.lapsCount}", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "LiPo: $lipoMin / ${state.roomState.lipoWarnMinutes} min",
                                style = MaterialTheme.typography.titleLarge,
                                color = if (lipoMin >= state.roomState.lipoWarnMinutes) mx.racingRed else mx.goldBright
                            )
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
                    title = strings.liveTimingTitle,
                    isOnline = state.isOnline,
                    sunMode = state.sunMode,
                    isSpanish = strings.isSpanish,
                    onBack = onBack,
                    onToggleSunMode = { viewModel.toggleSunMode() },
                    onToggleLang = { viewModel.toggleLanguageOverride() },
                    paMuted = state.paAudioMuted,
                    onTogglePaMute = { viewModel.togglePaAudioMute() }
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AnimatedCheckeredRibbon()
                    SupercrossStartGateCard(
                        sessionPhase = state.roomState.sessionPhase,
                        phaseEndTimeMs = state.roomState.phaseEndTimeMs,
                        phaseStartTimeMs = state.roomState.phaseStartTimeMs,
                        nowMs = liveNowMs,
                        isSpanish = strings.isSpanish
                    )

                    // Live Finish Line Photo-Finish
                    if (photoFinishB64.isNotBlank()) {
                        val ant1Cfg = state.antennas[AntennaStation.ANTENA_1.id]
                        val pfVerdict = state.lastAiVerdict ?: state.roomState.lastAiVerdict
                        GlassRaceCard(accentBorder = mx.racingRed.copy(alpha = 0.7f)) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        PulsingLiveDot()
                                        Spacer(Modifier.width(8.dp))
                                        Text("PHOTO-FINISH META", style = MaterialTheme.typography.labelLarge, color = mx.racingRed)
                                    }
                                    if (!ant1Cfg?.lastDetectedDorsal.isNullOrBlank()) {
                                        Text("#${ant1Cfg?.lastDetectedDorsal}", style = MaterialTheme.typography.labelLarge, color = mx.goldBright, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Base64ImageView(
                                    base64 = photoFinishB64,
                                    contentDescription = "Photo Finish",
                                    modifier = Modifier.fillMaxWidth().height(135.dp).clip(RoundedCornerShape(10.dp))
                                )
                                if (pfVerdict != null) {
                                    Text(
                                        text = "🤖 ${if (strings.isSpanish) pfVerdict.reasoningEs else pfVerdict.reasoningEn}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (pfVerdict.isRealPass) mx.hotlapPurple else mx.racingRed
                                    )
                                }
                            }
                        }
                    }

                    // Live Timing Leaderboard (MX / SM Filter + Dorsal Medals + Hotlap Glow)
                    GlassRaceCard {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (strings.isSpanish) "Clasificación en Directo (Toca para Espiar Setup)" else "Live Standings (Tap to Spy Setup)",
                                style = MaterialTheme.typography.titleMedium,
                                color = mx.goldAccent
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = classFilter == null, onClick = { classFilter = null }, label = { Text(if (strings.isSpanish) "Todas" else "All") }, colors = mxChipColors())
                                FilterChip(selected = classFilter == SurfaceType.MX, onClick = { classFilter = SurfaceType.MX }, label = { Text("MX Tierra") }, colors = mxChipColors())
                                FilterChip(selected = classFilter == SurfaceType.SM, onClick = { classFilter = SurfaceType.SM }, label = { Text("SM Supermotard") }, colors = mxChipColors())
                            }
                            if (leaderboard.isEmpty()) {
                                Text(
                                    if (strings.isSpanish) "Esperando pilotos en pista..." else "Waiting for riders on track...",
                                    color = mx.subtleText
                                )
                            } else {
                                leaderboard.forEachIndexed { idx, r ->
                                    val gap = if (leaderBest > 0 && r.bestLapMs > 0) r.bestLapMs - leaderBest else 0L
                                    val isHotlapHolder = leaderBest > 0L && r.bestLapMs == leaderBest
                                    val colorProf = state.colorProfiles.firstOrNull { it.riderId == r.riderId }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                if (isHotlapHolder) mx.hotlapPurple.copy(alpha = 0.16f)
                                                else mx.glassBorder.copy(alpha = 0.25f)
                                            )
                                            .clickable { spyRider = r }
                                            .padding(10.dp)
                                            .testTag("live_rider_row_${r.riderId}"),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                RiderDorsalBadge(
                                                    dorsal = r.dorsal,
                                                    discipline = r.discipline,
                                                    colorProfile = colorProf,
                                                    position = idx + 1
                                                )
                                                Text(r.riderName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                                SurfaceBadge(r.discipline)
                                            }
                                            Text(
                                                "${r.lapsCount} laps • Last ${formatLapMs(r.lastLapMs)} • Gap ${formatDeltaMs(gap)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = mx.subtleText
                                            )
                                        }
                                        Text(
                                            formatLapMs(r.bestLapMs),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (isHotlapHolder) mx.hotlapPurple else mx.telemetryGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                // Active Championship Standings
                val activeChamp = state.championships.firstOrNull { it.id == state.activeChampionshipId }
                if (activeChamp != null) {
                    GlassRaceCard(accentBorder = mx.goldAccent.copy(alpha = 0.6f)) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "🏆 ${activeChamp.name} (${activeChamp.completedHeats}/${activeChamp.totalHeats})",
                                style = MaterialTheme.typography.titleMedium,
                                color = mx.goldAccent
                            )
                            activeChamp.standings.values.sortedByDescending { it.points }.take(6).forEachIndexed { idx, st ->
                                Text(
                                    "${idx + 1}. ${st.riderName} #${st.dorsal} — ${st.points} pts",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }

                // Pit-Board Selector
                if (leaderboard.isNotEmpty()) {
                    GlassRaceCard {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Dashboard, contentDescription = null, tint = mx.smBadge)
                                Spacer(Modifier.width(6.dp))
                                Text(strings.pitBoardMode, style = MaterialTheme.typography.titleMedium)
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(leaderboard) { r ->
                                    Button(onClick = { pitBoardRiderId = r.riderId }) {
                                        Text("${r.riderName} #${r.dorsal}")
                                    }
                                }
                            }
                        }
                    }
                }

                // Crowd Ovation (Every 10 taps fires live applause!)
                val toNextTen = 10 - (state.fanState.ovationCount % 10)
                Mx3dButton(
                    onClick = { viewModel.tapStadiumOvation() },
                    containerColor = mx.goldAccent,
                    contentColor = contrastTextOn(mx.goldAccent),
                    modifier = Modifier.fillMaxWidth().testTag("fan_ovation_button")
                ) {
                    Icon(Icons.Default.Campaign, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("${strings.stadiumOvation} (${state.fanState.ovationCount} • -$toNextTen applause)")
                }

                // Porra Bet & MVP Vote
                if (leaderboard.isNotEmpty()) {
                    GlassRaceCard {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = mx.goldAccent)
                                Spacer(Modifier.width(6.dp))
                                Text(strings.preRaceBet, style = MaterialTheme.typography.titleMedium)
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(leaderboard) { r ->
                                    OutlinedButton(onClick = { viewModel.placePreRaceBet(fanName, r.riderName) }) {
                                        Text("${r.riderName}")
                                    }
                                }
                            }
                            if (state.fanState.bets.isNotEmpty()) {
                                Text(
                                    state.fanState.bets.entries.joinToString(" • ") { "${it.key}→${it.value}" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = mx.subtleText
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ThumbUp, contentDescription = null, tint = mx.smBadge)
                                Spacer(Modifier.width(6.dp))
                                Text(strings.voteMvp, style = MaterialTheme.typography.titleMedium)
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(leaderboard) { r ->
                                    val votes = state.fanState.mvpVotes[r.riderName] ?: 0
                                    FilterChip(
                                        selected = false,
                                        onClick = { viewModel.voteMvp(r.riderName) },
                                        label = { Text("${r.riderName} ($votes)") },
                                        colors = mxChipColors()
                                    )
                                }
                            }
                        }
                    }
                }

                // Quick Cheer Messages (spoken via PA & pilot headsets)
                GlassRaceCard {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Favorite, contentDescription = null, tint = mx.racingRed)
                            Spacer(Modifier.width(6.dp))
                            Text(strings.cheerMessage, style = MaterialTheme.typography.titleMedium)
                        }
                        OutlinedTextField(value = fanName, onValueChange = { fanName = it }, label = { Text("Tu nombre / Your name") }, singleLine = true, colors = mxTextFieldColors(), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = cheerTarget, onValueChange = { cheerTarget = it }, label = { Text("Piloto / Rider (opcional)") }, singleLine = true, colors = mxTextFieldColors(), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = cheerInput, onValueChange = { cheerInput = it }, label = { Text("Mensaje / Cheer") }, singleLine = true, colors = mxTextFieldColors(), modifier = Modifier.fillMaxWidth())
                        Mx3dButton(
                            onClick = {
                                viewModel.sendCheerMessage(fanName, cheerTarget, cheerInput)
                                cheerInput = ""
                            },
                            containerColor = mx.smBadge,
                            contentColor = contrastTextOn(mx.smBadge),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (strings.isSpanish) "Enviar a Megafonía" else "Send to PA") }

                        state.fanState.cheerMessages.take(4).forEach { msg ->
                            Text("📣 ${msg.fanName}: ${msg.text}", style = MaterialTheme.typography.bodyMedium, color = mx.goldBright)
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

    spyRider?.let { r ->
        AlertDialog(
            onDismissRequest = { spyRider = null },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("${strings.setupSpyTitle}: ${r.riderName} #${r.dorsal}", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${r.bikeModel} (${r.discipline})", style = MaterialTheme.typography.titleMedium)
                    Text(r.setupSummary.ifBlank { "Standard factory setup" }, style = MaterialTheme.typography.bodyMedium)
                    Text("${strings.bestLap}: ${formatLapMs(r.bestLapMs)} • ${r.lapsCount} laps", style = MaterialTheme.typography.labelMedium, color = mx.telemetryGreen)
                }
            },
            confirmButton = { TextButton(onClick = { spyRider = null }) { Text("OK", color = mx.goldAccent) } }
        )
    }
}
