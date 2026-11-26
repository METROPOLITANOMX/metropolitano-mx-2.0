package com.example.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.data.model.AntennaStation
import com.example.data.model.DetectionMode
import com.example.data.model.RiderAccount
import com.example.data.model.RiderStatus
import com.example.data.model.SessionPhase
import com.example.i18n.MxStrings
import com.example.ui.components.GlassRaceCard
import com.example.ui.theme.LocalMxColors
import com.example.ui.theme.contrastTextOn
import com.example.ui.viewmodel.MxProViewModel
import com.example.ui.viewmodel.MxUiState

@Composable
fun DirectorColorWarmupTab(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel,
    liveNowMs: Long
) {
    val mx = LocalMxColors.current
    val ant1 = state.antennas[AntennaStation.ANTENA_1.id]
    val latestSamples = ant1?.lastDetectedLab ?: emptyList()
    val ridersInRoom = state.roomState.activeRiders.values.toList()
    var selectedRiderId by remember { mutableStateOf(ridersInRoom.firstOrNull()?.riderId ?: "") }
    val isSingleColorMode = ant1?.detectionMode == DetectionMode.SINGLE_COLOR
    val isWarmup = state.roomState.sessionPhase == SessionPhase.WARMUP

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Warm-Up 3:00 Timer & Capture Mode Toggle
        GlassRaceCard(accentBorder = if (isWarmup) mx.telemetryGreen else mx.goldAccent.copy(alpha = 0.5f)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (strings.isSpanish) "Warm-Up 3 min (Capturas)" else "Warm-Up 3 min (Captures)",
                        style = MaterialTheme.typography.titleMedium,
                        color = mx.goldAccent
                    )
                    if (isWarmup && state.roomState.phaseEndTimeMs > liveNowMs) {
                        val remSec = ((state.roomState.phaseEndTimeMs - liveNowMs) / 1000L).toInt().coerceAtLeast(0)
                        Text(
                            text = String.format("%d:%02d", remSec / 60, remSec % 60),
                            style = MaterialTheme.typography.headlineMedium,
                            color = mx.telemetryGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (isWarmup) {
                    Button(
                        onClick = { viewModel.stopSession() },
                        colors = ButtonDefaults.buttonColors(containerColor = mx.racingRed, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (strings.isSpanish) "STOP WARM-UP (Pilotos a Parrilla)" else "STOP WARM-UP (Riders to Grid)")
                    }
                } else {
                    Button(
                        onClick = { viewModel.startWarmup(friendly = false) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = mx.goldAccent,
                            contentColor = contrastTextOn(mx.goldAccent)
                        ),
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (strings.isSpanish) "Iniciar Warm-Up (3:00)" else "Start Warm-Up (3:00)")
                    }
                }

                Text(
                    if (strings.isSpanish) "Modo de Captura de Color (Antena 1)" else "Color Capture Mode (Antenna 1)",
                    style = MaterialTheme.typography.labelLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !isSingleColorMode,
                        onClick = {
                            viewModel.updateRemoteAntennaConfig(
                                AntennaStation.ANTENA_1.id,
                                detectionMode = DetectionMode.FAIRING_COLOR
                            )
                        },
                        label = { Text(if (strings.isSpanish) "Multi-Captura" else "Multi-Color") }
                    )
                    FilterChip(
                        selected = isSingleColorMode,
                        onClick = {
                            viewModel.updateRemoteAntennaConfig(
                                AntennaStation.ANTENA_1.id,
                                detectionMode = DetectionMode.SINGLE_COLOR
                            )
                        },
                        label = { Text(if (strings.isSpanish) "Solo 1 Color" else "Single Color") }
                    )
                }
            }
        }

        if (ridersInRoom.isNotEmpty()) {
            Text(if (strings.isSpanish) "1. Selecciona el Piloto a Entrenar:" else "1. Select Rider to Train:", style = MaterialTheme.typography.titleMedium, color = mx.goldAccent)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ridersInRoom) { r ->
                    FilterChip(
                        selected = r.riderId == selectedRiderId,
                        onClick = { selectedRiderId = r.riderId },
                        label = { Text("${r.riderName} #${r.dorsal}") }
                    )
                }
            }
        }

        // Rolling Multi-Capture & Single-Color Pass History
        GlassRaceCard(accentBorder = mx.goldAccent.copy(alpha = 0.6f)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (isSingleColorMode) {
                        if (strings.isSpanish) "2. Capturas en Pista (Toca 1 color para fijarlo)" else "2. Track Captures (Tap 1 color to lock)"
                    } else {
                        if (strings.isSpanish) "2. Capturas Recientes en Pista (Asigna varias pasadas)" else "2. Recent Track Captures (Assign multiple passes)"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = mx.goldAccent
                )

                if (state.capturedPasses.isEmpty() && latestSamples.isEmpty()) {
                    Text(
                        if (strings.isSpanish) "Inicia el Warm-Up (3 min) y pasa la moto frente a la Antena para capturar sus tonos." else "Start the 3-min Warm-Up and pass the bike past the Antenna to capture tones.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = mx.subtleText
                    )
                } else {
                    if (latestSamples.isNotEmpty() && state.capturedPasses.isEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            latestSamples.forEach { sample ->
                                val c = try { Color(android.graphics.Color.parseColor(sample.hex)) } catch (_: Exception) { Color.White }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable {
                                        val r = ridersInRoom.firstOrNull { it.riderId == selectedRiderId }
                                        if (r != null) {
                                            viewModel.assignSingleColorToRider(r.riderId, r.riderName, sample)
                                        }
                                    }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(c)
                                            .border(1.dp, Color.White, RoundedCornerShape(8.dp))
                                    )
                                    Text(sample.colorName, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        if (ridersInRoom.isNotEmpty()) {
                            Button(
                                onClick = {
                                    val r = ridersInRoom.firstOrNull { it.riderId == selectedRiderId } ?: return@Button
                                    if (isSingleColorMode) {
                                        viewModel.assignSingleColorToRider(r.riderId, r.riderName, latestSamples.first())
                                    } else {
                                        viewModel.assignWarmupColorSampleToRider(r.riderId, r.riderName, latestSamples)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = mx.goldAccent,
                                    contentColor = contrastTextOn(mx.goldAccent)
                                ),
                                modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("director_assign_color_btn")
                            ) {
                                Text(
                                    if (isSingleColorMode) (if (strings.isSpanish) "Fijar 1 Color Único" else "Lock Single Color")
                                    else strings.addColorSample
                                )
                            }
                        }
                    }

                    state.capturedPasses.forEachIndexed { idx, cap ->
                        GlassRaceCard {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (strings.isSpanish) "Captura #${idx + 1} (${cap.antennaId})" else "Capture #${idx + 1} (${cap.antennaId})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = mx.goldBright
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        cap.samples.forEach { sample ->
                                            val c = try { Color(android.graphics.Color.parseColor(sample.hex)) } catch (_: Exception) { Color.White }
                                            Box(
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(c)
                                                    .border(1.dp, Color.White, RoundedCornerShape(6.dp))
                                                    .clickable {
                                                        val r = ridersInRoom.firstOrNull { it.riderId == selectedRiderId }
                                                        if (r != null) {
                                                            viewModel.assignSingleColorToRider(r.riderId, r.riderName, sample)
                                                        }
                                                    }
                                            )
                                        }
                                    }
                                }
                                Text(
                                    cap.samples.joinToString(" • ") { it.colorName },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = mx.subtleText
                                )
                                if (ridersInRoom.isNotEmpty()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                        if (!isSingleColorMode) {
                                            Button(
                                                onClick = {
                                                    val r = ridersInRoom.firstOrNull { it.riderId == selectedRiderId } ?: return@Button
                                                    viewModel.assignWarmupColorSampleToRider(r.riderId, r.riderName, cap.samples)
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = mx.goldAccent,
                                                    contentColor = contrastTextOn(mx.goldAccent)
                                                ),
                                                modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                                            ) {
                                                Text("${strings.addColorSample} (#${idx + 1})", maxLines = 1)
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                val r = ridersInRoom.firstOrNull { it.riderId == selectedRiderId } ?: return@OutlinedButton
                                                cap.samples.firstOrNull()?.let { firstColor ->
                                                    viewModel.assignSingleColorToRider(r.riderId, r.riderName, firstColor)
                                                }
                                            },
                                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                                        ) {
                                            Text(
                                                if (strings.isSpanish) "Solo 1 Color (${cap.samples.firstOrNull()?.colorName ?: ""})"
                                                else "1 Color (${cap.samples.firstOrNull()?.colorName ?: ""})",
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Text(if (strings.isSpanish) "3. Perfiles CIE Lab Guardados por Piloto" else "3. Saved CIE Lab Profiles by Rider", style = MaterialTheme.typography.titleLarge)
        ridersInRoom.forEach { r ->
            val profile = state.colorProfiles.firstOrNull { it.riderId == r.riderId }
            val confirmed = profile?.confirmed == true || r.colorConfirmed
            GlassRaceCard {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("${r.riderName} (#${r.dorsal})", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${profile?.samples?.size ?: 0} / 12 muestras CIE Lab",
                                style = MaterialTheme.typography.labelSmall,
                                color = mx.subtleText
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (confirmed) {
                                Icon(Icons.Default.CheckCircle, contentDescription = "Confirmed", tint = mx.telemetryGreen)
                            } else {
                                Text(if (strings.isSpanish) "Pendiente" else "Pending", style = MaterialTheme.typography.labelSmall, color = mx.goldAccent)
                            }
                            if ((profile?.samples?.isNotEmpty() == true)) {
                                IconButton(onClick = { viewModel.clearRiderColorProfile(r.riderId, r.riderName) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Clear", tint = mx.racingRed)
                                }
                            }
                        }
                    }
                    if (!profile?.samples.isNullOrEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            profile!!.samples.forEach { s ->
                                val c = try { Color(android.graphics.Color.parseColor(s.hex)) } catch (_: Exception) { Color.White }
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(c)
                                        .border(1.dp, Color.White, RoundedCornerShape(4.dp))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DirectorClubAdminTab(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel
) {
    val mx = LocalMxColors.current
    var pendingDeleteRider by remember { mutableStateOf<RiderAccount?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassRaceCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (strings.isSpanish) "Permitir Nuevos Registros" else "Allow New Registrations",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (state.globalConfig.registrationOpen) (if (strings.isSpanish) "Abierto" else "Open")
                        else (if (strings.isSpanish) "Cerrado" else "Closed"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.globalConfig.registrationOpen) mx.telemetryGreen else mx.racingRed
                    )
                }
                Switch(
                    checked = state.globalConfig.registrationOpen,
                    onCheckedChange = { viewModel.toggleGlobalRegistrationOpen() }
                )
            }
        }

        GlassRaceCard {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "${if (strings.isSpanish) "Visitas totales" else "Total visits"}: ${state.globalConfig.visitCount}",
                    style = MaterialTheme.typography.titleMedium,
                    color = mx.goldAccent
                )
                state.globalConfig.lastVisits.take(5).forEach { v ->
                    Text("• $v", style = MaterialTheme.typography.labelSmall, color = mx.subtleText)
                }
            }
        }

        Text(
            if (strings.isSpanish) "Cuentas de Pilotos (${state.riders.size})" else "Rider Accounts (${state.riders.size})",
            style = MaterialTheme.typography.titleLarge
        )
        state.riders.forEach { rider ->
            val isBlocked = rider.status == RiderStatus.BLOCKED
            GlassRaceCard(
                accentBorder = if (isBlocked) mx.racingRed.copy(alpha = 0.7f) else null
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rider.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${if (isBlocked) (if (strings.isSpanish) "BLOQUEADA" else "BLOCKED") else (if (strings.isSpanish) "ACTIVA" else "ACTIVE")} • ${rider.totalLaps} laps",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isBlocked) mx.racingRed else mx.telemetryGreen
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.toggleRiderStatus(rider) },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("admin_block_${rider.id}")
                        ) {
                            Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (isBlocked) mx.telemetryGreen else mx.goldAccent)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (isBlocked) (if (strings.isSpanish) "Activar" else "Unblock")
                                else (if (strings.isSpanish) "Bloquear" else "Block")
                            )
                        }
                        IconButton(
                            onClick = { pendingDeleteRider = rider },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("admin_delete_${rider.id}")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = mx.racingRed)
                        }
                    }
                }
            }
        }

        Text(if (strings.isSpanish) "Buzón de Reportes (${state.reports.size})" else "Report Inbox (${state.reports.size})", style = MaterialTheme.typography.titleLarge)
        state.reports.forEach { rep ->
            GlassRaceCard {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("[${rep.type}] ${rep.senderName}", style = MaterialTheme.typography.titleMedium, color = mx.goldAccent)
                    Text(rep.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    pendingDeleteRider?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteRider = null },
            title = { Text(if (strings.isSpanish) "Eliminar Cuenta" else "Delete Account") },
            text = {
                Text(
                    if (strings.isSpanish) "¿Borrar definitivamente la cuenta de '${target.name}' y sus motos por mal uso?"
                    else "Permanently delete '${target.name}' and all their bikes?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteRiderAccount(target.id)
                        pendingDeleteRider = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = mx.racingRed, contentColor = Color.White)
                ) {
                    Text(if (strings.isSpanish) "Eliminar" else "Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRider = null }) {
                    Text(if (strings.isSpanish) "Cancelar" else "Cancel")
                }
            }
        )
    }
}

