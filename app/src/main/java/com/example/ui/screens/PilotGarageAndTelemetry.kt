package com.example.ui.screens

import android.graphics.Bitmap
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BikeSetup
import com.example.data.model.SurfaceType
import com.example.data.model.computeRankTitle
import com.example.data.model.formatDeltaMs
import com.example.data.model.formatLapMs
import com.example.engine.CardExporter
import com.example.i18n.MxStrings
import com.example.ui.components.Base64ImageView
import com.example.ui.components.GlassRaceCard
import com.example.ui.components.SurfaceBadge
import com.example.ui.theme.LocalMxColors
import com.example.ui.viewmodel.MxProViewModel
import com.example.ui.viewmodel.MxUiState
import java.io.ByteArrayOutputStream
import java.util.UUID

@Composable
fun PilotGarageProTab(
    state: MxUiState,
    strings: MxStrings,
    viewModel: MxProViewModel
) {
    val mx = LocalMxColors.current
    val context = LocalContext.current
    val bikes = state.myBikes
    var editingBike by remember { mutableStateOf<BikeSetup?>(null) }
    var showBackupRestore by remember { mutableStateOf(false) }
    var backupText by remember { mutableStateOf("") }

    val activeBike = state.activeBike
    val photoTakeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bmp: Bitmap? ->
        if (bmp != null && activeBike != null) {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            val updatedList = (activeBike.photoBase64List + b64).takeLast(4)
            viewModel.saveBikeSetup(activeBike.copy(photoBase64List = updatedList), setActive = false)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(strings.tabGaragePro, style = MaterialTheme.typography.headlineMedium)
            Button(
                onClick = {
                    editingBike = BikeSetup(
                        id = "b_${UUID.randomUUID().toString().take(8)}",
                        discipline = state.activeCircuit.surface,
                        modelName = if (state.activeCircuit.surface == SurfaceType.SM) "Losi Promoto-SM" else "Losi Promoto-MX"
                    )
                },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("garage_add_bike")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(strings.addBike)
            }
        }

        // Bike selector chips
        if (bikes.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(bikes) { b ->
                    FilterChip(
                        selected = b.id == activeBike?.id,
                        onClick = { viewModel.saveBikeSetup(b, setActive = true) },
                        label = { Text("${b.modelName} #${b.dorsal}") }
                    )
                }
            }
        }

        if (activeBike != null) {
            GlassRaceCard {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("${activeBike.modelName} #${activeBike.dorsal}", style = MaterialTheme.typography.titleLarge)
                            Text("${activeBike.totalLaps} laps • ${activeBike.usageMinutes} min", style = MaterialTheme.typography.labelMedium, color = mx.goldAccent)
                        }
                        SurfaceBadge(activeBike.discipline)
                    }

                    // 4-Photo live camera gallery
                    if (activeBike.photoBase64List.isEmpty()) {
                        Image(
                            painter = painterResource(
                                if (activeBike.discipline == SurfaceType.MX) R.drawable.img_bike_mx else R.drawable.img_bike_sm
                            ),
                            contentDescription = activeBike.modelName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(activeBike.photoBase64List) { photoB64 ->
                                Base64ImageView(
                                    base64 = photoB64,
                                    contentDescription = activeBike.modelName,
                                    modifier = Modifier.size(130.dp, 100.dp).clip(RoundedCornerShape(10.dp))
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { photoTakeLauncher.launch(null) },
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("${strings.takePhoto} (${activeBike.photoBase64List.size}/4)")
                        }
                        OutlinedButton(
                            onClick = { editingBike = activeBike },
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                        ) {
                            Text(if (strings.isSpanish) "Editar Reglaje" else "Edit Setup")
                        }
                    }

                    // Maintenance progress bars
                    val chainUsed = (activeBike.totalLaps - activeBike.chainMaintAtLaps).coerceAtLeast(0)
                    val gyroUsed = (activeBike.totalLaps - activeBike.gyroMaintAtLaps).coerceAtLeast(0)
                    Text(
                        if (strings.isSpanish) "Mantenimiento Cadena ($chainUsed / 80 laps)" else "Chain Service ($chainUsed / 80 laps)",
                        style = MaterialTheme.typography.labelSmall
                    )
                    LinearProgressIndicator(progress = { (chainUsed / 80f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(
                        if (strings.isSpanish) "Servicio Gyro MS6X ($gyroUsed / 150 laps)" else "Gyro MS6X Service ($gyroUsed / 150 laps)",
                        style = MaterialTheme.typography.labelSmall
                    )
                    LinearProgressIndicator(progress = { (gyroUsed / 150f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())

                    // Complete setup sheet
                    Text(
                        "${if (strings.isSpanish) "Terreno" else "Terrain"}: ${activeBike.terrain}\n" +
                            "${if (strings.isSpanish) "Horquilla" else "Fork"}: ${activeBike.forkOil} | ${if (strings.isSpanish) "Amortiguador" else "Shock"}: ${activeBike.shockOil}\n" +
                            "${if (strings.isSpanish) "Piñón/Corona" else "Gearing"}: ${activeBike.pinionSpur} | ${if (strings.isSpanish) "Neumáticos" else "Tires"}: ${activeBike.tires}\n" +
                            "Gyro MS6X: ${activeBike.gyroPercent}%",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                val txt = CardExporter.formatSetupShareText(state.currentRider?.name ?: "", activeBike, strings.isSpanish)
                                CardExporter.shareWhatsAppText(context, txt)
                            },
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(strings.shareWhatsApp, maxLines = 1)
                        }
                        Button(
                            onClick = {
                                viewModel.saveBikeSetup(
                                    activeBike.copy(publishedToPaddock = !activeBike.publishedToPaddock),
                                    setActive = false
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (activeBike.publishedToPaddock) mx.telemetryGreen else mx.smBadge
                            ),
                            modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (activeBike.publishedToPaddock) (if (strings.isSpanish) "En Paddock" else "In Paddock")
                                else strings.publishPaddock,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // Export / Restore Backup
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    val json = viewModel.exportGarageBackupJson()
                    CardExporter.shareWhatsAppText(context, json)
                },
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(strings.exportBackup)
            }
            OutlinedButton(
                onClick = { showBackupRestore = true },
                modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(strings.restoreBackup)
            }
        }
    }

    editingBike?.let { bike ->
        BikeEditDialog(
            bike = bike,
            strings = strings,
            onDismiss = { editingBike = null },
            onSave = { updated ->
                viewModel.saveBikeSetup(updated, setActive = true)
                editingBike = null
            }
        )
    }

    if (showBackupRestore) {
        AlertDialog(
            onDismissRequest = { showBackupRestore = false },
            title = { Text(strings.restoreBackup) },
            text = {
                OutlinedTextField(
                    value = backupText,
                    onValueChange = { backupText = it },
                    label = { Text("JSON Backup") },
                    minLines = 4
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.restoreGarageBackupJson(backupText)
                    showBackupRestore = false
                }) { Text(strings.restoreBackup) }
            },
            dismissButton = { TextButton(onClick = { showBackupRestore = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun BikeEditDialog(
    bike: BikeSetup,
    strings: MxStrings,
    onDismiss: () -> Unit,
    onSave: (BikeSetup) -> Unit
) {
    var discipline by remember { mutableStateOf(bike.discipline) }
    var model by remember { mutableStateOf(bike.modelName) }
    var dorsal by remember { mutableStateOf(bike.dorsal) }
    var terrain by remember { mutableStateOf(bike.terrain) }
    var forkOil by remember { mutableStateOf(bike.forkOil) }
    var shockOil by remember { mutableStateOf(bike.shockOil) }
    var pinionSpur by remember { mutableStateOf(bike.pinionSpur) }
    var tires by remember { mutableStateOf(bike.tires) }
    var gyro by remember { mutableIntStateOf(bike.gyroPercent) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (strings.isSpanish) "Configurar Moto y Reglaje" else "Configure Bike & Setup") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = discipline == SurfaceType.MX, onClick = { discipline = SurfaceType.MX }, label = { Text("MX Tierra") })
                    FilterChip(selected = discipline == SurfaceType.SM, onClick = { discipline = SurfaceType.SM }, label = { Text("SM Asfalto") })
                }
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Modelo") }, singleLine = true)
                OutlinedTextField(value = dorsal, onValueChange = { dorsal = it }, label = { Text("Dorsal") }, singleLine = true)
                OutlinedTextField(value = terrain, onValueChange = { terrain = it }, label = { Text("Terreno") }, singleLine = true)
                OutlinedTextField(value = forkOil, onValueChange = { forkOil = it }, label = { Text("Aceite Horquilla") }, singleLine = true)
                OutlinedTextField(value = shockOil, onValueChange = { shockOil = it }, label = { Text("Amortiguador") }, singleLine = true)
                OutlinedTextField(value = pinionSpur, onValueChange = { pinionSpur = it }, label = { Text("Piñón / Corona") }, singleLine = true)
                OutlinedTextField(value = tires, onValueChange = { tires = it }, label = { Text("Neumáticos") }, singleLine = true)
                Text("Gyro / MS6X: $gyro%")
                Slider(value = gyro.toFloat(), onValueChange = { gyro = it.toInt() }, valueRange = 0f..100f)
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    bike.copy(
                        discipline = discipline,
                        modelName = model,
                        dorsal = dorsal,
                        terrain = terrain,
                        forkOil = forkOil,
                        shockOil = shockOil,
                        pinionSpur = pinionSpur,
                        tires = tires,
                        gyroPercent = gyro
                    )
                )
            }) { Text(if (strings.isSpanish) "Guardar" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (strings.isSpanish) "Cancelar" else "Cancel") } }
    )
}

@Composable
fun PilotTelemetryTab(
    state: MxUiState,
    strings: MxStrings
) {
    val mx = LocalMxColors.current
    val context = LocalContext.current
    val rider = state.currentRider
    val myLaps = state.circuitLaps.filter { it.riderId == rider?.id }
    val rivals = state.roomState.activeRiders.values.filter { it.riderId != rider?.id }
    var selectedRivalId by remember { mutableStateOf(rivals.firstOrNull()?.riderId ?: "") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (rider != null) {
            val rank = computeRankTitle(rider.totalLaps, strings.isSpanish)
            GlassRaceCard(accentBorder = mx.goldAccent.copy(alpha = 0.6f)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(rider.name, style = MaterialTheme.typography.headlineMedium)
                        Text(rank, style = MaterialTheme.typography.titleMedium, color = mx.goldAccent)
                    }
                    Text("${strings.bestLap}: ${formatLapMs(rider.bestLapMs)}  •  ${strings.regularity}: ${String.format("%.1f", rider.regularityIndex)}%")
                    Text("${strings.totalLaps}: ${rider.totalLaps}  •  ${String.format("%.1f", rider.avgSpeedKmh)} km/h")
                }
            }

            // Export PNG Summary Card & MXGP Podium
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val todayBest = myLaps.filter { it.lapTimeMs > 0 }.minOfOrNull { it.lapTimeMs } ?: rider.bestLapMs
                        CardExporter.generateAndShareSummaryPng(
                            context = context,
                            rider = rider,
                            circuit = state.activeCircuit,
                            bike = state.activeBike,
                            dayLapsCount = myLaps.size,
                            dayBestLapMs = todayBest,
                            isSpanish = strings.isSpanish
                        )
                    },
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(strings.downloadPngCard, maxLines = 1)
                }
                Button(
                    onClick = {
                        val top3 = state.roomState.activeRiders.values
                            .sortedWith(compareByDescending<com.example.data.model.ActiveRoomRider> { it.lapsCount }.thenBy { if (it.bestLapMs <= 0) Long.MAX_VALUE else it.bestLapMs })
                            .take(3)
                        CardExporter.generateAndShareMxgpPodium(context, state.activeCircuit, top3, strings.isSpanish)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = mx.smBadge),
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.EmojiEvents, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(strings.generatePodium, maxLines = 1)
                }
            }
        }

        // 1 vs 1 Duel
        GlassRaceCard {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.duel1v1, style = MaterialTheme.typography.titleLarge, color = mx.goldAccent)
                if (rivals.isEmpty()) {
                    Text(
                        if (strings.isSpanish) "Aún no hay otros rivales en sala" else "No other rivals in room yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = mx.subtleText
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(rivals) { r ->
                            FilterChip(
                                selected = r.riderId == selectedRivalId,
                                onClick = { selectedRivalId = r.riderId },
                                label = { Text("${r.riderName} #${r.dorsal}") }
                            )
                        }
                    }
                    val rival = rivals.firstOrNull { it.riderId == selectedRivalId } ?: rivals.first()
                    val myBest = state.roomState.activeRiders[rider?.id]?.bestLapMs ?: rider?.bestLapMs ?: 0L
                    val diff = if (myBest > 0 && rival.bestLapMs > 0) myBest - rival.bestLapMs else 0L
                    Text(
                        text = "${rival.riderName}: ${formatLapMs(rival.bestLapMs)}  |  Delta: ${formatDeltaMs(diff)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (diff <= 0) mx.telemetryGreen else mx.racingRed
                    )
                    Text(
                        text = "${rival.bikeModel} — ${rival.setupSummary.ifBlank { "Standard Setup" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = mx.subtleText
                    )
                }
            }
        }

        // Daily Session History grouped by day
        Text(if (strings.isSpanish) "Historial por Días Rodados" else "Session History by Day", style = MaterialTheme.typography.titleLarge)
        val grouped = myLaps.groupBy { it.dayKey.ifBlank { "Hoy" } }
        if (grouped.isEmpty()) {
            Text(
                if (strings.isSpanish) "Registra tu primera vuelta para ver el historial." else "Record your first lap to see history.",
                color = mx.subtleText
            )
        } else {
            grouped.forEach { (day, laps) ->
                GlassRaceCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val bestDay = laps.minOfOrNull { it.lapTimeMs } ?: 0L
                        Text("$day • ${laps.size} laps • Best ${formatLapMs(bestDay)}", style = MaterialTheme.typography.titleMedium, color = mx.goldAccent)
                        laps.take(5).forEach { l ->
                            Text(
                                "  • ${formatLapMs(l.lapTimeMs)} (${formatDeltaMs(l.deltaMs)}) — ${String.format("%.1f", l.speedKmh)} km/h",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
