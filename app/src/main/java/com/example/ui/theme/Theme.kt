package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Shapes
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

fun contrastTextOn(background: Color): Color =
    if (background.luminance() > 0.44f) Color(0xFF07090E) else Color.White

@Immutable
data class MxExtendedColors(
    val isSunMode: Boolean,
    val glassCardBg: Color,
    val glassBorder: Color,
    val goldAccent: Color,
    val goldBright: Color,
    val mxBadge: Color,
    val smBadge: Color,
    val telemetryGreen: Color,
    val racingRed: Color,
    val hotlapPurple: Color,
    val subtleText: Color
)

val LocalMxColors = staticCompositionLocalOf {
    MxExtendedColors(
        isSunMode = false,
        glassCardBg = CarbonSurface.copy(alpha = 0.90f),
        glassBorder = GlassBorderDark,
        goldAccent = ChampionshipGold,
        goldBright = ChampionshipGoldBright,
        mxBadge = MxAmber,
        smBadge = NitroCyan,
        telemetryGreen = TelemetryGreen,
        racingRed = RacingCrimson,
        hotlapPurple = HotlapPurple,
        subtleText = TextSecondaryDark
    )
}

private val CarbonNightColorScheme = darkColorScheme(
    primary = ChampionshipGold,
    onPrimary = CarbonVoid,
    primaryContainer = Color(0xFF3A2B09),
    onPrimaryContainer = ChampionshipGoldBright,
    secondary = NitroCyan,
    onSecondary = CarbonVoid,
    secondaryContainer = Color(0xFF0B323D),
    onSecondaryContainer = TextPrimaryDark,
    tertiary = RacingCrimson,
    onTertiary = Color.White,
    background = CarbonVoid,
    onBackground = TextPrimaryDark,
    surface = CarbonSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = CarbonElevated,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = CarbonVoid,
    surfaceContainerLow = Color(0xFF0E131D),
    surfaceContainer = CarbonSurface,
    surfaceContainerHigh = CarbonElevated,
    surfaceContainerHighest = CarbonElevatedHigh,
    outline = GlassBorderDark,
    outlineVariant = GlassBorderDark,
    error = RacingCrimson,
    onError = Color.White
)

private val SunHighContrastColorScheme = lightColorScheme(
    primary = SunPrimaryGold,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFEF3C7),
    onPrimaryContainer = Color(0xFF451A03),
    secondary = SunCyan,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0C4A6E),
    tertiary = SunCrimson,
    onTertiary = Color.White,
    background = SunCanvas,
    onBackground = TextPrimarySun,
    surface = SunSurface,
    onSurface = TextPrimarySun,
    surfaceVariant = SunElevated,
    onSurfaceVariant = TextSecondarySun,
    surfaceContainerLowest = SunSurface,
    surfaceContainerLow = SunCanvas,
    surfaceContainer = SunSurface,
    surfaceContainerHigh = SunElevated,
    surfaceContainerHighest = SunElevatedHigh,
    outline = SunBorder,
    outlineVariant = Color(0xFF94A3B8),
    error = SunCrimson,
    onError = Color.White
)

private val MxShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun mxChipColors(): SelectableChipColors {
    val mx = LocalMxColors.current
    return FilterChipDefaults.filterChipColors(
        containerColor = if (mx.isSunMode) SunElevated else CarbonElevated,
        labelColor = MaterialTheme.colorScheme.onSurface,
        iconColor = mx.goldAccent,
        selectedContainerColor = mx.goldAccent,
        selectedLabelColor = contrastTextOn(mx.goldAccent),
        selectedLeadingIconColor = contrastTextOn(mx.goldAccent)
    )
}

@Composable
fun mxTextFieldColors(): TextFieldColors {
    val mx = LocalMxColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        disabledTextColor = mx.subtleText,
        focusedBorderColor = mx.goldAccent,
        unfocusedBorderColor = mx.glassBorder,
        focusedLabelColor = mx.goldAccent,
        unfocusedLabelColor = mx.subtleText,
        cursorColor = mx.goldAccent,
        focusedContainerColor = if (mx.isSunMode) SunSurface else CarbonVoid.copy(alpha = 0.55f),
        unfocusedContainerColor = if (mx.isSunMode) SunSurface else CarbonVoid.copy(alpha = 0.35f)
    )
}

@Composable
fun mxSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = contrastTextOn(LocalMxColors.current.telemetryGreen),
    checkedTrackColor = LocalMxColors.current.telemetryGreen,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurface,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = LocalMxColors.current.glassBorder
)

@Composable
fun MetropolitanoMxTheme(
    sunMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (sunMode) SunHighContrastColorScheme else CarbonNightColorScheme
    val extended = if (sunMode) {
        MxExtendedColors(
            isSunMode = true,
            glassCardBg = SunSurface.copy(alpha = 0.96f),
            glassBorder = SunBorder,
            goldAccent = SunPrimaryGold,
            goldBright = Color(0xFFD97706),
            mxBadge = Color(0xFFC2410C),
            smBadge = SunCyan,
            telemetryGreen = SunGreen,
            racingRed = SunCrimson,
            hotlapPurple = SunPurple,
            subtleText = TextSecondarySun
        )
    } else {
        MxExtendedColors(
            isSunMode = false,
            glassCardBg = CarbonSurface.copy(alpha = 0.92f),
            glassBorder = GlassBorderDark,
            goldAccent = ChampionshipGold,
            goldBright = ChampionshipGoldBright,
            mxBadge = MxAmber,
            smBadge = NitroCyan,
            telemetryGreen = TelemetryGreen,
            racingRed = RacingCrimson,
            hotlapPurple = HotlapPurple,
            subtleText = TextSecondaryDark
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = MxShapes
    ) {
        CompositionLocalProvider(
            LocalMxColors provides extended,
            LocalContentColor provides colorScheme.onBackground,
            content = content
        )
    }
}
