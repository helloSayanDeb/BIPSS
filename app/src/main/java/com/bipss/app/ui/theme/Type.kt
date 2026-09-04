package com.bipss.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Sans = FontFamily.Default

/**
 * Type scale tuned for a data-dense health app: tight tracking on large
 * display text so numbers read as a unit, generous line height on body copy,
 * and an explicit uppercase label style for section headers.
 */
val BipssTypography = Typography(
    displayLarge  = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold,     fontSize = 44.sp, lineHeight = 50.sp, letterSpacing = (-1.0).sp),
    displayMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold,     fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-0.8).sp),
    displaySmall  = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold,     fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),

    headlineLarge  = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold,     fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold,     fontSize = 23.sp, lineHeight = 29.sp, letterSpacing = (-0.4).sp),
    headlineSmall  = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),

    titleLarge  = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.1).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall  = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),

    bodyLarge  = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall  = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),

    labelLarge  = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium,   fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall  = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium,   fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.4.sp),
)

/** Big metric numerals — used by metric tiles and ring gauges. */
val MetricHero = TextStyle(
    fontFamily = Sans, fontWeight = FontWeight.Bold,
    fontSize = 46.sp, lineHeight = 50.sp, letterSpacing = (-1.6).sp
)
val MetricValue = TextStyle(
    fontFamily = Sans, fontWeight = FontWeight.Bold,
    fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-1.0).sp
)
val MetricUnit = TextStyle(
    fontFamily = Sans, fontWeight = FontWeight.SemiBold,
    fontSize = 13.sp, lineHeight = 16.sp
)

/** Uppercase tracked section label, e.g. "MY DEVICE". */
val SectionLabelStyle = TextStyle(
    fontFamily = Sans, fontWeight = FontWeight.Bold,
    fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp
)
