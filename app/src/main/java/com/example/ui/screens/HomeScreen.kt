package com.example.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SportsMotorsports
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.formatLapMs
import com.example.engine.CardExporter
import com.example.i18n.MxStrings
import com.example.ui.components.CarbonStadiumBackdrop
import com.example.ui.components.GlassRaceCard
import com.example.ui.components.PulsingLiveDot
import com.example.ui.components.SurfaceBadge
import com.example.ui.components.metallicShimmerSweep
import com.example.ui.theme.LocalMxColors
import com.example.ui.viewmodel.MxProViewModel
import com.example.ui.viewmodel.MxUiState

@Composable
fun HomeScreen(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel,
    onNavigatePilot: () -> Unit,
    onNavigateLiveFan: () -> Unit,
    onNavigateAntenna: () -> Unit,
    onNavigateDirector: () -> Unit
) {
    val mx = LocalMxColors.current
    val context = LocalContext.current

    var showAuthDialog by remember { mutableStateOf(false) }
    var showDirectorDialog by remember { mutableStateOf(false) }
    var showCreateCircuitDialog by remember { mutableStateOf(false) }
    var showRankingPaddockDialog by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var circuitDropdownExpanded by remember { mutableStateOf(false) }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            viewModel.refreshLocation()
        }
    }

    LaunchedEffect(Unit) {
        locationLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    CarbonStadiumBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Discrete top bar: Director, Antennas, Sun Mode, ES/EN
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            if (state.directorUnlocked) onNavigateDirector()
                            else showDirectorDialog = true
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("home_director_button")
                    ) {
                        Icon(
                            Icons.Default.AdminPanelSettings,
                            contentDescription = strings.directorAccess,
                            tint = mx.subtleText,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(strings.directorAccess, color = mx.subtleText, style = MaterialTheme.typography.labelLarge)
                    }

                    TextButton(
                        onClick = onNavigateAntenna,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("home_antenna_button")
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = strings.antennaAccess,
                            tint = mx.subtleText,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(strings.antennaAccess, color = mx.subtleText, style = MaterialTheme.typography.labelLarge)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { viewModel.toggleLanguageOverride() },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("home_lang_button")
                    ) {
                        Icon(Icons.Default.Translate, contentDescription = "Language", tint = mx.goldAccent)
                    }
                    IconButton(
                        onClick = { viewModel.toggleSunMode() },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("home_sun_mode_button")
                    ) {
                        Icon(
                            imageVector = if (state.sunMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                            contentDescription = if (state.sunMode) strings.nightModeLabel else strings.sunModeLabel,
                            tint = mx.goldAccent
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Centered Metallic Shimmering Official Logo Hero Card
            GlassRaceCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .metallicShimmerSweep(enabled = !state.sunMode),
                accentBorder = mx.goldAccent.copy(alpha = 0.55f)
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(235.dp)) {
                    Image(
                        painter = painterResource(id = R.drawable.img_hero_stadium),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.35f),
                                        Color.Black.copy(alpha = 0.86f)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_metropolitano_logo),
                            contentDescription = strings.appTitle,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .height(152.dp)
                                .testTag("home_official_logo")
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = strings.appSubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFE2E8F0)
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.45f))
                                .clickable { viewModel.recalibrateClock() }
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                                .testTag("clock_sync_pill"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "RTDB SYNC ${if (state.serverTimeOffsetMs >= 0) "+" else ""}${state.serverTimeOffsetMs}ms • RTT ${state.lastSyncRttMs}ms • ${if (state.isOnline) "ONLINE" else "OFFLINE"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.isOnline) Color(0xFF4ADE80) else Color(0xFFF87171)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Active Circuit Selector & Timezone / GPS Protection
            GlassRaceCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.activeCircuit.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = mx.goldAccent
                        )
                        SurfaceBadge(surface = state.activeCircuit.surface)
                    }
                    Spacer(Modifier.height(6.dp))
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, mx.glassBorder, RoundedCornerShape(12.dp))
                                .clickable { circuitDropdownExpanded = true }
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                                .testTag("circuit_selector_dropdown"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = state.activeCircuit.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${state.activeCircuit.countryCity} • ${state.activeCircuit.lengthMeters}m • ${state.activeCircuit.layoutType}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = mx.subtleText
                                )
                            }
                            Text("▼", color = mx.goldAccent)
                        }
                        DropdownMenu(
                            expanded = circuitDropdownExpanded,
                            onDismissRequest = { circuitDropdownExpanded = false }
                        ) {
                            state.circuits.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text("${c.name} (${c.surface} • ${c.lengthMeters}m)") },
                                    onClick = {
                                        viewModel.selectCircuit(c)
                                        circuitDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    val geo = state.geoStatus
                    if (state.activeCircuit.isOfficial && geo != null && !geo.canRideMetropolitano) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(mx.racingRed.copy(alpha = 0.14f))
                                .border(1.dp, mx.racingRed.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = mx.racingRed)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(strings.timezoneWarningTitle, style = MaterialTheme.typography.titleMedium, color = mx.racingRed)
                                Text(strings.timezoneWarningBody, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    OutlinedButton(
                        onClick = { showCreateCircuitDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("create_local_circuit_button")
                    ) {
                        Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.createLocalCircuit)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Dynamic Banners: GPS near track, Weekly Hotlap, and Live Flashing Button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.geoStatus?.isNearActiveCircuit == true) {
                    GlassRaceCard(accentBorder = mx.telemetryGreen) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.GpsFixed, contentDescription = null, tint = mx.telemetryGreen)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = strings.gpsNearTrackBanner,
                                style = MaterialTheme.typography.bodyMedium,
                                color = mx.telemetryGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (state.roomState.weeklyHotlapMs > 0L) {
                    GlassRaceCard(accentBorder = mx.goldAccent.copy(alpha = 0.6f)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = mx.goldAccent)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${strings.weeklyHotlap}: ${state.roomState.weeklyHotlapRider}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Text(
                                text = formatLapMs(state.roomState.weeklyHotlapMs),
                                style = MaterialTheme.typography.titleLarge,
                                color = mx.goldAccent
                            )
                        }
                    }
                }

                if (state.isLiveActivityOnTrack) {
                    Button(
                        onClick = onNavigateLiveFan,
                        colors = ButtonDefaults.buttonColors(containerColor = mx.racingRed),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("live_flashing_banner_button")
                    ) {
                        PulsingLiveDot()
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = strings.ridingNowLive,
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Primary Action Cards
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (state.currentRider != null) {
                            viewModel.enterTrackAsLoggedRider()
                            onNavigatePilot()
                        } else {
                            showAuthDialog = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = mx.goldAccent,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .metallicShimmerSweep(enabled = !state.sunMode)
                        .minimumInteractiveComponentSize()
                        .testTag("home_pilot_button")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.SportsMotorsports, contentDescription = null, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (state.currentRider != null) {
                                    "${strings.iamPilot} (${state.currentRider.name})"
                                } else strings.iamPilot,
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = strings.iamPilotSub,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                GlassRaceCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateLiveFan() }
                        .testTag("home_fan_club_card"),
                    accentBorder = mx.smBadge.copy(alpha = 0.65f)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.LiveTv, contentDescription = null, tint = mx.smBadge, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.fanClubLive, style = MaterialTheme.typography.titleLarge)
                            Text(strings.fanClubSub, style = MaterialTheme.typography.bodyMedium, color = mx.subtleText)
                        }
                    }
                }

                GlassRaceCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showRankingPaddockDialog = true }
                        .testTag("home_ranking_paddock_card"),
                    accentBorder = mx.glassBorder
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = mx.goldAccent, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.worldRankingPaddock, style = MaterialTheme.typography.titleLarge)
                            Text(strings.worldRankingSub, style = MaterialTheme.typography.bodyMedium, color = mx.subtleText)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Bottom Utilities
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showGuideDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("home_guide_button")
                ) {
                    Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(strings.guideAndInstall, maxLines = 1)
                }

                OutlinedButton(
                    onClick = {
                        val msg = if (strings.isSpanish) {
                            "🏁 ¡Únete a Metropolitano MX Pro! Cronometraje RC 1/4 en vivo y setups Losi Promoto."
                        } else {
                            "🏁 Join Metropolitano MX Pro! Live 1/4 RC lap timing & Losi Promoto setups."
                        }
                        CardExporter.shareWhatsAppText(context, msg)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("home_whatsapp_invite_button")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(strings.inviteWhatsApp, maxLines = 1)
                }

                OutlinedButton(
                    onClick = { showReportDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("home_report_inbox_button")
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(strings.reportInbox, maxLines = 1)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showAuthDialog) {
        PilotAuthDialog(
            state = state,
            strings = strings,
            viewModel = viewModel,
            onDismiss = { showAuthDialog = false },
            onAuthenticated = {
                showAuthDialog = false
                onNavigatePilot()
            }
        )
    }

    if (showDirectorDialog) {
        DirectorUnlockDialog(
            strings = strings,
            onDismiss = { showDirectorDialog = false },
            onAttemptUnlock = { pwd ->
                if (viewModel.unlockDirector(pwd)) {
                    showDirectorDialog = false
                    onNavigateDirector()
                    true
                } else false
            }
        )
    }

    if (showCreateCircuitDialog) {
        CreateCircuitDialog(
            strings = strings,
            onDismiss = { showCreateCircuitDialog = false },
            onSave = { name, city, surf, len, layout ->
                viewModel.createLocalCircuit(name, city, surf, len, layout)
                showCreateCircuitDialog = false
            }
        )
    }

    if (showRankingPaddockDialog) {
        WorldRankingPaddockDialog(
            state = state,
            strings = strings,
            onDismiss = { showRankingPaddockDialog = false }
        )
    }

    if (showGuideDialog) {
        AppGuideDialog(strings = strings, onDismiss = { showGuideDialog = false })
    }

    if (showReportDialog) {
        ReportInboxDialog(
            state = state,
            strings = strings,
            onDismiss = { showReportDialog = false },
            onSubmit = { sender, type, msg ->
                viewModel.submitAppReport(sender, type, msg)
                showReportDialog = false
            }
        )
    }
}
