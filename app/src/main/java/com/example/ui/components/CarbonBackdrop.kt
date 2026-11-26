package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CarbonVoid
import com.example.ui.theme.LocalMxColors
import com.example.ui.theme.SunCanvas
import kotlin.math.cos
import kotlin.math.sin

private data class StadiumSpark(
    val xFraction: Float,
    val yFraction: Float,
    val radius: Float,
    val speed: Float,
    val phase: Float,
    val isCyan: Boolean
)

@Composable
fun CarbonStadiumBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val mxColors = LocalMxColors.current
    val transition = rememberInfiniteTransition(label = "stadium_bg")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283185f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "drift"
    )
    val streakShift by transition.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "streak_shift"
    )
    val sparks = remember {
        listOf(
            StadiumSpark(0.06f, 0.14f, 3.8f, 1.0f, 0.1f, false),
            StadiumSpark(0.93f, 0.18f, 4.4f, 1.3f, 1.4f, true),
            StadiumSpark(0.08f, 0.42f, 3.0f, 0.8f, 2.5f, true),
            StadiumSpark(0.91f, 0.48f, 3.8f, 1.1f, 3.7f, false),
            StadiumSpark(0.04f, 0.72f, 4.2f, 0.9f, 4.2f, false),
            StadiumSpark(0.95f, 0.78f, 3.4f, 1.4f, 5.1f, true),
            StadiumSpark(0.12f, 0.88f, 2.8f, 1.2f, 0.9f, false),
            StadiumSpark(0.88f, 0.91f, 3.2f, 1.0f, 2.1f, true)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (mxColors.isSunMode) SunCanvas else CarbonVoid)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (!mxColors.isSunMode) {
                // 1. Diagonal carbon-fiber weave + racing telemetry sub-grid
                val step = 24f
                val weaveColorA = Color(0xFF151C29)
                val weaveColorB = Color(0xFF0C1018)
                var y = 0f
                var row = 0
                while (y < h) {
                    var x = 0f
                    var col = 0
                    while (x < w) {
                        val c = if ((row + col) % 2 == 0) weaveColorA else weaveColorB
                        drawRect(
                            color = c.copy(alpha = 0.55f),
                            topLeft = Offset(x, y),
                            size = Size(step * 0.86f, step * 0.86f)
                        )
                        x += step
                        col++
                    }
                    y += step
                    row++
                }

                // 2. Dual Sweeping Supercross Stadium Floodlights (Gold & Nitro Cyan)
                val beam1 = Path().apply {
                    moveTo(w * 0.12f, 0f)
                    lineTo(w * 0.28f, 0f)
                    lineTo(w * (0.58f + 0.16f * cos(drift)), h * 0.75f)
                    lineTo(w * (0.34f + 0.16f * cos(drift)), h * 0.75f)
                    close()
                }
                drawPath(
                    path = beam1,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            mxColors.goldAccent.copy(alpha = 0.13f),
                            mxColors.goldAccent.copy(alpha = 0.03f),
                            Color.Transparent
                        )
                    )
                )

                val beam2 = Path().apply {
                    moveTo(w * 0.74f, 0f)
                    lineTo(w * 0.90f, 0f)
                    lineTo(w * (0.64f - 0.14f * sin(drift)), h * 0.72f)
                    lineTo(w * (0.42f - 0.14f * sin(drift)), h * 0.72f)
                    close()
                }
                drawPath(
                    path = beam2,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            mxColors.smBadge.copy(alpha = 0.10f),
                            mxColors.smBadge.copy(alpha = 0.02f),
                            Color.Transparent
                        )
                    )
                )

                // 3. Moving radial stadium glow
                val spotX = w * (0.5f + 0.20f * cos(drift))
                val spotY = h * (0.20f + 0.08f * sin(drift))
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            mxColors.goldAccent.copy(alpha = 0.16f),
                            mxColors.racingRed.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        center = Offset(spotX, spotY),
                        radius = w * 0.95f
                    ),
                    center = Offset(spotX, spotY),
                    radius = w * 0.95f
                )

                // 4. Diagonal Speed Streaks (Motorsport kinetic lines)
                for (k in 0..3) {
                    val baseY = h * (0.18f + k * 0.22f)
                    val sx = w * streakShift - k * 140f
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                if (k % 2 == 0) mxColors.goldBright.copy(alpha = 0.16f) else mxColors.smBadge.copy(alpha = 0.15f),
                                Color.Transparent
                            ),
                            startX = sx,
                            endX = sx + w * 0.45f
                        ),
                        start = Offset(sx, baseY),
                        end = Offset(sx + w * 0.45f, baseY - 46f),
                        strokeWidth = 2.dp.toPx()
                    )
                }

                // 5. Subtle Checkered Racing Banner watermark along bottom horizon
                val checkSize = 14.dp.toPx()
                val cols = (w / checkSize).toInt() + 1
                val startCheckY = h - checkSize * 2.2f
                for (r in 0..1) {
                    for (c in 0..cols) {
                        if ((r + c) % 2 == 0) {
                            drawRect(
                                color = mxColors.goldAccent.copy(alpha = 0.06f),
                                topLeft = Offset(c * checkSize, startCheckY + r * checkSize),
                                size = Size(checkSize, checkSize)
                            )
                        }
                    }
                }

                // 6. Floating Stadium Sparks
                sparks.forEach { p ->
                    val px = w * p.xFraction + 12f * cos(drift * p.speed + p.phase)
                    val py = h * p.yFraction + 20f * sin(drift * p.speed + p.phase)
                    val alpha = 0.28f + 0.38f * (0.5f + 0.5f * sin(drift * 2f + p.phase))
                    drawCircle(
                        color = (if (p.isCyan) mxColors.smBadge else mxColors.goldBright).copy(alpha = alpha),
                        radius = p.radius,
                        center = Offset(px, py)
                    )
                }
            } else {
                // Sun mode crisp technical telemetry grid + bold amber racing corner accent
                val gridStep = 44f
                val gridColor = Color(0xFFCBD5E1).copy(alpha = 0.55f)
                var gx = 0f
                while (gx < w) {
                    drawLine(gridColor, Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
                    gx += gridStep
                }
                var gy = 0f
                while (gy < h) {
                    drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
                    gy += gridStep
                }
                drawLine(
                    color = mxColors.goldAccent.copy(alpha = 0.25f),
                    start = Offset(0f, 0f),
                    end = Offset(w * 0.4f, 0f),
                    strokeWidth = 6.dp.toPx()
                )
            }
        }
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            content()
        }
    }
}

@Composable
fun AnimatedCheckeredRibbon(modifier: Modifier = Modifier) {
    val mx = LocalMxColors.current
    val transition = rememberInfiniteTransition(label = "checkered_ribbon")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "check_shift"
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
    ) {
        val cellW = 10.dp.toPx()
        val cellH = size.height / 2f
        val offsetX = (shift * cellW) % (cellW * 2f)
        val count = (size.width / cellW).toInt() + 4
        for (row in 0..1) {
            for (col in -2..count) {
                val isLight = (row + col) % 2 == 0
                drawRect(
                    color = if (isLight) mx.goldBright.copy(alpha = 0.85f) else Color(0xFF1E293B),
                    topLeft = Offset(col * cellW + offsetX - cellW * 2f, row * cellH),
                    size = Size(cellW, cellH)
                )
            }
        }
    }
}

@Composable
fun GlassRaceCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    borderWidth: Dp = 1.25.dp,
    accentBorder: Color? = null,
    content: @Composable () -> Unit
) {
    val mxColors = LocalMxColors.current
    val strokeColor = accentBorder ?: mxColors.glassBorder
    Surface(
        modifier = modifier
            .clip(shape)
            .border(BorderStroke(borderWidth, strokeColor), shape),
        shape = shape,
        color = mxColors.glassCardBg,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (mxColors.isSunMode) 2.dp else 6.dp
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}

@Composable
fun Modifier.metallicShimmerSweep(enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val transition = rememberInfiniteTransition(label = "metallic_shimmer")
    val progress by transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_x"
    )
    return this.drawWithContent {
        drawContent()
        val xStart = size.width * progress
        val shimmerWidth = size.width * 0.34f
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.28f),
                    Color.Transparent
                ),
                start = Offset(xStart, 0f),
                end = Offset(xStart + shimmerWidth, size.height)
            )
        )
    }
}
