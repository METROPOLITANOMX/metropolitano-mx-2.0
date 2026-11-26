package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.CarbonVoid
import com.example.ui.theme.LocalMxColors
import com.example.ui.theme.SunCanvas

@Composable
fun CarbonStadiumBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val mxColors = LocalMxColors.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (mxColors.isSunMode) SunCanvas else CarbonVoid)
    ) {
        // Night Motocross Stadium Track backdrop with clean dark carbon veil for readability
        Image(
            painter = painterResource(id = R.drawable.img_mx_stadium_backdrop),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (mxColors.isSunMode) 0.20f else 0.86f)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (mxColors.isSunMode) {
                        Brush.verticalGradient(
                            colors = listOf(
                                SunCanvas.copy(alpha = 0.85f),
                                SunCanvas.copy(alpha = 0.78f),
                                SunCanvas.copy(alpha = 0.88f)
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(
                                CarbonVoid.copy(alpha = 0.54f),
                                CarbonVoid.copy(alpha = 0.42f),
                                CarbonVoid.copy(alpha = 0.72f)
                            )
                        )
                    }
                )
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (!mxColors.isSunMode) {
                // Subtle neutral carbon telemetry weave (no colored stripes)
                val step = 32f
                val weaveColor = Color(0xFF0F141E)
                var y = 0f
                var row = 0
                while (y < h) {
                    var x = 0f
                    var col = 0
                    while (x < w) {
                        if ((row + col) % 2 == 0) {
                            drawRect(
                                color = weaveColor.copy(alpha = 0.14f),
                                topLeft = Offset(x, y),
                                size = Size(step * 0.85f, step * 0.85f)
                            )
                        }
                        x += step
                        col++
                    }
                    y += step
                    row++
                }

                // Soft neutral top stadium floodlight haze
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.5f, h * 0.06f),
                        radius = w * 0.85f
                    ),
                    center = Offset(w * 0.5f, h * 0.06f),
                    radius = w * 0.85f
                )
            } else {
                val gridStep = 44f
                val gridColor = Color(0xFFCBD5E1).copy(alpha = 0.50f)
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
