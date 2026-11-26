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
import com.example.R
import com.example.data.model.LayoutType
import com.example.data.model.SurfaceType
import com.example.data.model.formatLapMs
import com.example.i18n.MxStrings
import com.example.ui.components.Base64ImageView
import com.example.ui.components.GlassRaceCard
import com.example.ui.components.SurfaceBadge
import com.example.ui.theme.LocalMxColors
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
    onDismiss: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var surfaceFilter by remember { mutableStateOf<SurfaceType?>(null) }
    val mx = LocalMxColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.worldRankingPaddock) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Ranking") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Paddock") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(if (strings.isSpanish) "Copas" else "Champs") })
                }
                Spacer(Modifier.height(8.dp))
                if (tab != 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = surfaceFilter == null, onClick = { surfaceFilter = null }, label = { Text("All") })
                        FilterChip(selected = surfaceFilter == SurfaceType.MX, onClick = { surfaceFilter = SurfaceType.MX }, label = { Text("MX") })
                        FilterChip(selected = surfaceFilter == SurfaceType.SM, onClick = { surfaceFilter = SurfaceType.SM }, label = { Text("SM") })
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (tab == 0) {
                    val sorted = state.riders
                        .filter { surfaceFilter == null || it.preferredSurface == surfaceFilter }
                        .sortedWith(compareByDescending<com.example.data.model.RiderAccount> { it.regularityIndex }.thenByDescending { it.avgSpeedKmh })
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(sorted) { idx, r ->
                            GlassRaceCard {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("#${idx + 1} ${r.name}", style = MaterialTheme.typography.titleMedium)
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
                } else if (tab == 1) {
                    val bikes = state.paddockBikes.filter { surfaceFilter == null || it.discipline == surfaceFilter }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(bikes) { b ->
                            GlassRaceCard {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    if (b.photoBase64List.isNotEmpty()) {
                                        Base64ImageView(
                                            base64 = b.photoBase64List.first(),
                                            contentDescription = b.modelName,
                                            modifier = Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp))
                                        )
                                    } else {
                                        Image(
                                            painter = painterResource(
                                                if (b.discipline == SurfaceType.MX) R.drawable.img_bike_mx else R.drawable.img_bike_sm
                                            ),
                                            contentDescription = b.modelName,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp))
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("${b.modelName} #${b.dorsal}", style = MaterialTheme.typography.titleMedium)
                                        SurfaceBadge(b.discipline)
                                    }
                                    Text("${b.riderName} • ${b.terrain}", style = MaterialTheme.typography.bodyMedium, color = mx.subtleText)
                                    Text(
                                        "Fork: ${b.forkOil} | Shock: ${b.shockOil} | ${b.pinionSpur} | Gyro ${b.gyroPercent}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = mx.goldAccent
                                    )
                                }
                            }
                        }
                    }
                } else {
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
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
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
