package com.example.ui.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FlagState
import com.example.data.model.LapRecord
import com.example.data.model.RiderColorProfile
import com.example.data.model.SessionPhase
import com.example.data.model.SurfaceType
import com.example.data.model.formatLapMs
import com.example.ui.theme.LocalMxColors
import java.util.Locale

@Composable
fun FlagAndRecordFrameOverlay(
    flagState: FlagState,
    lastHotlapAtMs: Long,
    nowMs: Long,
    modifier: Modifier = Modifier
) {
    val mx = LocalMxColors.current
    val isRecentHotlap = lastHotlapAtMs > 0L && (nowMs - lastHotlapAtMs) in 0L..4200L
    val isYellow = flagState == FlagState.YELLOW
    if (!isYellow && !isRecentHotlap) return

    val infinite = rememberInfiniteTransition(label = "edge_glow")
    val pulseColor by infinite.animateColor(
        initialValue = if (isYellow) mx.goldBright.copy(alpha = 0.85f) else mx.hotlapPurple.copy(alpha = 0.9f),
        targetValue = if (isYellow) mx.mxBadge.copy(alpha = 0.25f) else mx.goldAccent.copy(alpha = 0.25f),
        animationSpec = infiniteRepeatable(
            animation = tween(520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_edge"
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .border(width = 4.dp, color = pulseColor, shape = RoundedCornerShape(0.dp))
    )
}

@Composable
fun SupercrossStartGateCard(
    sessionPhase: SessionPhase,
    phaseEndTimeMs: Long,
    phaseStartTimeMs: Long,
    nowMs: Long,
    isSpanish: Boolean,
    modifier: Modifier = Modifier
) {
    val mx = LocalMxColors.current
    val isRecentGreen = sessionPhase == SessionPhase.RACING && (nowMs - phaseStartTimeMs) in 0L..3500L
    val show = sessionPhase == SessionPhase.COUNTDOWN_30 ||
        sessionPhase == SessionPhase.RANDOM_GATE ||
        isRecentGreen
    if (!show) return

    val remSec = if (phaseEndTimeMs > nowMs) ((phaseEndTimeMs - nowMs) / 1000L).toInt().coerceAtLeast(0) else 0
    val isDropped = sessionPhase == SessionPhase.RACING
    val gateDropAnim by animateFloatAsState(
        targetValue = if (isDropped) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "gate_drop"
    )

    val litCount = when {
        isDropped -> 5
        sessionPhase == SessionPhase.RANDOM_GATE -> 5
        remSec <= 1 -> 5
        remSec <= 3 -> 4
        remSec <= 6 -> 3
        remSec <= 10 -> 2
        remSec <= 16 -> 1
        else -> 0
    }

    GlassRaceCard(
        modifier = modifier.fillMaxWidth(),
        accentBorder = if (isDropped) mx.telemetryGreen else mx.racingRed
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = when {
                    isDropped -> if (isSpanish) "¡VALLA ABAJO — GAS A FONDO!" else "GATE DROP — FULL THROTTLE!"
                    sessionPhase == SessionPhase.RANDOM_GATE -> if (isSpanish) "VALLA MX LISTA • ATENTOS AL VERDE" else "MX GATE ARMED • WATCH THE LIGHTS"
                    else -> if (isSpanish) "SEMÁFORO SUPERCROSS • ${remSec}s" else "SUPERCROSS START GATE • ${remSec}s"
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (isDropped) mx.telemetryGreen else mx.racingRed,
                fontWeight = FontWeight.Bold
            )

            // 5 LED Start Tower
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 1..5) {
                    val lit = i <= litCount
                    val ledColor = when {
                        isDropped -> Color(0xFF22C55E)
                        lit -> Color(0xFFE63946)
                        else -> Color(0xFF27272A)
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(ledColor)
                            .border(2.dp, Color.White.copy(alpha = if (lit) 0.75f else 0.18f), CircleShape)
                    )
                }
            }

            // Animated falling MX starting gate bars
            Canvas(modifier = Modifier.fillMaxWidth().height(34.dp)) {
                val baseY = size.height - 6.dp.toPx()
                drawLine(
                    color = Color(0xFF64748B),
                    start = Offset(12f, baseY),
                    end = Offset(size.width - 12f, baseY),
                    strokeWidth = 3.dp.toPx()
                )
                val barCount = 5
                val step = (size.width - 40f) / (barCount - 1).coerceAtLeast(1)
                val barHeight = 24.dp.toPx() * (1f - gateDropAnim * 0.85f)
                for (idx in 0 until barCount) {
                    val bx = 20f + idx * step
                    drawLine(
                        color = if (isDropped) Color(0xFF22C55E) else Color(0xFFF5B731),
                        start = Offset(bx, baseY),
                        end = Offset(bx + gateDropAnim * 18.dp.toPx(), baseY - barHeight),
                        strokeWidth = 5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}

@Composable
fun RiderDorsalBadge(
    dorsal: String,
    discipline: SurfaceType,
    colorProfile: RiderColorProfile? = null,
    position: Int? = null,
    modifier: Modifier = Modifier
) {
    val mx = LocalMxColors.current
    val plateColor = colorProfile?.samples?.firstOrNull()?.hex?.let {
        try { Color(android.graphics.Color.parseColor(it)) } catch (_: Exception) { null }
    } ?: if (discipline == SurfaceType.SM) mx.smBadge else mx.mxBadge

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (position != null && position in 1..3) {
            val medalColor = when (position) {
                1 -> Color(0xFFF5B731)
                2 -> Color(0xFFCBD5E1)
                else -> Color(0xFFD97706)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(medalColor.copy(alpha = 0.22f))
                    .border(1.dp, medalColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = medalColor, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("P$position", style = MaterialTheme.typography.labelSmall, color = medalColor, fontWeight = FontWeight.Bold)
                }
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(plateColor.copy(alpha = 0.22f))
                .border(1.5.dp, plateColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(plateColor)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "#$dorsal",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun LapTelemetryChartCard(
    laps: List<LapRecord>,
    isSpanish: Boolean,
    modifier: Modifier = Modifier
) {
    val mx = LocalMxColors.current
    val validLaps = laps.filter { it.lapTimeMs > 0L }.sortedBy { it.timestampMs }.takeLast(18)

    GlassRaceCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isSpanish) "Gráfica de Telemetría (Vueltas y Velocidad)" else "Telemetry Chart (Lap Times & Speed)",
                    style = MaterialTheme.typography.titleMedium,
                    color = mx.goldAccent
                )
                Icon(Icons.Default.Speed, contentDescription = null, tint = mx.smBadge, modifier = Modifier.size(18.dp))
            }

            if (validLaps.size < 2) {
                Text(
                    if (isSpanish) "Completa al menos 2 vueltas para visualizar la curva de tiempos y velocidad."
                    else "Complete at least 2 laps to render the lap & speed curve.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = mx.subtleText
                )
                return@Column
            }

            val bestMs = validLaps.minOf { it.lapTimeMs }
            val worstMs = validLaps.maxOf { it.lapTimeMs }
            val avgMs = validLaps.map { it.lapTimeMs }.average().toLong()
            val topSpeed = validLaps.maxOf { it.speedKmh }
            val lineColor = mx.smBadge
            val bestColor = mx.hotlapPurple
            val avgColor = mx.goldAccent
            val greenBar = mx.telemetryGreen

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${if (isSpanish) "Mejor" else "Best"}: ${formatLapMs(bestMs)}", style = MaterialTheme.typography.labelSmall, color = bestColor)
                Text("${if (isSpanish) "Media" else "Avg"}: ${formatLapMs(avgMs)}", style = MaterialTheme.typography.labelSmall, color = avgColor)
                Text("Max: ${String.format(Locale.US, "%.1f", topSpeed)} km/h", style = MaterialTheme.typography.labelSmall, color = greenBar)
            }

            // Lap Time Curve
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(115.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = if (mx.isSunMode) 0.06f else 0.35f))
                    .padding(10.dp)
            ) {
                val range = (worstMs - bestMs).coerceAtLeast(500L).toFloat()
                val stepX = size.width / (validLaps.size - 1).coerceAtLeast(1)

                // Average dashed/solid horizontal guide
                val avgNorm = ((avgMs - bestMs) / range).coerceIn(0f, 1f)
                val avgY = avgNorm * size.height
                drawLine(
                    color = avgColor.copy(alpha = 0.45f),
                    start = Offset(0f, avgY),
                    end = Offset(size.width, avgY),
                    strokeWidth = 1.5.dp.toPx()
                )

                val linePath = Path()
                val fillPath = Path()
                val points = validLaps.mapIndexed { idx, l ->
                    val x = idx * stepX
                    val y = (((l.lapTimeMs - bestMs) / range).coerceIn(0f, 1f)) * (size.height - 8f) + 4f
                    Offset(x, y)
                }

                points.forEachIndexed { idx, pt ->
                    if (idx == 0) {
                        linePath.moveTo(pt.x, pt.y)
                        fillPath.moveTo(pt.x, size.height)
                        fillPath.lineTo(pt.x, pt.y)
                    } else {
                        linePath.lineTo(pt.x, pt.y)
                        fillPath.lineTo(pt.x, pt.y)
                    }
                }
                fillPath.lineTo(points.last().x, size.height)
                fillPath.close()

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.28f), Color.Transparent))
                )
                drawPath(path = linePath, color = lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))

                points.forEachIndexed { idx, pt ->
                    val isBest = validLaps[idx].lapTimeMs == bestMs
                    drawCircle(
                        color = if (isBest) bestColor else lineColor,
                        radius = if (isBest) 5.dp.toPx() else 3.dp.toPx(),
                        center = pt
                    )
                }
            }

            // Speed Bar Sparkline
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = if (mx.isSunMode) 0.05f else 0.28f))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                val maxSpd = topSpeed.coerceAtLeast(1.0).toFloat()
                val barSlot = size.width / validLaps.size.coerceAtLeast(1)
                val barW = (barSlot * 0.6f).coerceAtLeast(4f)
                validLaps.forEachIndexed { idx, l ->
                    val frac = (l.speedKmh.toFloat() / maxSpd).coerceIn(0.15f, 1f)
                    val h = size.height * frac
                    val x = idx * barSlot + (barSlot - barW) / 2f
                    drawRect(
                        color = if (l.lapTimeMs == bestMs) bestColor else greenBar.copy(alpha = 0.78f),
                        topLeft = Offset(x, size.height - h),
                        size = Size(barW, h)
                    )
                }
            }
            Text(
                text = if (isSpanish) "Últimas ${validLaps.size} vueltas • Punto violeta = Mejor vuelta" else "Last ${validLaps.size} laps • Purple dot = Best lap",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = mx.subtleText
            )
        }
    }
}
