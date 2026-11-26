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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.model.AntennaStation
import com.example.data.model.DetectionMode
import com.example.data.model.RiderAccount
import com.example.data.model.RiderStatus
import com.example.data.model.SessionPhase
import com.example.i18n.MxStrings
import com.example.ui.components.GlassRaceCard
import com.example.ui.components.Mx3dButton
import com.example.ui.components.Mx3dOutlinedButton
import com.example.ui.theme.LocalMxColors
import com.example.ui.theme.contrastTextOn
import com.example.ui.theme.mxChipColors
import com.example.ui.theme.mxSwitchColors
import com.example.ui.theme.mxTextFieldColors
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
                    Mx3dButton(
                        onClick = { viewModel.stopSession() },
                        containerColor = mx.racingRed,
                        contentColor = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (strings.isSpanish) "STOP WARM-UP (Pilotos a Parrilla)" else "STOP WARM-UP (Riders to Grid)")
                    }
                } else {
                    Mx3dButton(
                        onClick = { viewModel.startWarmup(friendly = false) },
                        containerColor = mx.goldAccent,
                        contentColor = contrastTextOn(mx.goldAccent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (strings.isSpanish) "Iniciar Warm-Up (3:00)" else "Start Warm-Up (3:00)")
                    }
                }

                Text(
                    if (strings.isSpanish) "Modo de Captura de Color (Antena 1)" else "Color Capture Mode (Antenna 1)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
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
                        label = { Text(if (strings.isSpanish) "Multi-Captura" else "Multi-Color") },
                        colors = mxChipColors()
                    )
                    FilterChip(
                        selected = isSingleColorMode,
                        onClick = {
                            viewModel.updateRemoteAntennaConfig(
                                AntennaStation.ANTENA_1.id,
                                detectionMode = DetectionMode.SINGLE_COLOR
                            )
                        },
                        label = { Text(if (strings.isSpanish) "Solo 1 Color" else "Single Color") },
                        colors = mxChipColors()
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
                        label = { Text("${r.riderName} #${r.dorsal}") },
                        colors = mxChipColors()
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
                            Mx3dButton(
                                onClick = {
                                    val r = ridersInRoom.firstOrNull { it.riderId == selectedRiderId } ?: return@Mx3dButton
                                    if (isSingleColorMode) {
                                        viewModel.assignSingleColorToRider(r.riderId, r.riderName, latestSamples.first())
                                    } else {
                                        viewModel.assignWarmupColorSampleToRider(r.riderId, r.riderName, latestSamples)
                                    }
                                },
                                containerColor = mx.goldAccent,
                                contentColor = contrastTextOn(mx.goldAccent),
                                modifier = Modifier.fillMaxWidth().testTag("director_assign_color_btn")
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
                                        text = (if (strings.isSpanish) "Captura #${idx + 1} (${cap.antennaId})" else "Capture #${idx + 1} (${cap.antennaId})") +
                                            if (cap.detectedDorsal.isNotBlank()) " • #${cap.detectedDorsal}" else "",
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
                                            Mx3dButton(
                                                onClick = {
                                                    val r = ridersInRoom.firstOrNull { it.riderId == selectedRiderId } ?: return@Mx3dButton
                                                    viewModel.assignWarmupColorSampleToRider(r.riderId, r.riderName, cap.samples)
                                                },
                                                containerColor = mx.goldAccent,
                                                contentColor = contrastTextOn(mx.goldAccent),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("${strings.addColorSample} (#${idx + 1})", maxLines = 1)
                                            }
                                        }
                                        Mx3dOutlinedButton(
                                            onClick = {
                                                val r = ridersInRoom.firstOrNull { it.riderId == selectedRiderId } ?: return@Mx3dOutlinedButton
                                                cap.samples.firstOrNull()?.let { firstColor ->
                                                    viewModel.assignSingleColorToRider(r.riderId, r.riderName, firstColor)
                                                }
                                            },
                                            borderColor = mx.smBadge,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                if (strings.isSpanish) "Solo 1 Color (${cap.samples.firstOrNull()?.colorName ?: ""})"
                                                else "1 Color (${cap.samples.firstOrNull()?.colorName ?: ""})",
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                                if (cap.snapshotBase64.isNotBlank()) {
                                    Mx3dOutlinedButton(
                                        enabled = !state.aiStewardBusy,
                                        onClick = {
                                            viewModel.inspectPhotoWithAiSteward(
                                                photoBase64 = cap.snapshotBase64,
                                                detectedSamples = cap.samples,
                                                ocrDorsalHint = cap.detectedDorsal,
                                                autoAssignWarmupSample = true
                                            )
                                        },
                                        borderColor = mx.hotlapPurple,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            when {
                                                state.aiStewardBusy -> if (strings.isSpanish) "Analizando con Comisario IA..." else "AI Steward Analyzing..."
                                                strings.isSpanish -> "✨ Auto-Identificar Piloto con IA"
                                                else -> "✨ Auto-Identify Rider with AI"
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Text(
            if (strings.isSpanish) "3. Perfiles CIE Lab Guardados por Piloto" else "3. Saved CIE Lab Profiles by Rider",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
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
                            Text("${r.riderName} (#${r.dorsal})", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
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
    val clipboard = LocalClipboardManager.current
    var pendingDeleteRider by remember { mutableStateOf<RiderAccount?>(null) }
    var newDirPass by remember { mutableStateOf("") }
    var customCode by remember { mutableStateOf("") }
    var codeCircuit by remember { mutableStateOf("") }
    var codeNote by remember { mutableStateOf("") }
    var codePromoteOfficial by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Registrations toggle
        GlassRaceCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (strings.isSpanish) "Permitir Nuevos Registros" else "Allow New Registrations",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
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
                    onCheckedChange = { viewModel.toggleGlobalRegistrationOpen() },
                    colors = mxSwitchColors()
                )
            }
        }

        // 2. APK Download & Install Lock + Custom Code Generator
        GlassRaceCard(accentBorder = if (state.globalConfig.apkDownloadLocked) mx.racingRed else mx.goldAccent.copy(alpha = 0.55f)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = if (state.globalConfig.apkDownloadLocked) mx.racingRed else mx.telemetryGreen, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (strings.isSpanish) "Bloquear Descarga e Instalación APK sin Código" else "Lock APK Download & Install Without Code",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            if (state.globalConfig.apkDownloadLocked) {
                                if (strings.isSpanish) "Bloqueado: exige código emitido por Dirección"
                                else "Locked: requires code issued by Director"
                            } else {
                                if (strings.isSpanish) "Abierto al público general"
                                else "Open to public"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.globalConfig.apkDownloadLocked) mx.racingRed else mx.telemetryGreen
                        )
                    }
                    Switch(
                        checked = state.globalConfig.apkDownloadLocked,
                        onCheckedChange = { viewModel.toggleApkDownloadLocked() },
                        colors = mxSwitchColors(),
                        modifier = Modifier.testTag("admin_apk_lock_switch")
                    )
                }

                Text(
                    if (strings.isSpanish) "Emitir Código Personalizado (Descarga / Instalación / Circuito Oficial)"
                    else "Issue Custom Code (Download / Install / Official Circuit)",
                    style = MaterialTheme.typography.labelLarge,
                    color = mx.goldAccent
                )
                OutlinedTextField(
                    value = customCode,
                    onValueChange = { customCode = it.uppercase() },
                    label = { Text(if (strings.isSpanish) "Código (vacío = aleatorio)" else "Custom code (blank = random)") },
                    singleLine = true,
                    colors = mxTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("admin_custom_code_input")
                )
                OutlinedTextField(
                    value = codeCircuit,
                    onValueChange = { codeCircuit = it },
                    label = { Text(if (strings.isSpanish) "Nombre del Circuito" else "Circuit Name") },
                    singleLine = true,
                    colors = mxTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("admin_code_circuit_input")
                )
                OutlinedTextField(
                    value = codeNote,
                    onValueChange = { codeNote = it },
                    label = { Text(if (strings.isSpanish) "Ciudad / Nota (opcional)" else "City / Note (optional)") },
                    singleLine = true,
                    colors = mxTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (strings.isSpanish) "Convertir en Circuito Oficial al canjear"
                        else "Promote to Official Circuit on redeem",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = codePromoteOfficial,
                        onCheckedChange = { codePromoteOfficial = it },
                        colors = mxSwitchColors()
                    )
                }
                Mx3dButton(
                    onClick = {
                        viewModel.createInviteDownloadCode(
                            rawCode = customCode,
                            circuitName = codeCircuit,
                            note = codeNote,
                            promoteOfficial = codePromoteOfficial
                        )
                        customCode = ""
                        codeNote = ""
                    },
                    containerColor = mx.goldAccent,
                    contentColor = contrastTextOn(mx.goldAccent),
                    modifier = Modifier.fillMaxWidth().testTag("admin_issue_code_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (strings.isSpanish) "Emitir Código" else "Issue Code")
                }

                val codesList = state.globalConfig.accessCodes.values.sortedByDescending { it.createdAtMs }
                if (codesList.isNotEmpty()) {
                    Text(
                        "${if (strings.isSpanish) "Códigos Emitidos" else "Issued Codes"} (${codesList.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = mx.goldBright
                    )
                    codesList.forEach { entry ->
                        GlassRaceCard {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${entry.code} • ${if (entry.active) "ACTIVO" else "ANULADO"}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (entry.active) mx.telemetryGreen else mx.racingRed
                                    )
                                    Text(
                                        "${entry.circuitName}${if (entry.promoteOfficial) " ★ Oficial" else ""} • Usos: ${entry.redeemedCount}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = mx.subtleText
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { clipboard.setText(AnnotatedString(entry.code)) }) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = mx.goldAccent)
                                    }
                                    IconButton(onClick = { viewModel.toggleInviteCodeActive(entry.code) }) {
                                        Icon(
                                            Icons.Default.Block,
                                            contentDescription = "Toggle",
                                            tint = if (entry.active) mx.goldAccent else mx.telemetryGreen
                                        )
                                    }
                                    IconButton(onClick = { viewModel.deleteInviteCode(entry.code) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = mx.racingRed)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Change Director Access Password
        GlassRaceCard {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = mx.goldAccent)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (strings.isSpanish) "Cambiar Clave de Dirección" else "Change Director Password",
                        style = MaterialTheme.typography.titleMedium,
                        color = mx.goldAccent
                    )
                }
                OutlinedTextField(
                    value = newDirPass,
                    onValueChange = { newDirPass = it },
                    label = { Text(if (strings.isSpanish) "Nueva contraseña (mín. 4 caracteres)" else "New password (min 4 chars)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = mxTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("admin_change_dir_pwd_input")
                )
                Mx3dButton(
                    onClick = {
                        viewModel.changeDirectorPassword(newDirPass)
                        newDirPass = ""
                    },
                    containerColor = mx.smBadge,
                    contentColor = contrastTextOn(mx.smBadge),
                    modifier = Modifier.fillMaxWidth().testTag("admin_save_dir_pwd_btn")
                ) {
                    Text(if (strings.isSpanish) "Guardar Nueva Clave de Dirección" else "Save New Director Password")
                }
            }
        }

        // 4. Visits counter
        GlassRaceCard {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${if (strings.isSpanish) "Visitas totales" else "Total visits"}: ${state.globalConfig.visitCount}",
                        style = MaterialTheme.typography.titleMedium,
                        color = mx.goldAccent
                    )
                    Mx3dOutlinedButton(
                        onClick = { viewModel.refreshDirectorDashboard() },
                        borderColor = mx.smBadge,
                        modifier = Modifier.width(120.dp)
                    ) {
                        Text(if (strings.isSpanish) "Actualizar" else "Refresh", maxLines = 1)
                    }
                }
                if (state.globalConfig.lastVisits.isEmpty()) {
                    Text(
                        if (strings.isSpanish) "Sin historial reciente" else "No recent visits yet",
                        style = MaterialTheme.typography.labelSmall,
                        color = mx.subtleText
                    )
                } else {
                    state.globalConfig.lastVisits.take(5).forEach { v ->
                        Text("• $v", style = MaterialTheme.typography.labelSmall, color = mx.subtleText)
                    }
                }
            }
        }

        // 5. Rider accounts
        Text(
            if (strings.isSpanish) "Cuentas de Pilotos (${state.riders.size})" else "Rider Accounts (${state.riders.size})",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (state.riders.isEmpty()) {
            GlassRaceCard {
                Text(
                    if (strings.isSpanish) "Aún no hay cuentas de piloto registradas en la base de datos."
                    else "No rider accounts registered yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = mx.subtleText,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
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
                        Text(rider.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            text = "${if (isBlocked) (if (strings.isSpanish) "BLOQUEADA" else "BLOCKED") else (if (strings.isSpanish) "ACTIVA" else "ACTIVE")} • ${rider.totalLaps} laps",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isBlocked) mx.racingRed else mx.telemetryGreen
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Mx3dOutlinedButton(
                            onClick = { viewModel.toggleRiderStatus(rider) },
                            borderColor = if (isBlocked) mx.telemetryGreen else mx.goldAccent,
                            modifier = Modifier.width(118.dp).testTag("admin_block_${rider.id}")
                        ) {
                            Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (isBlocked) mx.telemetryGreen else mx.goldAccent)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (isBlocked) (if (strings.isSpanish) "Activar" else "Unblock")
                                else (if (strings.isSpanish) "Bloquear" else "Block"),
                                maxLines = 1
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

        // 6. Buzón Inbox (immutable club backend route; no email UI field)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Mail, contentDescription = null, tint = mx.goldAccent)
            Spacer(Modifier.width(6.dp))
            Text(
                if (strings.isSpanish) "Buzón y Solicitudes de Circuito Oficial (${state.reports.size})"
                else "Inbox & Official Circuit Requests (${state.reports.size})",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        state.reports.forEach { rep ->
            val isOfficialReq = rep.type == "OFFICIAL_REQUEST"
            GlassRaceCard(accentBorder = if (isOfficialReq) mx.goldAccent.copy(alpha = 0.65f) else null) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "[${if (isOfficialReq) "SOLICITUD OFICIAL" else rep.type}] ${rep.senderName}",
                        style = MaterialTheme.typography.titleMedium,
                        color = mx.goldAccent
                    )
                    if (rep.circuitRequested.isNotBlank()) {
                        Text(
                            text = "${if (strings.isSpanish) "Circuito solicitado" else "Requested circuit"}: ${rep.circuitRequested}",
                            style = MaterialTheme.typography.labelMedium,
                            color = mx.telemetryGreen
                        )
                    }
                    Text(rep.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (isOfficialReq) {
                        Mx3dOutlinedButton(
                            onClick = {
                                codeCircuit = rep.circuitRequested.ifBlank { rep.senderName }
                                codePromoteOfficial = true
                                viewModel.createInviteDownloadCode(
                                    rawCode = "",
                                    circuitName = codeCircuit,
                                    note = rep.senderName,
                                    promoteOfficial = true
                                )
                            },
                            borderColor = mx.telemetryGreen,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (strings.isSpanish) "Emitir Código Oficial Ahora" else "Issue Official Code Now")
                        }
                    }
                }
            }
        }
    }

    pendingDeleteRider?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteRider = null },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(if (strings.isSpanish) "Eliminar Cuenta" else "Delete Account", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    if (strings.isSpanish) "¿Borrar definitivamente la cuenta de '${target.name}' y sus motos por mal uso?"
                    else "Permanently delete '${target.name}' and all their bikes?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Mx3dButton(
                    onClick = {
                        viewModel.deleteRiderAccount(target.id)
                        pendingDeleteRider = null
                    },
                    containerColor = mx.racingRed,
                    contentColor = Color.White
                ) {
                    Text(if (strings.isSpanish) "Eliminar" else "Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRider = null }) {
                    Text(if (strings.isSpanish) "Cancelar" else "Cancel", color = mx.subtleText)
                }
            }
        )
    }
}

