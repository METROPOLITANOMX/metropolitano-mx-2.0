package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BikeSetup
import com.example.data.model.LayoutType
import com.example.data.model.RiderStatus
import com.example.data.model.SurfaceType
import com.example.data.model.formatLapMs
import com.example.i18n.MxStrings
import com.example.ui.components.Base64ImageView
import com.example.ui.components.GlassRaceCard
import com.example.ui.components.Mx3dButton
import com.example.ui.components.RiderDorsalBadge
import com.example.ui.components.SurfaceBadge
import com.example.ui.theme.LocalMxColors
import com.example.ui.theme.contrastTextOn
import com.example.ui.theme.mxChipColors
import com.example.ui.theme.mxTextFieldColors
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
    val mx = LocalMxColors.current
    var tabIndex by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf(state.rememberedName) }
    var pass by remember { mutableStateOf(state.rememberedPass) }
    var pin4 by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(true) }
    val activeRidersList = state.riders.filter { it.status == RiderStatus.ACTIVE }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(strings.iamPilot, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                TabRow(
                    selectedTabIndex = tabIndex,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = mx.goldAccent
                ) {
                    Tab(
                        selected = tabIndex == 0,
                        onClick = { tabIndex = 0 },
                        selectedContentColor = mx.goldAccent,
                        unselectedContentColor = mx.subtleText,
                        text = { Text(strings.pilotSignIn) }
                    )
                    Tab(
                        selected = tabIndex == 1,
                        onClick = { tabIndex = 1 },
                        selectedContentColor = mx.goldAccent,
                        unselectedContentColor = mx.subtleText,
                        text = { Text(strings.pilotRegister) }
                    )
                    Tab(
                        selected = tabIndex == 2,
                        onClick = { tabIndex = 2 },
                        selectedContentColor = mx.goldAccent,
                        unselectedContentColor = mx.subtleText,
                        text = { Text(strings.recoverPin) }
                    )
                }
                Spacer(Modifier.height(10.dp))

                if (tabIndex == 0 && activeRidersList.isNotEmpty()) {
                    Text(
                        if (strings.isSpanish) "¿Ya tienes cuenta? Toca tu piloto:" else "Existing account? Tap your name:",
                        style = MaterialTheme.typography.labelSmall,
                        color = mx.goldAccent
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(activeRidersList.take(12)) { r ->
                            FilterChip(
                                selected = name.equals(r.name, ignoreCase = true),
                                onClick = { name = r.name },
                                label = { Text(r.name) },
                                colors = mxChipColors()
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.pilotName) },
                    singleLine = true,
                    colors = mxTextFieldColors(),
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
                        colors = mxTextFieldColors(),
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
                        colors = mxTextFieldColors(),
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
                        colors = mxTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (tabIndex != 2) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = rememberMe,
                            onCheckedChange = { rememberMe = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = mx.goldAccent,
                                checkmarkColor = contrastTextOn(mx.goldAccent),
                                uncheckedColor = mx.subtleText
                            )
                        )
                        Text(strings.rememberMe, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (tabIndex == 1 && !state.globalConfig.registrationOpen) {
                    Text(
                        text = strings.registrationsClosed,
                        color = mx.racingRed,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Mx3dButton(
                onClick = {
                    when (tabIndex) {
                        0 -> viewModel.signInRider(name, pass, rememberMe, onAuthenticated)
                        1 -> viewModel.registerRider(name, pass, pin4, rememberMe, onAuthenticated)
                        else -> viewModel.recoverPasswordWithPin(name, pin4, pass)
                    }
                },
                containerColor = mx.goldAccent,
                contentColor = contrastTextOn(mx.goldAccent),
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
            TextButton(onClick = onDismiss) {
                Text(if (strings.isSpanish) "Cancelar" else "Cancel", color = mx.subtleText)
            }
        }
    )
}

@Composable
fun DirectorUnlockDialog(
    state: MxUiState,
    strings: MxStrings,
    onDismiss: () -> Unit,
    onAttemptUnlock: (String, Boolean) -> Boolean
) {
    val mx = LocalMxColors.current
    var pwd by remember { mutableStateOf(state.rememberedDirectorPass) }
    var rememberCreds by remember { mutableStateOf(state.rememberDirectorPass) }
    var wrong by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(strings.directorAccess, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(
                    text = if (strings.isSpanish) {
                        "Dirección"
                    } else {
                        "Director"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = mx.subtleText
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it; wrong = false },
                    label = { Text(strings.password) },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = wrong,
                    singleLine = true,
                    colors = mxTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("director_pwd_input")
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = rememberCreds,
                        onCheckedChange = { rememberCreds = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = mx.goldAccent,
                            checkmarkColor = contrastTextOn(mx.goldAccent),
                            uncheckedColor = mx.subtleText
                        )
                    )
                    Text(
                        if (strings.isSpanish) "Guardar contraseña" else "Save password",
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            Mx3dButton(
                onClick = { if (!onAttemptUnlock(pwd, rememberCreds)) wrong = true },
                containerColor = mx.goldAccent,
                contentColor = contrastTextOn(mx.goldAccent),
                modifier = Modifier.testTag("director_unlock_button")
            ) { Text(if (strings.isSpanish) "Entrar" else "Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (strings.isSpanish) "Cerrar" else "Close", color = mx.subtleText) } }
    )
}

@Composable
fun CreateCircuitDialog(
    strings: MxStrings,
    onDismiss: () -> Unit,
    onSave: (String, String, SurfaceType, Int, LayoutType) -> Unit
) {
    val mx = LocalMxColors.current
    var name by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var surface by remember { mutableStateOf(SurfaceType.MX) }
    var lengthStr by remember { mutableStateOf("180") }
    var layout by remember { mutableStateOf(LayoutType.MIXED) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(strings.createLocalCircuit, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.circuitName) },
                    singleLine = true,
                    colors = mxTextFieldColors()
                )
                OutlinedTextField(
                    value = city,
                    onValueChange = { city = it },
                    label = { Text(strings.countryCity) },
                    singleLine = true,
                    colors = mxTextFieldColors()
                )
                OutlinedTextField(
                    value = lengthStr,
                    onValueChange = { lengthStr = it.filter(Char::isDigit) },
                    label = { Text(strings.lengthMeters) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = mxTextFieldColors()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = surface == SurfaceType.MX, onClick = { surface = SurfaceType.MX }, label = { Text("MX Tierra") }, colors = mxChipColors())
                    FilterChip(selected = surface == SurfaceType.SM, onClick = { surface = SurfaceType.SM }, label = { Text("SM Asfalto") }, colors = mxChipColors())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LayoutType.entries.forEach { lt ->
                        FilterChip(selected = layout == lt, onClick = { layout = lt }, label = { Text(lt.name) }, colors = mxChipColors())
                    }
                }
            }
        },
        confirmButton = {
            Mx3dButton(
                onClick = { onSave(name, city, surface, lengthStr.toIntOrNull() ?: 180, layout) },
                containerColor = mx.goldAccent,
                contentColor = contrastTextOn(mx.goldAccent)
            ) {
                Text(strings.saveCircuit)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (strings.isSpanish) "Cancelar" else "Cancel", color = mx.subtleText) } }
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
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(strings.worldRankingPaddock, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                ScrollableTabRow(
                    selectedTabIndex = tab,
                    edgePadding = 4.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = mx.goldAccent
                ) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, selectedContentColor = mx.goldAccent, unselectedContentColor = mx.subtleText, text = { Text("Ranking") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, selectedContentColor = mx.goldAccent, unselectedContentColor = mx.subtleText, text = { Text("Paddock") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, selectedContentColor = mx.goldAccent, unselectedContentColor = mx.subtleText, text = { Text(if (strings.isSpanish) "Retos MX/SM" else "Challenges") })
                    Tab(selected = tab == 3, onClick = { tab = 3 }, selectedContentColor = mx.goldAccent, unselectedContentColor = mx.subtleText, text = { Text(if (strings.isSpanish) "Copas" else "Champs") })
                }
                Spacer(Modifier.height(8.dp))
                if (tab == 0 || tab == 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = surfaceFilter == null, onClick = { surfaceFilter = null }, label = { Text(if (strings.isSpanish) "Todas" else "All") }, colors = mxChipColors())
                        FilterChip(selected = surfaceFilter == SurfaceType.MX, onClick = { surfaceFilter = SurfaceType.MX }, label = { Text("MX Tierra") }, colors = mxChipColors())
                        FilterChip(selected = surfaceFilter == SurfaceType.SM, onClick = { surfaceFilter = SurfaceType.SM }, label = { Text("SM Supermotard") }, colors = mxChipColors())
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
                                                Mx3dButton(
                                                    onClick = {
                                                        when {
                                                            compareA == null -> compareA = b
                                                            compareA?.id == b.id -> compareA = null
                                                            else -> compareB = b
                                                        }
                                                    },
                                                    containerColor = if (compareA?.id == b.id || compareB?.id == b.id) mx.telemetryGreen else mx.smBadge,
                                                    contentColor = contrastTextOn(if (compareA?.id == b.id || compareB?.id == b.id) mx.telemetryGreen else mx.smBadge),
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
    val mx = LocalMxColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(strings.guideAndInstall, style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                text = if (strings.isSpanish) {
                    "1. Úsala en cualquier sitio: descampado, parking, el jardín de tu casa o circuito oficial para cronometrar tu Losi Promoto.\n" +
                        "2. Coloca un teléfono como Antena (Solo o con Dirección) apuntando a tu línea de paso.\n" +
                        "3. Calibra pista vacía en Antena o desde Dirección y elige Cualquier Paso, 1 Color o Multi-Color CIE.\n" +
                        "4. Rueda en Sala Pista con Modo Bolsillo (altavoz o cascos) mientras Dirección y boxes ven los tiempos en vivo.\n" +
                        "5. Instalación desde Navegador Web: Android muestra aviso en cualquier APK fuera de Google Play Store. Pulsa 'Descargar de todos modos' al bajar desde el portal web.\n" +
                        "6. ¿Quieres pasar a Circuito Oficial y obtener códigos? Escríbenos desde el Buzón."
                } else {
                    "1. Use anywhere: open lot, parking spot, backyard, or official MX track to time your Losi Promoto.\n" +
                        "2. Place a phone as Antenna (Solo or with Director) aimed at your finish line.\n" +
                        "3. Calibrate empty track on the Antenna or from Director, choosing Any Pass, 1 Color, or CIE Multi-Color.\n" +
                        "4. Ride in Track Room with Pocket Mode (speaker or headset) while Director & pit-crew watch live.\n" +
                        "5. Browser Web Install: Android warns on all APKs outside Google Play Store. Tap 'Download anyway' when downloading from the web portal.\n" +
                        "6. Want to register as an Official Circuit and request invite codes? Message us via Inbox."
                },
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = mx.goldAccent) } },
        dismissButton = {
            TextButton(onClick = {
                try {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(com.example.data.model.MxConstants.DEFAULT_APK_URL)
                    )
                    context.startActivity(intent)
                } catch (_: Exception) {
                }
            }) {
                Text(if (strings.isSpanish) "Abrir Web Oficial" else "Open Web Installer", color = mx.smBadge)
            }
        }
    )
}

@Composable
fun ReportInboxDialog(
    state: MxUiState,
    strings: MxStrings,
    initialType: String = "BUG",
    onDismiss: () -> Unit,
    onSubmit: (String, String, String, String) -> Unit
) {
    val mx = LocalMxColors.current
    var sender by remember { mutableStateOf(state.currentRider?.name ?: "") }
    var type by remember { mutableStateOf(initialType) }
    var circuitReq by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(strings.reportInbox, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = sender,
                    onValueChange = { sender = it },
                    label = { Text(strings.pilotName) },
                    singleLine = true,
                    colors = mxTextFieldColors()
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = type == "OFFICIAL_REQUEST",
                            onClick = { type = "OFFICIAL_REQUEST" },
                            label = { Text(if (strings.isSpanish) "Circuito Oficial / Código" else "Official Circuit / Code") },
                            colors = mxChipColors()
                        )
                    }
                    item {
                        FilterChip(
                            selected = type == "BUG",
                            onClick = { type = "BUG" },
                            label = { Text("Bug") },
                            colors = mxChipColors()
                        )
                    }
                    item {
                        FilterChip(
                            selected = type == "SUGGESTION",
                            onClick = { type = "SUGGESTION" },
                            label = { Text(if (strings.isSpanish) "Sugerencia" else "Suggestion") },
                            colors = mxChipColors()
                        )
                    }
                }
                if (type == "OFFICIAL_REQUEST") {
                    OutlinedTextField(
                        value = circuitReq,
                        onValueChange = { circuitReq = it },
                        label = { Text(if (strings.isSpanish) "Nombre de tu Circuito / Club" else "Your Circuit / Club Name") },
                        singleLine = true,
                        colors = mxTextFieldColors()
                    )
                }
                OutlinedTextField(
                    value = msg,
                    onValueChange = { msg = it },
                    label = {
                        Text(
                            if (type == "OFFICIAL_REQUEST") {
                                if (strings.isSpanish) "Ubicación y solicitud de código / acceso" else "Location & code/official request"
                            } else {
                                if (strings.isSpanish) "Descripción" else "Details"
                            }
                        )
                    },
                    minLines = 3,
                    colors = mxTextFieldColors()
                )
            }
        },
        confirmButton = {
            Mx3dButton(
                onClick = { onSubmit(sender, type, msg, circuitReq) },
                containerColor = mx.goldAccent,
                contentColor = contrastTextOn(mx.goldAccent)
            ) {
                Text(if (strings.isSpanish) "Enviar" else "Send")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (strings.isSpanish) "Cancelar" else "Cancel", color = mx.subtleText) } }
    )
}
