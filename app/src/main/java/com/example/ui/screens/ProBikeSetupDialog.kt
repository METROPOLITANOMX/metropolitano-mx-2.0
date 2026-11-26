package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import com.example.data.model.BikeSetup
import com.example.data.model.SurfaceType
import com.example.i18n.MxStrings
import com.example.ui.theme.LocalMxColors
import com.example.ui.theme.contrastTextOn
import java.util.Locale

@Composable
fun ProBikeSetupDialog(
    bike: BikeSetup,
    strings: MxStrings,
    onDismiss: () -> Unit,
    onSave: (BikeSetup) -> Unit
) {
    val mx = LocalMxColors.current
    var sectionTab by remember { mutableIntStateOf(0) }

    var discipline by remember { mutableStateOf(bike.discipline) }
    var model by remember { mutableStateOf(bike.modelName) }
    var dorsal by remember { mutableStateOf(bike.dorsal) }
    var terrain by remember { mutableStateOf(bike.terrain) }

    // Section 1: Suspensión & Geometría Losi Promoto
    var forkOil by remember { mutableStateOf(bike.forkOil) }
    var forkSpring by remember { mutableStateOf(bike.forkSpring) }
    var shockOil by remember { mutableStateOf(bike.shockOil) }
    var shockSpring by remember { mutableStateOf(bike.shockSpring) }
    var rearSagMm by remember { mutableStateOf(bike.rearSagMm) }
    var leanBarSetting by remember { mutableStateOf(bike.leanBarSetting) }
    var frontBrakePercent by remember { mutableIntStateOf(bike.frontBrakePercent) }
    var servoSaverSetting by remember { mutableStateOf(bike.servoSaverSetting) }

    // Section 2: Electrónica Spektrum MS6X & Firma 85A
    var gyroPercent by remember { mutableIntStateOf(bike.gyroPercent) }
    var gyroMode by remember { mutableStateOf(bike.gyroMode) }
    var escPunch by remember { mutableIntStateOf(bike.escPunch) }
    var escDragBrake by remember { mutableIntStateOf(bike.escDragBrake) }
    var escMaxBrake by remember { mutableIntStateOf(bike.escMaxBrake) }
    var throttleExpo by remember { mutableIntStateOf(bike.throttleExpo) }
    var lipoCutoff by remember { mutableStateOf(bike.lipoCutoff) }

    // Section 3: Desarrollo, Neumáticos & LiPo
    var pinionTeeth by remember { mutableIntStateOf(bike.pinionTeeth.coerceIn(13, 18)) }
    var spurTeeth by remember { mutableIntStateOf(bike.spurTeeth.coerceIn(48, 54)) }
    var frontTire by remember { mutableStateOf(bike.frontTire.ifBlank { bike.tires }) }
    var rearTire by remember { mutableStateOf(bike.rearTire.ifBlank { bike.tires }) }
    var tireInsert by remember { mutableStateOf(bike.tireInsert) }
    var lipoSpecs by remember { mutableStateOf(bike.lipoSpecs) }
    var notes by remember { mutableStateOf(bike.notes) }

    fun applyFactoryDefaults(surface: SurfaceType) {
        val d = BikeSetup.defaultsFor(surface)
        discipline = surface
        model = d.modelName
        terrain = d.terrain
        forkOil = d.forkOil
        forkSpring = d.forkSpring
        shockOil = d.shockOil
        shockSpring = d.shockSpring
        rearSagMm = d.rearSagMm
        leanBarSetting = d.leanBarSetting
        frontBrakePercent = d.frontBrakePercent
        servoSaverSetting = d.servoSaverSetting
        gyroPercent = d.gyroPercent
        gyroMode = d.gyroMode
        escPunch = d.escPunch
        escDragBrake = d.escDragBrake
        escMaxBrake = d.escMaxBrake
        throttleExpo = d.throttleExpo
        lipoCutoff = d.lipoCutoff
        pinionTeeth = d.pinionTeeth
        spurTeeth = d.spurTeeth
        frontTire = d.frontTire
        rearTire = d.rearTire
        tireInsert = d.tireInsert
        lipoSpecs = d.lipoSpecs
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (strings.isSpanish) "Ficha Pro Losi Promoto" else "Losi Promoto Pro Setup",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (strings.isSpanish) "Modalidad Oficial (MX / Supermotard)" else "Official Discipline (MX / Supermoto)",
                    style = MaterialTheme.typography.labelMedium,
                    color = mx.goldAccent
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = discipline == SurfaceType.MX,
                        onClick = {
                            discipline = SurfaceType.MX
                            if (model.contains("-SM")) model = "Losi Promoto-MX"
                        },
                        label = { Text("MX Tierra") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = discipline == SurfaceType.SM,
                        onClick = {
                            discipline = SurfaceType.SM
                            if (model.contains("-MX")) model = "Losi Promoto-SM"
                        },
                        label = { Text("SM Supermotard") },
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedButton(
                    onClick = { applyFactoryDefaults(discipline) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (strings.isSpanish) "Cargar valores base ${discipline.name}"
                        else "Load base ${discipline.name} setup"
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text(if (strings.isSpanish) "Modelo" else "Model") },
                        singleLine = true,
                        modifier = Modifier.weight(1.7f)
                    )
                    OutlinedTextField(
                        value = dorsal,
                        onValueChange = { dorsal = it.take(4) },
                        label = { Text("Dorsal") },
                        singleLine = true,
                        modifier = Modifier.weight(0.9f)
                    )
                }
                OutlinedTextField(
                    value = terrain,
                    onValueChange = { terrain = it },
                    label = { Text(if (strings.isSpanish) "Superficie / Pista" else "Track Surface") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(2.dp))
                TabRow(selectedTabIndex = sectionTab) {
                    Tab(
                        selected = sectionTab == 0,
                        onClick = { sectionTab = 0 },
                        text = { Text(if (strings.isSpanish) "Suspensión" else "Suspension", maxLines = 1) }
                    )
                    Tab(
                        selected = sectionTab == 1,
                        onClick = { sectionTab = 1 },
                        text = { Text("Spektrum", maxLines = 1) }
                    )
                    Tab(
                        selected = sectionTab == 2,
                        onClick = { sectionTab = 2 },
                        text = { Text(if (strings.isSpanish) "Desarrollo" else "Gearing", maxLines = 1) }
                    )
                }

                when (sectionTab) {
                    0 -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = forkOil,
                                onValueChange = { forkOil = it },
                                label = { Text(if (strings.isSpanish) "Aceite Horquilla" else "Fork Oil") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = forkSpring,
                                onValueChange = { forkSpring = it },
                                label = { Text(if (strings.isSpanish) "Muelle Del." else "Fork Spring") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = shockOil,
                                onValueChange = { shockOil = it },
                                label = { Text(if (strings.isSpanish) "Aceite Amort." else "Shock Oil") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = shockSpring,
                                onValueChange = { shockSpring = it },
                                label = { Text(if (strings.isSpanish) "Muelle Tras." else "Shock Spring") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = rearSagMm,
                                onValueChange = { rearSagMm = it },
                                label = { Text("SAG Trasero") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = servoSaverSetting,
                                onValueChange = { servoSaverSetting = it },
                                label = { Text("Servo Saver") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text("Lean Bars (${if (strings.isSpanish) "Ángulo apoyo" else "Lean Angle"})", style = MaterialTheme.typography.labelSmall, color = mx.subtleText)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Bajo (SM)", "Medio (Std)", "Alto (MX)").forEach { opt ->
                                FilterChip(
                                    selected = leanBarSetting.startsWith(opt.substringBefore(" ")),
                                    onClick = { leanBarSetting = opt },
                                    label = { Text(opt) }
                                )
                            }
                        }
                        Text("${if (strings.isSpanish) "Freno Delantero (Cable)" else "Front Brake Travel"}: $frontBrakePercent%", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = frontBrakePercent.toFloat(),
                            onValueChange = { frontBrakePercent = it.toInt() },
                            valueRange = 0f..100f
                        )
                    }
                    1 -> {
                        Text("Spektrum MS6X Flywheel / Gyro: $gyroPercent%", style = MaterialTheme.typography.labelMedium, color = mx.goldAccent)
                        Slider(
                            value = gyroPercent.toFloat(),
                            onValueChange = { gyroPercent = it.toInt() },
                            valueRange = 0f..100f
                        )
                        Text(if (strings.isSpanish) "Modo MS6X" else "MS6X Mode", style = MaterialTheme.typography.labelSmall, color = mx.subtleText)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Off-Road", "On-Road / Wheelie", "Dirt").forEach { m ->
                                FilterChip(
                                    selected = gyroMode == m,
                                    onClick = { gyroMode = m },
                                    label = { Text(m) }
                                )
                            }
                        }
                        Text("Firma 85A ESC Punch: $escPunch / 9", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = escPunch.toFloat(),
                            onValueChange = { escPunch = it.toInt().coerceIn(1, 9) },
                            valueRange = 1f..9f,
                            steps = 7
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Drag Brake: $escDragBrake%", style = MaterialTheme.typography.labelSmall)
                                Slider(value = escDragBrake.toFloat(), onValueChange = { escDragBrake = it.toInt() }, valueRange = 0f..50f)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Max Brake: $escMaxBrake%", style = MaterialTheme.typography.labelSmall)
                                Slider(value = escMaxBrake.toFloat(), onValueChange = { escMaxBrake = it.toInt() }, valueRange = 25f..100f)
                            }
                        }
                        Text("Expo Gas: ${if (throttleExpo >= 0) "+$throttleExpo" else "$throttleExpo"}%", style = MaterialTheme.typography.labelSmall)
                        Slider(value = throttleExpo.toFloat(), onValueChange = { throttleExpo = it.toInt() }, valueRange = -30f..30f)
                        OutlinedTextField(
                            value = lipoCutoff,
                            onValueChange = { lipoCutoff = it },
                            label = { Text("LiPo Cutoff") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    else -> {
                        val ratioStr = String.format(Locale.US, "%.2f:1", spurTeeth.toDouble() / pinionTeeth.coerceAtLeast(1))
                        Text(
                            "${if (strings.isSpanish) "Relación Final" else "Final Drive Ratio"}: ${pinionTeeth}T / ${spurTeeth}T ($ratioStr)",
                            style = MaterialTheme.typography.titleMedium,
                            color = mx.smBadge
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Piñón:", style = MaterialTheme.typography.labelSmall)
                            listOf(13, 14, 15, 16, 17).forEach { p ->
                                FilterChip(selected = pinionTeeth == p, onClick = { pinionTeeth = p }, label = { Text("${p}T") })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Corona:", style = MaterialTheme.typography.labelSmall)
                            listOf(48, 50, 52, 54).forEach { s ->
                                FilterChip(selected = spurTeeth == s, onClick = { spurTeeth = s }, label = { Text("${s}T") })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = frontTire,
                                onValueChange = { frontTire = it },
                                label = { Text(if (strings.isSpanish) "Neumático Del." else "Front Tire") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = rearTire,
                                onValueChange = { rearTire = it },
                                label = { Text(if (strings.isSpanish) "Neumático Tras." else "Rear Tire") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        OutlinedTextField(
                            value = tireInsert,
                            onValueChange = { tireInsert = it },
                            label = { Text(if (strings.isSpanish) "Espuma / Inserto" else "Tire Insert") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = lipoSpecs,
                            onValueChange = { lipoSpecs = it },
                            label = { Text(if (strings.isSpanish) "Batería LiPo (2S IC5)" else "LiPo Battery Pack") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text(if (strings.isSpanish) "Notas de reglaje" else "Setup Notes") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val ratio = String.format(Locale.US, "%.2f:1", spurTeeth.toDouble() / pinionTeeth.coerceAtLeast(1))
                    onSave(
                        bike.copy(
                            discipline = discipline,
                            modelName = model.ifBlank { if (discipline == SurfaceType.SM) "Losi Promoto-SM" else "Losi Promoto-MX" },
                            dorsal = dorsal.ifBlank { "01" },
                            terrain = terrain,
                            forkOil = forkOil,
                            forkSpring = forkSpring,
                            shockOil = shockOil,
                            shockSpring = shockSpring,
                            rearSagMm = rearSagMm,
                            leanBarSetting = leanBarSetting,
                            frontBrakePercent = frontBrakePercent,
                            servoSaverSetting = servoSaverSetting,
                            gyroPercent = gyroPercent,
                            gyroMode = gyroMode,
                            escPunch = escPunch,
                            escDragBrake = escDragBrake,
                            escMaxBrake = escMaxBrake,
                            throttleExpo = throttleExpo,
                            lipoCutoff = lipoCutoff,
                            pinionTeeth = pinionTeeth,
                            spurTeeth = spurTeeth,
                            pinionSpur = "${pinionTeeth}T / ${spurTeeth}T ($ratio)",
                            frontTire = frontTire,
                            rearTire = rearTire,
                            tires = frontTire,
                            tireInsert = tireInsert,
                            lipoSpecs = lipoSpecs,
                            notes = notes
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = mx.goldAccent,
                    contentColor = contrastTextOn(mx.goldAccent)
                )
            ) { Text(if (strings.isSpanish) "Guardar Setup Pro" else "Save Pro Setup") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (strings.isSpanish) "Cancelar" else "Cancel") }
        }
    )
}
