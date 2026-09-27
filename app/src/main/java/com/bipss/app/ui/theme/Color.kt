package com.bipss.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * BIPSS 4 design system — monochrome, matching the black-and-white logo.
 *
 * Rules this palette follows:
 *  - Neutrals are true greys and the brand colour is white on black (black on
 *    white in light mode). Hue is reserved for meaning: risk bands, vital
 *    identity, SOS.
 *  - Every text token clears WCAG AA against the surface it sits on.
 *  - Surfaces form a visible ladder (background < card < field < raised), so
 *    an input never disappears into the page.
 */

// ── Dark surfaces (the default theme) ───────────────────────────────────────
val BgDark            = Color(0xFF000000)   // page — the logo's black
val SurfaceDark1      = Color(0xFF111111)   // cards
val SurfaceDark2      = Color(0xFF1A1A1A)   // input fields / nested cards
val SurfaceDark3      = Color(0xFF262626)   // pressed / selected
val OutlineDarkHair   = Color(0xFF242424)   // 1dp card hairline
val OutlineDarkStrong = Color(0xFF3D3D3D)   // field borders, dividers that must read

val TextDark1 = Color(0xFFFFFFFF)   // primary text         21:1 on BgDark
val TextDark2 = Color(0xFFB4B4B4)   // secondary text       ~10:1 on BgDark
val TextDark3 = Color(0xFF8A8A8A)   // placeholder/disabled ~5.5:1 on BgDark

// ── Light surfaces ──────────────────────────────────────────────────────────
val BgLight            = Color(0xFFF5F5F5)
val SurfaceLight1      = Color(0xFFFFFFFF)
val SurfaceLight2      = Color(0xFFF0F0F0)
val SurfaceLight3      = Color(0xFFE6E6E6)
val OutlineLightHair   = Color(0xFFE3E3E3)
val OutlineLightStrong = Color(0xFFC2C2C2)

val TextLight1 = Color(0xFF0A0A0A)
val TextLight2 = Color(0xFF5C5C5C)
val TextLight3 = Color(0xFF8C8C8C)

// ── Brand ───────────────────────────────────────────────────────────────────
val PrimaryLight          = Color(0xFF000000)
val PrimaryDark           = Color(0xFFFFFFFF)
val PrimaryContainerLightC = Color(0xFFEBEBEB)
val PrimaryContainerDarkC  = Color(0xFF1F1F1F)
val AccentTeal            = Color(0xFF00C9A7)

// ── Risk bands (RiskBand: NORMAL | MONITOR | AT_RISK) ───────────────────────
val BandNormalDark  = Color(0xFF34D399)
val BandNormalLight = Color(0xFF0F9D58)
val BandMonitorDark  = Color(0xFFFBBF24)
val BandMonitorLight = Color(0xFFB4690E)
val BandRiskDark  = Color(0xFFFF6B72)
val BandRiskLight = Color(0xFFD22D38)

// ── Vital identities (consistent across every screen) ───────────────────────
val VitalHeartDark   = Color(0xFFFF6B7D); val VitalHeartLight   = Color(0xFFE11D48)
val VitalSpo2Dark    = Color(0xFF4CC2FF); val VitalSpo2Light    = Color(0xFF0369A1)
val VitalTempDark    = Color(0xFFFFA45C); val VitalTempLight    = Color(0xFFC2410C)
val VitalActivityDark = Color(0xFFB99BFF); val VitalActivityLight = Color(0xFF6D28D9)
val VitalSleepDark   = Color(0xFF8B93FF); val VitalSleepLight   = Color(0xFF4F46E5)
val VitalAirDark     = Color(0xFF5EEAD4); val VitalAirLight     = Color(0xFF0F766E)

// ── Degraded-mode header tints (§8 of the software plan) ────────────────────
val ModeOptimal   = Color(0xFF34D399)   // band + network
val ModeLocalOnly = Color(0xFFFBBF24)   // band, no network
val ModePhoneOnly = Color(0xFF9E9E9E)   // no band, network
val ModeMinimal   = Color(0xFFE8833A)   // neither

// ── SOS (fixed high-visibility scheme, deliberately theme-invariant) ────────
val SOSBackgroundLight = Color(0xFFFFF1F2)
val SOSBackgroundDark  = Color(0xFF2A1416)
val SOSRed             = Color(0xFFE5262F)

// ── Gradients ───────────────────────────────────────────────────────────────
val HeroGlowDark       = listOf(Color(0x1AFFFFFF), Color(0x00FFFFFF))
val HeroGlowLight      = listOf(Color(0x0D000000), Color(0x00000000))

// ── Legacy aliases ──────────────────────────────────────────────────────────
// Dashboard/Insights/Chat/AppShell still reference the old Goose token names
// directly. They now resolve to the palette above so those screens pick up
// the new colour language until each is rebuilt on the component library.
val GooseDeviceBackground = BgDark
val GooseAppBackground    = BgDark
val CardBaseFillProminent = Color(0x14FFFFFF)
val CardBaseFill          = Color(0x0EFFFFFF)
val SystemGreen  = BandNormalDark
val SystemRed    = BandRiskDark
val SystemPink   = VitalHeartDark
val SystemCyan   = VitalSpo2Dark
val SystemOrange = VitalTempDark
val SleepTint     = VitalSleepDark
val RecoveryTint  = BandNormalDark
val StrainTint    = VitalTempDark
val StressTint    = VitalHeartDark
val EnergyTint    = BandNormalDark
val CardioLoadTint = VitalHeartDark
val GoosePrimary   = TextDark1
val GooseSecondary = TextDark2
val GooseTertiary  = TextDark3
val CardBorderDefault = OutlineDarkHair
val BackgroundDeep       = BgDark
val BackgroundDark       = BgDark
val SurfaceDark          = SurfaceDark1
val SurfaceVariantDark   = SurfaceDark2
val GlassCardFill        = CardBaseFill
val GlassCardBorder      = OutlineDarkHair
val RecoveryGreenBright  = BandNormalDark
val RecoveryGreenDark    = Color(0xFF15805A)
val StrainBlueBright     = PrimaryDark
val StrainBlueDark       = PrimaryLight
val SleepPurpleBright    = VitalSleepDark
val SleepPurpleDark      = Color(0xFF3F3FA8)
val AmberOrange          = BandMonitorDark
val MetricGreen          = BandNormalDark
val DangerDark           = BandRiskDark
val OnSurfaceDark        = TextDark1
val OnSurfaceVariantDark = TextDark2
val OutlineDark          = OutlineDarkHair
val BackgroundLight          = BgLight
val SurfaceLight             = SurfaceLight1
val SurfaceVariantLight      = SurfaceLight2
val OnSurfaceLight           = TextLight1
val OnSurfaceVariantLight    = TextLight2
val OutlineLight             = OutlineLightHair
val OutlineVariantLight      = OutlineLightHair
val OutlineVariantDark       = OutlineDarkHair
val WarningLight             = BandMonitorLight
val WarningDark              = BandMonitorDark
val DangerLight              = BandRiskLight
val OnPrimaryLight           = Color.White
val OnPrimaryDark            = Color.White
val PrimaryContainerLight    = PrimaryContainerLightC
val PrimaryContainerDark     = PrimaryContainerDarkC
val OnPrimaryContainerLight  = TextLight1
val OnPrimaryContainerDark   = TextDark1
val AmberOrangeLight         = BandMonitorLight
val AmberOrangeDark          = BandMonitorDark
val TealGreenLight           = AccentTeal
val TealGreenDark            = AccentTeal
val MetricGreenLight         = BandNormalLight

// Names used by Settings/SOS built in the previous pass
val BluePrimary    = PrimaryLight
val GreenSecondary = AccentTeal
val StatusNormal   = BandNormalLight
val StatusMonitor  = BandMonitorLight
val StatusAtRisk   = BandRiskLight
val VitalHeartRate = VitalHeartLight
val VitalSpO2      = VitalSpo2Light
val VitalBodyTemp  = VitalTempLight
val VitalActivity  = VitalActivityLight
val BackgroundLightBrand       = BgLight
val SurfaceLightBrand          = SurfaceLight1
val SurfaceVariantLightBrand   = SurfaceLight2
val OnSurfaceLightBrand        = TextLight1
val OnSurfaceVariantLightBrand = TextLight2
val BackgroundDarkBrand        = BgDark
val SurfaceDarkBrand           = SurfaceDark1
val SurfaceVariantDarkBrand    = SurfaceDark2
val OnSurfaceDarkBrand         = TextDark1
val OnSurfaceVariantDarkBrand  = TextDark2
