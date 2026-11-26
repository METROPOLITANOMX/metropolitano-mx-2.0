package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
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
    if (background.luminance() > 0.45f) Color(0xFF0A0C10) else Color.White

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
        glassCardBg = CarbonSurface.copy(alpha = 0.84f),
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
    primaryContainer = Color(0xFF33260B),
    onPrimaryContainer = ChampionshipGoldBright,
    secondary = NitroCyan,
    onSecondary = CarbonVoid,
    secondaryContainer = Color(0xFF0A2A33),
    onSecondaryContainer = NitroCyan,
    tertiary = RacingCrimson,
    onTertiary = Color.White,
    background = CarbonVoid,
    onBackground = TextPrimaryDark,
    surface = CarbonSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = CarbonElevated,
    onSurfaceVariant = TextSecondaryDark,
    outline = GlassBorderDark,
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
    outline = SunBorder,
    error = SunCrimson,
    onError = Color.White
)

private val MxShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
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
            glassCardBg = CarbonSurface.copy(alpha = 0.88f),
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
