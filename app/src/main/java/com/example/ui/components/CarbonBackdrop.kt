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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CarbonVoid
import com.example.ui.theme.LocalMxColors
import com.example.ui.theme.SunCanvas
import kotlin.math.cos
import kotlin.math.sin

private data class GoldParticle(
    val xFraction: Float,
    val yFraction: Float,
    val radius: Float,
    val speed: Float,
    val phase: Float
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
            animation = tween(durationMillis = 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "drift"
    )
    val particles = remember {
        listOf(
            GoldParticle(0.06f, 0.14f, 3.5f, 1.0f, 0.1f),
            GoldParticle(0.93f, 0.18f, 4.2f, 1.3f, 1.4f),
            GoldParticle(0.08f, 0.42f, 2.8f, 0.8f, 2.5f),
            GoldParticle(0.91f, 0.48f, 3.6f, 1.1f, 3.7f),
            GoldParticle(0.04f, 0.72f, 4.0f, 0.9f, 4.2f),
            GoldParticle(0.95f, 0.78f, 3.2f, 1.4f, 5.1f),
            GoldParticle(0.12f, 0.88f, 2.5f, 1.2f, 0.9f),
            GoldParticle(0.88f, 0.91f, 3.0f, 1.0f, 2.1f)
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
                // Subtle diagonal carbon-fiber weave
                val step = 22f
                val weaveColorA = Color(0xFF141923)
                val weaveColorB = Color(0xFF0E1219)
                var y = 0f
                var row = 0
                while (y < h) {
                    var x = 0f
                    var col = 0
                    while (x < w) {
                        val c = if ((row + col) % 2 == 0) weaveColorA else weaveColorB
                        drawRect(
                            color = c.copy(alpha = 0.45f),
                            topLeft = Offset(x, y),
                            size = androidx.compose.ui.geometry.Size(step * 0.88f, step * 0.88f)
                        )
                        x += step
                        col++
                    }
                    y += step
                    row++
                }

                // Moving radial Supercross spotlight
                val spotX = w * (0.5f + 0.18f * cos(drift))
                val spotY = h * (0.22f + 0.08f * sin(drift))
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            mxColors.goldAccent.copy(alpha = 0.14f),
                            mxColors.smBadge.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        center = Offset(spotX, spotY),
                        radius = w * 0.95f
                    ),
                    center = Offset(spotX, spotY),
                    radius = w * 0.95f
                )

                // Floating gold Supercross edge particles
                particles.forEach { p ->
                    val px = w * p.xFraction + 10f * cos(drift * p.speed + p.phase)
                    val py = h * p.yFraction + 18f * sin(drift * p.speed + p.phase)
                    val alpha = 0.25f + 0.35f * (0.5f + 0.5f * sin(drift * 2f + p.phase))
                    drawCircle(
                        color = mxColors.goldBright.copy(alpha = alpha),
                        radius = p.radius,
                        center = Offset(px, py)
                    )
                }
            } else {
                // Sun mode crisp technical telemetry grid
                val gridStep = 48f
                val gridColor = Color(0xFFE2E8F0)
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
            }
        }
        content()
    }
}

@Composable
fun GlassRaceCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    borderWidth: Dp = 1.dp,
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
        tonalElevation = if (mxColors.isSunMode) 2.dp else 6.dp
    ) {
        content()
    }
}

@Composable
fun Modifier.metallicShimmerSweep(enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val transition = rememberInfiniteTransition(label = "metallic_shimmer")
    val progress by transition.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_x"
    )
    return this.drawWithContent {
        drawContent()
        val xStart = size.width * progress
        val shimmerWidth = size.width * 0.32f
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.18f),
                    Color.Transparent
                ),
                start = Offset(xStart, 0f),
                end = Offset(xStart + shimmerWidth, size.height)
            )
        )
    }
}
