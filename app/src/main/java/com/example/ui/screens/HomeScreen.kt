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
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SportsMotorsports
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.example.ui.components.AnimatedCheckeredRibbon
import com.example.ui.components.CarbonStadiumBackdrop
import com.example.ui.components.GlassRaceCard
import com.example.ui.components.Mx3dButton
import com.example.ui.components.Mx3dOutlinedButton
import com.example.ui.components.PulsingLiveDot
import com.example.ui.components.SurfaceBadge
import com.example.ui.components.metallicShimmerSweep
import com.example.ui.theme.LocalMxColors
import com.example.ui.theme.contrastTextOn
import com.example.ui.theme.mxTextFieldColors
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
    var reportInitialType by remember { mutableStateOf("BUG") }
    var showApkCodeDialog by remember { mutableStateOf(false) }
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
                            tint = mx.goldAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            strings.directorAccess,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge
                        )
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
                            tint = mx.smBadge,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            strings.antennaAccess,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge
                        )
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
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            AnimatedCheckeredRibbon(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
            )
            Spacer(Modifier.height(10.dp))

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

                    Mx3dOutlinedButton(
                        onClick = { showCreateCircuitDialog = true },
                        borderColor = mx.smBadge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("create_local_circuit_button")
                    ) {
                        Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp), tint = mx.smBadge)
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

                if (state.roomState.mxRecordMs > 0L || state.roomState.smRecordMs > 0L) {
                    GlassRaceCard(accentBorder = mx.goldAccent.copy(alpha = 0.6f)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (state.roomState.mxRecordMs > 0L) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = mx.mxBadge)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Hotlap MX: ${state.roomState.mxRecordRider}", style = MaterialTheme.typography.titleMedium)
                                    }
                                    Text(formatLapMs(state.roomState.mxRecordMs), style = MaterialTheme.typography.titleLarge, color = mx.mxBadge)
                                }
                            }
                            if (state.roomState.smRecordMs > 0L) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = mx.smBadge)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Hotlap SM: ${state.roomState.smRecordRider}", style = MaterialTheme.typography.titleMedium)
                                    }
                                    Text(formatLapMs(state.roomState.smRecordMs), style = MaterialTheme.typography.titleLarge, color = mx.smBadge)
                                }
                            }
                        }
                    }
                } else if (state.roomState.weeklyHotlapMs > 0L) {
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
                    Mx3dButton(
                        onClick = onNavigateLiveFan,
                        containerColor = mx.racingRed,
                        contentColor = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
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
                Mx3dButton(
                    onClick = {
                        if (state.currentRider != null) {
                            viewModel.enterTrackAsLoggedRider()
                            onNavigatePilot()
                        } else {
                            showAuthDialog = true
                        }
                    },
                    containerColor = mx.goldAccent,
                    contentColor = contrastTextOn(mx.goldAccent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_pilot_button")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
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
                Mx3dOutlinedButton(
                    onClick = { showGuideDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("home_guide_button")
                ) {
                    Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(16.dp), tint = mx.goldAccent)
                    Spacer(Modifier.width(4.dp))
                    Text(strings.guideAndInstall, maxLines = 1)
                }

                Mx3dOutlinedButton(
                    onClick = {
                        val isLocked = state.globalConfig.apkDownloadLocked &&
                            !state.installCodeUnlocked &&
                            !state.directorUnlocked
                        if (isLocked) {
                            showApkCodeDialog = true
                        } else {
                            val msg = if (strings.isSpanish) {
                                "🏁 ¡Únete a Metropolitano MX Pro! Cronometraje RC 1/4 en vivo y setups Losi Promoto: ${state.globalConfig.apkDownloadUrl}"
                            } else {
                                "🏁 Join Metropolitano MX Pro! Live 1/4 RC lap timing & Losi Promoto setups: ${state.globalConfig.apkDownloadUrl}"
                            }
                            CardExporter.shareWhatsAppText(context, msg)
                        }
                    },
                    borderColor = if (state.globalConfig.apkDownloadLocked && !state.installCodeUnlocked && !state.directorUnlocked) mx.racingRed else mx.telemetryGreen,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("home_whatsapp_invite_button")
                ) {
                    Icon(
                        imageVector = if (state.globalConfig.apkDownloadLocked && !state.installCodeUnlocked && !state.directorUnlocked) Icons.Default.Lock else Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (state.globalConfig.apkDownloadLocked && !state.installCodeUnlocked && !state.directorUnlocked) mx.racingRed else mx.telemetryGreen
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (state.globalConfig.apkDownloadLocked && !state.installCodeUnlocked && !state.directorUnlocked) {
                            if (strings.isSpanish) "APK Bloq." else "APK Locked"
                        } else strings.inviteWhatsApp,
                        maxLines = 1
                    )
                }

                Mx3dOutlinedButton(
                    onClick = {
                        reportInitialType = "BUG"
                        showReportDialog = true
                    },
                    borderColor = mx.racingRed,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("home_report_inbox_button")
                ) {
                    Icon(Icons.Default.Mail, contentDescription = null, modifier = Modifier.size(16.dp), tint = mx.racingRed)
                    Spacer(Modifier.width(4.dp))
                    Text(strings.reportInbox, maxLines = 1)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Universal Track & Official Circuit Program Card
            GlassRaceCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                accentBorder = mx.goldAccent.copy(alpha = 0.55f)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Verified, contentDescription = null, tint = mx.goldAccent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (strings.isSpanish) "Cronometra tu Losi Promoto en Cualquier Lugar"
                            else "Time Your Losi Promoto Anywhere",
                            style = MaterialTheme.typography.titleMedium,
                            color = mx.goldAccent
                        )
                    }
                    Text(
                        text = if (strings.isSpanish) {
                            "Funciona en un descampado, parking, el jardín de tu casa o en circuito oficial. Crea tu trazado y saca tus tiempos al instante.\n¿Tienes un club o circuito? Pide tu código por Buzón para desbloquear la APK y pasar a Circuito Oficial."
                        } else {
                            "Works in an open lot, parking area, your backyard, or an official MX track. Create your track and log real lap times anywhere.\nOwn a track or club? Request an Official Circuit & APK code via Inbox."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = mx.subtleText
                    )
                    Mx3dButton(
                        onClick = {
                            reportInitialType = "OFFICIAL_REQUEST"
                            showReportDialog = true
                        },
                        containerColor = mx.smBadge,
                        contentColor = contrastTextOn(mx.smBadge),
                        modifier = Modifier.fillMaxWidth().testTag("home_request_official_button")
                    ) {
                        Icon(Icons.Default.Mail, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (strings.isSpanish) "Pedir Código / Ser Circuito Oficial (Buzón)"
                            else "Request Code / Become Official Circuit (Inbox)"
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    // Installation Lock Gate for new/locked installs when Director turns APK lock ON
    val mustGateInstall = state.globalConfig.apkDownloadLocked &&
        !state.installCodeUnlocked &&
        !state.directorUnlocked &&
        state.currentRider == null
    if (mustGateInstall) {
        var gateCode by remember { mutableStateOf("") }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassRaceCard(
                modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
                accentBorder = mx.goldAccent
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = mx.racingRed)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (strings.isSpanish) "Instalación Protegida" else "Protected Installation",
                                style = MaterialTheme.typography.titleLarge,
                                color = mx.goldAccent
                            )
                        }
                        IconButton(onClick = { showDirectorDialog = true }) {
                            Icon(Icons.Default.AdminPanelSettings, contentDescription = strings.directorAccess, tint = mx.goldAccent)
                        }
                    }
                    Text(
                        if (strings.isSpanish) {
                            "Dirección ha activado el acceso exclusivo mediante código de invitación para nuevas instalaciones y circuitos oficiales."
                        } else {
                            "Race Director has enabled invite-code protection for new installations and official circuits."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    OutlinedTextField(
                        value = gateCode,
                        onValueChange = { gateCode = it.uppercase() },
                        label = { Text(if (strings.isSpanish) "Código de Activación" else "Activation Code") },
                        singleLine = true,
                        colors = mxTextFieldColors(),
                        modifier = Modifier.fillMaxWidth().testTag("install_gate_code_input")
                    )
                    Mx3dButton(
                        onClick = { viewModel.redeemInstallOrDownloadCode(gateCode) {} },
                        containerColor = mx.goldAccent,
                        contentColor = contrastTextOn(mx.goldAccent),
                        modifier = Modifier.fillMaxWidth().testTag("install_gate_unlock_button")
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (strings.isSpanish) "Desbloquear con Código" else "Unlock with Code")
                    }
                    Mx3dOutlinedButton(
                        onClick = {
                            reportInitialType = "OFFICIAL_REQUEST"
                            showReportDialog = true
                        },
                        borderColor = mx.smBadge,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Mail, contentDescription = null, modifier = Modifier.size(16.dp), tint = mx.smBadge)
                        Spacer(Modifier.width(6.dp))
                        Text(if (strings.isSpanish) "Solicitar Código por Buzón" else "Request Code via Inbox")
                    }
                }
            }
        }
    }

    if (showApkCodeDialog) {
        var apkCode by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showApkCodeDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(if (strings.isSpanish) "Descarga APK Protegida" else "Protected APK Download") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (strings.isSpanish) {
                            "La descarga de la APK requiere un código emitido por Dirección para circuitos oficiales."
                        } else {
                            "APK download requires a Director-issued code for official circuits."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = apkCode,
                        onValueChange = { apkCode = it.uppercase() },
                        label = { Text(if (strings.isSpanish) "Introduce tu código" else "Enter your code") },
                        singleLine = true,
                        colors = mxTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Mx3dButton(
                    onClick = {
                        viewModel.redeemInstallOrDownloadCode(apkCode) {
                            showApkCodeDialog = false
                            val msg = "🏁 Metropolitano MX Pro (${it.circuitName}): ${state.globalConfig.apkDownloadUrl}"
                            CardExporter.shareWhatsAppText(context, msg)
                        }
                    },
                    containerColor = mx.goldAccent,
                    contentColor = contrastTextOn(mx.goldAccent)
                ) {
                    Text(if (strings.isSpanish) "Validar y Compartir" else "Verify & Share")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showApkCodeDialog = false
                        reportInitialType = "OFFICIAL_REQUEST"
                        showReportDialog = true
                    }) {
                        Text(if (strings.isSpanish) "Pedir Código" else "Request Code", color = mx.smBadge)
                    }
                    TextButton(onClick = { showApkCodeDialog = false }) {
                        Text(if (strings.isSpanish) "Cerrar" else "Close", color = mx.subtleText)
                    }
                }
            }
        )
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
            state = state,
            strings = strings,
            onDismiss = { showDirectorDialog = false },
            onAttemptUnlock = { pwd, rememberCreds ->
                if (viewModel.unlockDirector(pwd, rememberCreds)) {
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
            viewModel = viewModel,
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
            initialType = reportInitialType,
            onDismiss = { showReportDialog = false },
            onSubmit = { sender, type, msg, circuitReq ->
                viewModel.submitAppReport(sender, type, msg, circuitReq)
                showReportDialog = false
            }
        )
    }
}
