package com.example.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.SurfaceType
import com.example.ui.theme.LocalMxColors
import com.example.ui.theme.contrastTextOn

fun triggerRacingClickVibration(context: Context) {
    try {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(22L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(22L)
            }
        }
    } catch (_: Exception) {
    }
}

/**
 * 3D Glossy Metallic Racing Button with bottom bevel extrusion, animated shine sweep,
 * spring depression physics, and crisp tactile vibration feedback.
 */
@Composable
fun Mx3dButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = LocalMxColors.current.goldAccent,
    contentColor: Color = contrastTextOn(containerColor),
    enabled: Boolean = true,
    shimmer: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressOffset by animateDpAsState(
        targetValue = if (isPressed && enabled) 4.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "btn_press_offset"
    )
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.985f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "btn_scale"
    )

    val effectiveBase = if (enabled) containerColor else containerColor.copy(alpha = 0.42f)
    val effectiveText = if (enabled) contentColor else contentColor.copy(alpha = 0.65f)
    val topGloss = lerp(effectiveBase, Color.White, 0.34f)
    val bottomShade = lerp(effectiveBase, Color.Black, 0.24f)
    val bevelColor = lerp(effectiveBase, Color.Black, 0.54f)
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .padding(bottom = 5.dp)
    ) {
        // 3D Bottom Bevel / Extruded Lip
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = 5.dp)
                .clip(shape)
                .background(bevelColor)
        )
        // Raised Glossy Metallic Cap
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = pressOffset)
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(topGloss, effectiveBase, bottomShade)
                    )
                )
                .metallicShimmerSweep(enabled = shimmer && enabled)
                .border(1.dp, Color.White.copy(alpha = if (enabled) 0.42f else 0.14f), shape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = Color.White),
                    enabled = enabled,
                    role = Role.Button
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    triggerRacingClickVibration(context)
                    onClick()
                }
                .padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompositionLocalProvider(LocalContentColor provides effectiveText) {
                content()
            }
        }
    }
}

/**
 * 3D Dark Carbon / Metallic Outlined Racing Button with glossy top rim highlight and haptic tap.
 */
@Composable
fun Mx3dOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    borderColor: Color = LocalMxColors.current.goldAccent,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit
) {
    val mx = LocalMxColors.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressOffset by animateDpAsState(
        targetValue = if (isPressed && enabled) 3.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "obtn_press_offset"
    )
    val shape = RoundedCornerShape(14.dp)
    val topFace = if (mx.isSunMode) Color(0xFFFFFFFF) else Color(0xFF232C3E)
    val bottomFace = if (mx.isSunMode) Color(0xFFE2E8F0) else Color(0xFF141925)
    val bevelFace = if (mx.isSunMode) Color(0xFF94A3B8) else Color(0xFF070A10)

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .padding(bottom = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = 4.dp)
                .clip(shape)
                .background(bevelFace)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = pressOffset)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(topFace, bottomFace)))
                .border(1.3.dp, borderColor.copy(alpha = if (enabled) 0.78f else 0.30f), shape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = borderColor),
                    enabled = enabled,
                    role = Role.Button
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    triggerRacingClickVibration(context)
                    onClick()
                }
                .padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val effective = if (enabled) contentColor else contentColor.copy(alpha = 0.45f)
            CompositionLocalProvider(LocalContentColor provides effective) {
                content()
            }
        }
    }
}

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
    val context = LocalContext.current
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
                    onClick = {
                        triggerRacingClickVibration(context)
                        onBack()
                    },
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
                    onClick = {
                        triggerRacingClickVibration(context)
                        onTogglePaMute()
                    },
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
                onClick = {
                    triggerRacingClickVibration(context)
                    onToggleLang()
                },
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
                onClick = {
                    triggerRacingClickVibration(context)
                    onToggleSunMode()
                },
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

/**
 * Prevents Android screen lock/sleep while the composable is active (used on Antenna screens).
 */
@Composable
fun KeepScreenOnEffect(enabled: Boolean = true) {
    val context = LocalContext.current
    androidx.compose.runtime.DisposableEffect(enabled, context) {
        val window = (context as? android.app.Activity)?.window
        if (enabled) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (enabled) {
                window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

@Composable
fun PocketModeOverlay(
    titleLocked: String,
    isSpanish: Boolean,
    audioRoute: com.example.engine.AudioOutputRoute,
    onSelectAudioRoute: (com.example.engine.AudioOutputRoute) -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.activity.compose.BackHandler { onUnlock() }
    Box(
        modifier = modifier
            .background(Color.Black)
            .testTag("pocket_mode_overlay")
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "🔒",
                style = MaterialTheme.typography.displaySmall
            )
            Text(
                text = titleLocked,
                color = Color(0xFF94A3B8),
                style = MaterialTheme.typography.titleMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(
                text = if (isSpanish) {
                    "Audio continuo activo aunque apagues pantalla\nSalida de audio:"
                } else {
                    "Continuous audio active even with screen off\nAudio output:"
                },
                color = Color(0xFF64748B),
                style = MaterialTheme.typography.labelMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val options = listOf(
                    com.example.engine.AudioOutputRoute.AUTO to "Auto",
                    com.example.engine.AudioOutputRoute.SPEAKER to (if (isSpanish) "Altavoz" else "Speaker"),
                    com.example.engine.AudioOutputRoute.HEADSET to (if (isSpanish) "Cascos" else "Headset")
                )
                options.forEach { (route, label) ->
                    val selected = audioRoute == route
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Color(0xFFF5B731) else Color(0xFF1E293B))
                            .clickable { onSelectAudioRoute(route) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (selected) Color.Black else Color(0xFFCBD5E1),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.size(12.dp))
            Mx3dOutlinedButton(
                onClick = onUnlock,
                borderColor = Color(0xFF475569),
                contentColor = Color(0xFFE2E8F0),
                modifier = Modifier.width(220.dp)
            ) {
                Text(if (isSpanish) "Salir de Modo Bolsillo" else "Unlock Pocket Mode")
            }
        }
    }
}
