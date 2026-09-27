package com.bipss.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Extended palette that Material's ColorScheme has no slot for — the surface
 * ladder, risk bands, and per-vital identity colours. Read it with
 * `BipssTheme.colors` from any composable inside [BipssTheme].
 */
data class BipssColors(
    val isDark: Boolean,
    val card: Color,
    val field: Color,
    val raised: Color,
    val hairline: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val bandNormal: Color,
    val bandMonitor: Color,
    val bandRisk: Color,
    val heart: Color,
    val spo2: Color,
    val temp: Color,
    val activity: Color,
    val sleep: Color,
    val air: Color,
    val heroGlow: List<Color>,
)

private val DarkExtended = BipssColors(
    isDark = true,
    card = SurfaceDark1, field = SurfaceDark2, raised = SurfaceDark3,
    hairline = OutlineDarkHair, borderStrong = OutlineDarkStrong,
    textPrimary = TextDark1, textSecondary = TextDark2, textTertiary = TextDark3,
    bandNormal = BandNormalDark, bandMonitor = BandMonitorDark, bandRisk = BandRiskDark,
    heart = VitalHeartDark, spo2 = VitalSpo2Dark, temp = VitalTempDark,
    activity = VitalActivityDark, sleep = VitalSleepDark, air = VitalAirDark,
    heroGlow = HeroGlowDark,
)

private val LightExtended = BipssColors(
    isDark = false,
    // Fields are white on the light-grey page — a grey-on-grey field reads as
    // disabled, which is the trap the old theme fell into on dark.
    card = SurfaceLight1, field = SurfaceLight1, raised = SurfaceLight3,
    hairline = OutlineLightHair, borderStrong = OutlineLightStrong,
    textPrimary = TextLight1, textSecondary = TextLight2, textTertiary = TextLight3,
    bandNormal = BandNormalLight, bandMonitor = BandMonitorLight, bandRisk = BandRiskLight,
    heart = VitalHeartLight, spo2 = VitalSpo2Light, temp = VitalTempLight,
    activity = VitalActivityLight, sleep = VitalSleepLight, air = VitalAirLight,
    heroGlow = HeroGlowLight,
)

val LocalBipssColors = staticCompositionLocalOf { DarkExtended }

// Kept for the dashboard screens that still read these directly.
val LocalWarningColor = staticCompositionLocalOf { BandMonitorDark }
val LocalDangerColor  = staticCompositionLocalOf { BandRiskDark }
val LocalMetricGreen  = staticCompositionLocalOf { BandNormalDark }
val LocalAmberOrange  = staticCompositionLocalOf { BandMonitorDark }

private val DarkScheme = darkColorScheme(
    primary            = PrimaryDark,
    onPrimary          = Color.Black,
    primaryContainer   = PrimaryContainerDarkC,
    onPrimaryContainer = TextDark1,
    secondary          = Color(0xFFD4D4D4),
    onSecondary        = Color.Black,
    tertiary           = VitalSleepDark,
    background         = BgDark,
    onBackground       = TextDark1,
    surface            = SurfaceDark1,
    onSurface          = TextDark1,
    surfaceVariant     = SurfaceDark2,
    onSurfaceVariant   = TextDark2,
    // Every container and tint slot is set explicitly: Material's defaults
    // are purple-tinted and would show up on tonal buttons, dialogs and sheets.
    secondaryContainer   = SurfaceDark3,
    onSecondaryContainer = TextDark1,
    tertiaryContainer    = SurfaceDark3,
    onTertiaryContainer  = TextDark1,
    surfaceTint          = Color.Transparent,
    surfaceDim           = BgDark,
    surfaceBright        = SurfaceDark3,
    surfaceContainerLowest = BgDark,
    surfaceContainerLow  = SurfaceDark1,
    surfaceContainer     = SurfaceDark2,
    surfaceContainerHigh = SurfaceDark3,
    surfaceContainerHighest = Color(0xFF303030),
    inverseSurface       = TextDark1,
    inverseOnSurface     = BgDark,
    inversePrimary       = PrimaryLight,
    outline            = OutlineDarkStrong,
    outlineVariant     = OutlineDarkHair,
    error              = BandRiskDark,
    onError            = Color(0xFF2A0508),
    errorContainer     = Color(0xFF3A1418),
    onErrorContainer   = Color(0xFFFFD9DB),
    scrim              = Color(0xCC000000),
)

private val LightScheme = lightColorScheme(
    primary            = PrimaryLight,
    onPrimary          = Color.White,
    primaryContainer   = PrimaryContainerLightC,
    onPrimaryContainer = TextLight1,
    secondary          = Color(0xFF262626),
    onSecondary        = Color.White,
    tertiary           = VitalSleepLight,
    background         = BgLight,
    onBackground       = TextLight1,
    surface            = SurfaceLight1,
    onSurface          = TextLight1,
    surfaceVariant     = SurfaceLight2,
    onSurfaceVariant   = TextLight2,
    secondaryContainer   = SurfaceLight3,
    onSecondaryContainer = TextLight1,
    tertiaryContainer    = SurfaceLight3,
    onTertiaryContainer  = TextLight1,
    surfaceTint          = Color.Transparent,
    surfaceDim           = SurfaceLight3,
    surfaceBright        = SurfaceLight1,
    surfaceContainerLowest = SurfaceLight1,
    surfaceContainerLow  = BgLight,
    surfaceContainer     = SurfaceLight2,
    surfaceContainerHigh = SurfaceLight3,
    surfaceContainerHighest = Color(0xFFDDDDDD),
    inverseSurface       = TextLight1,
    inverseOnSurface     = SurfaceLight1,
    inversePrimary       = PrimaryDark,
    outline            = OutlineLightStrong,
    outlineVariant     = OutlineLightHair,
    error              = BandRiskLight,
    onError            = Color.White,
    errorContainer     = Color(0xFFFFE2E4),
    onErrorContainer   = Color(0xFF5F1116),
    scrim              = Color(0x99000000),
)

@Composable
fun BipssTheme(
    themePreference: ThemePreference = ThemePreference.DARK,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themePreference) {
        ThemePreference.DARK   -> true
        ThemePreference.LIGHT  -> false
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) DarkScheme else LightScheme
    val extended    = if (darkTheme) DarkExtended else LightExtended

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor     = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars     = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalBipssColors  provides extended,
        LocalWarningColor provides extended.bandMonitor,
        LocalDangerColor  provides extended.bandRisk,
        LocalMetricGreen  provides extended.bandNormal,
        LocalAmberOrange  provides extended.bandMonitor,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = BipssTypography,
            shapes      = BipssShapes,
            content     = content
        )
    }
}

/** `BipssTheme.colors.heart`, `BipssTheme.colors.field`, … */
object BipssTheme {
    val colors: BipssColors
        @Composable get() = LocalBipssColors.current
}
