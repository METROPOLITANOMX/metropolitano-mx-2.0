package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.example.R
import com.example.data.model.BikeSetup
import com.example.data.model.LayoutType
import com.example.data.model.SurfaceType
import com.example.data.model.formatLapMs
import com.example.i18n.MxStrings
import com.example.ui.components.Base64ImageView
import com.example.ui.components.GlassRaceCard
import com.example.ui.components.RiderDorsalBadge
import com.example.ui.components.SurfaceBadge
import com.example.ui.theme.LocalMxColors
import com.example.ui.theme.contrastTextOn
import com.example.ui.viewmodel.MxProViewModel
import com.example.ui.viewmodel.MxUiState

@Composable
fun PilotAuthDialog(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel,
    onDismiss: () -> Unit,
    onAuthenticated: () -> Unit
) {
    var tabIndex by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf(state.rememberedName) }
    var pass by remember { mutableStateOf(state.rememberedPass) }
    var pin4 by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(state.rememberMe) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.iamPilot) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                TabRow(selectedTabIndex = tabIndex) {
                    Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text(strings.pilotSignIn) })
                    Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text(strings.pilotRegister) })
                    Tab(selected = tabIndex == 2, onClick = { tabIndex = 2 }, text = { Text(strings.recoverPin) })
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.pilotName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("auth_name_input")
                )
                Spacer(Modifier.height(8.dp))
                if (tabIndex == 0 || tabIndex == 1) {
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text(strings.password) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("auth_pass_input")
                    )
                }
                if (tabIndex == 1 || tabIndex == 2) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pin4,
                        onValueChange = { if (it.length <= 4) pin4 = it.filter(Char::isDigit) },
                        label = { Text(strings.pin4Digits) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().testTag("auth_pin_input")
                    )
                }
                if (tabIndex == 2) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text(strings.newPassword) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (tabIndex != 2) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it })
                        Text(strings.rememberMe)
                    }
                }
                if (tabIndex == 1 && !state.globalConfig.registrationOpen) {
                    Text(
                        text = strings.registrationsClosed,
                        color = LocalMxColors.current.racingRed,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (tabIndex) {
                        0 -> viewModel.signInRider(name, pass, rememberMe, onAuthenticated)
                        1 -> viewModel.registerRider(name, pass, pin4, rememberMe, onAuthenticated)
                        else -> viewModel.recoverPasswordWithPin(name, pin4, pass)
                    }
                },
                modifier = Modifier.testTag("auth_submit_button")
            ) {
                Text(
                    when (tabIndex) {
                        0 -> strings.enterTrack
                        1 -> strings.registerBtn
                        else -> strings.recoverBtn
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (strings.isSpanish) "Cancelar" else "Cancel") }
        }
    )
}

@Composable
fun DirectorUnlockDialog(
    strings: MxStrings,
    onDismiss: () -> Unit,
    onAttemptUnlock: (String) -> Boolean
) {
    var pwd by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.directorConsole) },
        text = {
            Column {
                Text(
                    text = if (strings.isSpanish) {
                        "Acceso protegido para organizador del circuito (clave por defecto: MXPRO2026)."
                    } else {
                        "Organizer protected access (default key: MXPRO2026)."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it; wrong = false },
                    label = { Text(strings.password) },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = wrong,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("director_pwd_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (!onAttemptUnlock(pwd)) wrong = true },
                modifier = Modifier.testTag("director_unlock_button")
            ) { Text(if (strings.isSpanish) "Entrar" else "Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (strings.isSpanish) "Cerrar" else "Close") } }
    )
}

@Composable
fun CreateCircuitDialog(
    strings: MxStrings,
    onDismiss: () -> Unit,
    onSave: (String, String, SurfaceType, Int, LayoutType) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var surface by remember { mutableStateOf(SurfaceType.MX) }
    var lengthStr by remember { mutableStateOf("180") }
    var layout by remember { mutableStateOf(LayoutType.MIXED) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.createLocalCircuit) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(strings.circuitName) }, singleLine = true)
                OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text(strings.countryCity) }, singleLine = true)
                OutlinedTextField(
                    value = lengthStr,
                    onValueChange = { lengthStr = it.filter(Char::isDigit) },
                    label = { Text(strings.lengthMeters) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = surface == SurfaceType.MX, onClick = { surface = SurfaceType.MX }, label = { Text("MX Tierra") })
                    FilterChip(selected = surface == SurfaceType.SM, onClick = { surface = SurfaceType.SM }, label = { Text("SM Asfalto") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LayoutType.entries.forEach { lt ->
                        FilterChip(selected = layout == lt, onClick = { layout = lt }, label = { Text(lt.name) })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, city, surface, lengthStr.toIntOrNull() ?: 180, layout) }) {
                Text(strings.saveCircuit)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (strings.isSpanish) "Cancelar" else "Cancel") } }
    )
}

@Composable
fun WorldRankingPaddockDialog(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var surfaceFilter by remember { mutableStateOf<SurfaceType?>(null) }
    var compareA by remember { mutableStateOf<BikeSetup?>(null) }
    var compareB by remember { mutableStateOf<BikeSetup?>(null) }
    val mx = LocalMxColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.worldRankingPaddock) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 4.dp) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Ranking") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Paddock") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(if (strings.isSpanish) "Retos MX/SM" else "Challenges") })
                    Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text(if (strings.isSpanish) "Copas" else "Champs") })
                }
                Spacer(Modifier.height(8.dp))
                if (tab == 0 || tab == 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = surfaceFilter == null, onClick = { surfaceFilter = null }, label = { Text(if (strings.isSpanish) "Todas" else "All") })
                        FilterChip(selected = surfaceFilter == SurfaceType.MX, onClick = { surfaceFilter = SurfaceType.MX }, label = { Text("MX Tierra") })
                        FilterChip(selected = surfaceFilter == SurfaceType.SM, onClick = { surfaceFilter = SurfaceType.SM }, label = { Text("SM Supermotard") })
                    }
                    Spacer(Modifier.height(8.dp))
                }
                when (tab) {
                    0 -> {
                        val sorted = state.riders
                            .filter { surfaceFilter == null || it.preferredSurface == surfaceFilter }
                            .sortedWith(compareByDescending<com.example.data.model.RiderAccount> { it.regularityIndex }.thenByDescending { it.avgSpeedKmh })
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(sorted) { idx, r ->
                                val dorsal = state.allBikes.firstOrNull { it.id == r.activeBikeId }?.dorsal
                                    ?: state.allBikes.firstOrNull { it.riderId == r.id }?.dorsal ?: "01"
                                GlassRaceCard {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                RiderDorsalBadge(
                                                    dorsal = dorsal,
                                                    discipline = r.preferredSurface,
                                                    position = idx + 1
                                                )
                                                Text(r.name, style = MaterialTheme.typography.titleMedium)
                                            }
                                            SurfaceBadge(surface = r.preferredSurface)
                                        }
                                        Text(
                                            text = "${strings.regularity}: ${String.format("%.1f", r.regularityIndex)}% • ${String.format("%.1f", r.avgSpeedKmh)} km/h • 200m Eq: ${formatLapMs(r.eq200mMs)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = mx.goldAccent
                                        )
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        val bikes = state.paddockBikes
                            .filter { surfaceFilter == null || it.discipline == surfaceFilter }
                            .sortedByDescending { it.setupVotes }
                        val voterKey = state.currentRider?.id ?: state.deviceId.ifBlank { "guest" }

                        if (compareA != null && compareB != null) {
                            SetupComparatorCard(
                                bikeA = compareA!!,
                                bikeB = compareB!!,
                                isSpanish = strings.isSpanish,
                                onClose = { compareA = null; compareB = null }
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        if (bikes.isEmpty()) {
                            Text(
                                if (strings.isSpanish) "Aún no hay setups públicos en esta modalidad. ¡Publica el tuyo desde Garaje Pro!"
                                else "No public setups in this class yet. Publish yours from Pro Garage!",
                                color = mx.subtleText
                            )
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(bikes) { b ->
                                    val voted = voterKey in b.votedByRiderIds
                                    GlassRaceCard {
                                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (b.photoBase64List.isNotEmpty()) {
                                                Base64ImageView(
                                                    base64 = b.photoBase64List.first(),
                                                    contentDescription = b.modelName,
                                                    modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(10.dp))
                                                )
                                            } else {
                                                Image(
                                                    painter = painterResource(
                                                        if (b.discipline == SurfaceType.MX) R.drawable.img_bike_mx else R.drawable.img_bike_sm
                                                    ),
                                                    contentDescription = b.modelName,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(10.dp))
                                                )
                                            }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("${b.modelName} #${b.dorsal}", style = MaterialTheme.typography.titleMedium)
                                                SurfaceBadge(b.discipline)
                                            }
                                            Text("${b.riderName} • ${b.terrain}", style = MaterialTheme.typography.bodyMedium, color = mx.subtleText)
                                            Text(
                                                "Horq: ${b.forkOil} (${b.forkSpring}) | Amort: ${b.shockOil} | Lean: ${b.leanBarSetting}",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                            Text(
                                                "MS6X ${b.gyroPercent}% (${b.gyroMode}) • Punch ${b.escPunch}/9 • ${b.effectivePinionSpur()}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = mx.goldAccent
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                                OutlinedButton(
                                                    onClick = { viewModel.voteBikeSetup(b) },
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Star,
                                                        contentDescription = null,
                                                        tint = if (voted) mx.goldAccent else mx.subtleText
                                                    )
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("${b.setupVotes} ${if (strings.isSpanish) "Votos" else "Votes"}")
                                                }
                                                Button(
                                                    onClick = {
                                                        when {
                                                            compareA == null -> compareA = b
                                                            compareA?.id == b.id -> compareA = null
                                                            else -> compareB = b
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = if (compareA?.id == b.id || compareB?.id == b.id) mx.telemetryGreen else mx.smBadge,
                                                        contentColor = contrastTextOn(if (compareA?.id == b.id || compareB?.id == b.id) mx.telemetryGreen else mx.smBadge)
                                                    ),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(Icons.Default.CompareArrows, contentDescription = null)
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(
                                                        when {
                                                            compareA?.id == b.id -> "Moto A ✓"
                                                            compareB?.id == b.id -> "Moto B ✓"
                                                            compareA == null -> if (strings.isSpanish) "Comparar A" else "Pick A"
                                                            else -> if (strings.isSpanish) "VS Moto B" else "Compare B"
                                                        },
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
                    2 -> {
                        val mxLaps = state.circuitLaps.filter { it.discipline == SurfaceType.MX && it.lapTimeMs > 0L }
                        val smLaps = state.circuitLaps.filter { it.discipline == SurfaceType.SM && it.lapTimeMs > 0L }
                        val myBestMx = mxLaps.filter { it.riderId == state.currentRider?.id }.minOfOrNull { it.lapTimeMs } ?: 0L
                        val myBestSm = smLaps.filter { it.riderId == state.currentRider?.id }.minOfOrNull { it.lapTimeMs } ?: 0L

                        Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassRaceCard(accentBorder = mx.mxBadge) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            if (strings.isSpanish) "RETO MOTOCROSS (MX)" else "MOTOCROSS CHALLENGE (MX)",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = mx.mxBadge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        SurfaceBadge(SurfaceType.MX)
                                    }
                                    Text(
                                        text = if (state.roomState.mxRecordMs > 0L) {
                                            "${state.roomState.mxRecordRider}: ${formatLapMs(state.roomState.mxRecordMs)}"
                                        } else {
                                            if (strings.isSpanish) "Sin récord MX aún — ¡Sé el primero!" else "No MX record yet — Be the first!"
                                        },
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = mx.goldAccent
                                    )
                                    Text(
                                        "${if (strings.isSpanish) "Tu mejor vuelta MX" else "Your best MX lap"}: ${formatLapMs(myBestMx)} • ${mxLaps.size} ${if (strings.isSpanish) "vueltas registradas" else "laps"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = mx.subtleText
                                    )
                                }
                            }

                            GlassRaceCard(accentBorder = mx.smBadge) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            if (strings.isSpanish) "RETO SUPERMOTARD (SM)" else "SUPERMOTO CHALLENGE (SM)",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = mx.smBadge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        SurfaceBadge(SurfaceType.SM)
                                    }
                                    Text(
                                        text = if (state.roomState.smRecordMs > 0L) {
                                            "${state.roomState.smRecordRider}: ${formatLapMs(state.roomState.smRecordMs)}"
                                        } else {
                                            if (strings.isSpanish) "Sin récord SM aún — ¡Estrena el asfalto!" else "No SM record yet — Set the pace!"
                                        },
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = mx.smBadge
                                    )
                                    Text(
                                        "${if (strings.isSpanish) "Tu mejor vuelta SM" else "Your best SM lap"}: ${formatLapMs(myBestSm)} • ${smLaps.size} ${if (strings.isSpanish) "vueltas registradas" else "laps"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = mx.subtleText
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        if (state.championships.isEmpty()) {
                            Text(
                                if (strings.isSpanish) "Dirección aún no ha creado campeonatos en este circuito." else "No championships created in this circuit yet.",
                                color = mx.subtleText
                            )
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(state.championships) { ch ->
                                    GlassRaceCard {
                                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("${ch.name} (${ch.completedHeats}/${ch.totalHeats})", style = MaterialTheme.typography.titleMedium, color = mx.goldAccent)
                                                SurfaceBadge(ch.discipline)
                                            }
                                            ch.standings.values.sortedByDescending { it.points }.forEachIndexed { idx, st ->
                                                Text(
                                                    "${idx + 1}. ${st.riderName} #${st.dorsal} — ${st.points} pts (${st.wins}W)",
                                                    style = MaterialTheme.typography.labelMedium
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
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

@Composable
private fun SetupComparatorCard(
    bikeA: BikeSetup,
    bikeB: BikeSetup,
    isSpanish: Boolean,
    onClose: () -> Unit
) {
    val mx = LocalMxColors.current
    val rows = listOf(
        Triple(if (isSpanish) "Modalidad" else "Class", bikeA.discipline.name, bikeB.discipline.name),
        Triple(if (isSpanish) "Horquilla" else "Fork", bikeA.forkOil, bikeB.forkOil),
        Triple(if (isSpanish) "Amortiguador" else "Shock", bikeA.shockOil, bikeB.shockOil),
        Triple("SAG / Lean", "${bikeA.rearSagMm} / ${bikeA.leanBarSetting}", "${bikeB.rearSagMm} / ${bikeB.leanBarSetting}"),
        Triple("MS6X Gyro", "${bikeA.gyroPercent}% (${bikeA.gyroMode})", "${bikeB.gyroPercent}% (${bikeB.gyroMode})"),
        Triple("ESC Punch/Drag", "${bikeA.escPunch}/9 • ${bikeA.escDragBrake}%", "${bikeB.escPunch}/9 • ${bikeB.escDragBrake}%"),
        Triple(if (isSpanish) "Desarrollo" else "Gearing", bikeA.effectivePinionSpur(), bikeB.effectivePinionSpur()),
        Triple(if (isSpanish) "Neumáticos" else "Tires", bikeA.frontTire, bikeB.frontTire)
    )
    GlassRaceCard(accentBorder = mx.smBadge) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${bikeA.riderName} #${bikeA.dorsal}  VS  ${bikeB.riderName} #${bikeB.dorsal}",
                    style = MaterialTheme.typography.titleMedium,
                    color = mx.smBadge
                )
                TextButton(onClick = onClose) { Text("✕") }
            }
            rows.forEach { (label, valA, valB) ->
                val diff = valA != valB
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (diff) mx.goldAccent.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = mx.subtleText, modifier = Modifier.weight(0.8f))
                    Text(valA, style = MaterialTheme.typography.labelSmall, color = if (diff) mx.goldBright else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1.1f))
                    Text(valB, style = MaterialTheme.typography.labelSmall, color = if (diff) mx.smBadge else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1.1f))
                }
            }
        }
    }
}

@Composable
fun AppGuideDialog(strings: MxStrings, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.guideAndInstall) },
        text = {
            Text(
                text = if (strings.isSpanish) {
                    "1. Coloca dos teléfonos en meta como Antena 1 y Antena 2 con fondo gris/negro mate.\n" +
                        "2. Desde Dirección (clave por defecto: MXPRO2026) calibra pista vacía y ajusta el Túnel ROI.\n" +
                        "3. En Warm-Up captura los colores CIE Lab de cada carenado con 2-3 pasadas.\n" +
                        "4. Los pilotos ruedan en Sala Pista (Modo Emisora o Bolsillo) y boxes usan Club Aficionados / Pit-Board."
                } else {
                    "1. Place two trackside phones at the finish line as Antenna 1 & 2 against a matte gray/black backdrop.\n" +
                        "2. In Race Director (default key: MXPRO2026), calibrate empty track and align the ROI Tunnel.\n" +
                        "3. During Warm-Up assign CIE Lab fairing colors in 2-3 passes.\n" +
                        "4. Riders race with Radio Mount or Pocket Mode while fans/pit-crew use Live Fan & Pit-Board."
                }
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

@Composable
fun ReportInboxDialog(
    state: MxUiState,
    strings: MxStrings,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String) -> Unit
) {
    var sender by remember { mutableStateOf(state.currentRider?.name ?: "") }
    var type by remember { mutableStateOf("BUG") }
    var msg by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.reportInbox) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = sender, onValueChange = { sender = it }, label = { Text(strings.pilotName) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == "BUG", onClick = { type = "BUG" }, label = { Text("Bug") })
                    FilterChip(selected = type == "SUGGESTION", onClick = { type = "SUGGESTION" }, label = { Text(if (strings.isSpanish) "Sugerencia" else "Suggestion") })
                }
                OutlinedTextField(
                    value = msg,
                    onValueChange = { msg = it },
                    label = { Text(if (strings.isSpanish) "Descripción" else "Details") },
                    minLines = 3
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(sender, type, msg) }) {
                Text(if (strings.isSpanish) "Enviar" else "Send")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (strings.isSpanish) "Cancelar" else "Cancel") } }
    )
}
