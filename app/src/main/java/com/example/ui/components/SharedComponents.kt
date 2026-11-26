package com.example.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.SurfaceType
import com.example.ui.theme.LocalMxColors

@Composable
fun MxTopHeader(
    title: String,
    isOnline: Boolean,
    sunMode: Boolean,
    isSpanish: Boolean,
    onBack: (() -> Unit)? = null,
    onToggleSunMode: () -> Unit,
    onToggleLang: () -> Unit,
    paMuted: Boolean? = null,
    onTogglePaMute: (() -> Unit)? = null
) {
    val mx = LocalMxColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("nav_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = if (isSpanish) "Volver" else "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isOnline) mx.telemetryGreen.copy(alpha = 0.16f)
                        else mx.racingRed.copy(alpha = 0.2f)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = if (isOnline) Icons.Default.Wifi else Icons.Default.WifiOff,
                    contentDescription = if (isOnline) "Online" else "Offline",
                    tint = if (isOnline) mx.telemetryGreen else mx.racingRed,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            if (paMuted != null && onTogglePaMute != null) {
                IconButton(
                    onClick = onTogglePaMute,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("toggle_pa_audio_button")
                ) {
                    Icon(
                        imageVector = if (paMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (paMuted) "Unmute PA" else "Mute PA",
                        tint = if (paMuted) mx.racingRed else mx.telemetryGreen
                    )
                }
            }
            IconButton(
                onClick = onToggleLang,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toggle_lang_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = if (isSpanish) "Cambiar idioma" else "Switch language",
                    tint = mx.goldAccent
                )
            }
            IconButton(
                onClick = onToggleSunMode,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toggle_sun_mode_button")
            ) {
                Icon(
                    imageVector = if (sunMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                    contentDescription = if (sunMode) "Dark Carbon" else "Sun Mode",
                    tint = mx.goldAccent
                )
            }
        }
    }
}

@Composable
fun SurfaceBadge(surface: SurfaceType, modifier: Modifier = Modifier) {
    val mx = LocalMxColors.current
    val color = if (surface == SurfaceType.MX) mx.mxBadge else mx.smBadge
    val label = if (surface == SurfaceType.MX) "MX TIERRA" else "SM ASFALTO"
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun PulsingLiveDot(modifier: Modifier = Modifier) {
    val mx = LocalMxColors.current
    val transition = rememberInfiniteTransition(label = "live_dot")
    val dotColor by transition.animateColor(
        initialValue = mx.racingRed,
        targetValue = Color.White,
        animationSpec = infiniteRepeatable(
            animation = tween(650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(dotColor)
    )
}

@Composable
fun Base64ImageView(
    base64: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val bitmap = remember(base64) {
        if (base64.isBlank()) null
        else try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale
        )
    }
}
