package com.bipss.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.data.MetricTypes
import com.bipss.app.domain.RiskLevel
import com.bipss.app.domain.RiskScore
import com.bipss.app.navigation.LocalScaffoldPadding
import com.bipss.app.navigation.Routes
import com.bipss.app.sos.*
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.SOSRed
import com.bipss.app.ui.theme.Space
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun DashboardTodayScreen(
    navController: NavController,
    vm: DashboardViewModel = koinViewModel(),
    sosVm: SosViewModel = koinViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val sosState by sosVm.state.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors

    val locPermState = rememberLocationPermission()

    // The first sync fires at launch, which on a fresh install is before the
    // location grant has landed - leaving weather and air quality unresolved
    // and the alert list unfiltered. Re-sync as soon as location becomes
    // available rather than waiting for the next periodic (15-minute) sync.
    androidx.compose.runtime.LaunchedEffect(locPermState.isGranted) {
        if (locPermState.isGranted && state.env.latitude == null) {
            vm.refreshEnvironment()
        }
    }

    // Every state has a visible screen, including the failure ones - an
    // unrendered error used to leave SOS silently dead until the app restarted.
    when (val s = sosState) {
        is SosUiState.Countdown  -> SosCountdownOverlay(state = s, onCancel = { sosVm.cancelSos() })
        is SosUiState.Sending    -> SosSendingSpinner()
        is SosUiState.Sent       -> SosSentOverlay(state = s, onDismiss = { sosVm.dismissResult() })
        is SosUiState.NotSent    -> SosNotSentOverlay(state = s, onDismiss = { sosVm.dismissResult() })
        is SosUiState.FalseAlarm -> SosFalseAlarmOverlay(onDismiss = { sosVm.dismissResult() })
        SosUiState.Idle          -> Unit
    }

    if (!locPermState.isGranted && !state.locationPermissionSkipped) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(LocalScaffoldPadding.current)
                .padding(Space.xl),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.LocalHospital,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(Space.xl))
                Text(
                    "Location Permission",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.textPrimary
                )
                Spacer(Modifier.height(Space.m))
                Text(
                    "We need your location to find nearby hospitals for SOS and get accurate weather/AQI for the risk engine.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(Space.xxl))
                androidx.compose.material3.Button(
                    onClick = { locPermState.request() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Permission")
                }
                Spacer(Modifier.height(Space.m))
                androidx.compose.material3.TextButton(
                    onClick = { vm.skipLocationPermission() }
                ) {
                    Text("Continue without location", color = colors.textTertiary)
                }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(LocalScaffoldPadding.current)
                .padding(horizontal = Space.gutter)
        ) {
            Spacer(Modifier.height(Space.l))

            // -- Header ------------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(size = 44.dp)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Today",
                        style = MaterialTheme.typography.headlineLarge,
                        color = colors.textPrimary
                    )
                    Text(
                        if (state.env.fetchedAtMs == 0L) "Environment not synced yet"
                        else "Environment synced ${state.env.dataAgeLabel.lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary
                    )
                }
                DataModeChip(state.dataMode)
            }

            Spacer(Modifier.height(Space.xl))

            // -- Hero score --------------------------------------------------
            val hero = state.heroScore
            if (hero != null) {
                HeroScoreCard(hero)
            } else {
                BipssCard {
                    Text(
                        "Collecting your first readings",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        "Scores appear once the band streams data or the environment sync completes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
            }

            // Only shown while the baseline is still widening.
            Spacer(Modifier.height(Space.m))
            state.baselineDays?.let { BaselineProgressChip(it) }

            // -- Hydration ---------------------------------------------------
            Spacer(Modifier.height(Space.l))
            WaterLogCard(
                lastLogMs = state.lastWaterLogMs,
                totalMlToday = state.waterLogTotalMlToday,
                onLogWater = { amount -> vm.logWaterIntake(amount) }
            )

            // -- Vitals ------------------------------------------------------
            Spacer(Modifier.height(Space.xxl))
            SectionHeader("Vitals")
            Spacer(Modifier.height(Space.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                MetricTile(
                    label = "Heart rate",
                    value = state.heartRate?.toString() ?: "—",
                    unit = "bpm",
                    icon = Icons.Rounded.Favorite,
                    tint = colors.heart,
                    history = state.heartRateHistory,
                    caption = if (state.heartRate != null) "Tap for history" else "Waiting for readings",
                    fidelity = if (state.hrFromPipeline) com.bipss.app.domain.Fidelity.PRODUCTION
                               else com.bipss.app.domain.Fidelity.ESTIMATE,
                    modifier = Modifier.weight(1f),
                    onClick = { navController.navigate(Routes.metricDetail(MetricTypes.HR)) }
                )
                MetricTile(
                    label = "Blood oxygen",
                    value = state.spO2?.toString() ?: "—",
                    unit = "%",
                    icon = Icons.Rounded.Bloodtype,
                    tint = colors.spo2,
                    history = state.spO2History,
                    caption = if (state.spO2 != null) "Tap for history" else "Waiting for readings",
                    fidelity = com.bipss.app.domain.Fidelity.ESTIMATE,
                    modifier = Modifier.weight(1f),
                    onClick = { navController.navigate(Routes.metricDetail(MetricTypes.SPO2)) }
                )
            }
            Spacer(Modifier.height(Space.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                MetricTile(
                    label = "Skin temp",
                    value = state.skinTempC?.let { "%.1f".format(it) } ?: "—",
                    unit = "°C",
                    icon = Icons.Rounded.DeviceThermostat,
                    tint = colors.temp,
                    history = state.skinTempHistory,
                    caption = if (state.skinTempC != null) "Tap for history" else "Waiting for readings",
                    modifier = Modifier.weight(1f),
                    onClick = { navController.navigate(Routes.metricDetail(MetricTypes.SKIN_TEMP)) }
                )
                MetricTile(
                    label = "Pulse rate variability",
                    value = state.prvRmssd?.toString() ?: "—",
                    unit = "ms",
                    icon = Icons.Rounded.MonitorHeart,
                    tint = colors.activity,
                    history = emptyList(),
                    caption = if (state.prvRmssd != null) "Tap for history" else "Waiting for readings",
                    fidelity = com.bipss.app.domain.Fidelity.ESTIMATE,
                    modifier = Modifier.weight(1f),
                    onClick = { navController.navigate(Routes.metricDetail(MetricTypes.PRV)) }
                )
            }

            // -- Wellness pillars --------------------------------------------
            val pillars = state.wellnessPillars
            if (pillars.isNotEmpty()) {
                Spacer(Modifier.height(Space.xxl))
                SectionHeader("Your day")
                Spacer(Modifier.height(Space.m))
                pillars.forEach { pillar ->
                    ScoreRow(
                        score = pillar,
                        onClick = when (pillar.type) {
                            com.bipss.app.domain.RiskScoreType.SLEEP -> { { navController.navigate(Routes.SLEEP_DETAIL) } }
                            com.bipss.app.domain.RiskScoreType.RECOVERY -> { { navController.navigate(Routes.RECOVERY_DETAIL) } }
                            com.bipss.app.domain.RiskScoreType.STRAIN -> { { navController.navigate(Routes.STRAIN_DETAIL) } }
                            else -> null
                        }
                    )
                    Spacer(Modifier.height(Space.m))
                }
            }

            // -- Hazard domains ----------------------------------------------
            val hazards = state.hazardDomains.filter { it != hero }
            if (hazards.isNotEmpty()) {
                Spacer(Modifier.height(Space.l))
                SectionHeader("Risk domains")
                Spacer(Modifier.height(Space.m))
                hazards.forEach { risk ->
                    ScoreRow(risk)
                    Spacer(Modifier.height(Space.m))
                }
            }

            // -- Experimental -----------------------------------------------------
            // Apart from the risk domains: it is never a verdict about risk.
            val experimental = state.experimentalScores
            if (experimental.isNotEmpty()) {
                Spacer(Modifier.height(Space.l))
                SectionHeader("Experimental")
                Spacer(Modifier.height(Space.m))
                experimental.forEach { e ->
                    ScoreRow(e)
                    Spacer(Modifier.height(Space.s))
                    Text(
                        com.bipss.app.domain.ali.AutonomicLoad.TOOLTIP,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary
                    )
                    Spacer(Modifier.height(Space.m))
                }
            }

            // -- Environment ---------------------------------------------------
            Spacer(Modifier.height(Space.l))
            SectionHeader("Environment")
            Spacer(Modifier.height(Space.m))
            BipssCard {
                Row {
                    EnvStat(
                        "Temperature",
                        state.env.ambientTempC?.let { "${it.toInt()}°C" } ?: "—",
                        Icons.Rounded.DeviceThermostat, colors.temp, Modifier.weight(1f)
                    )
                    EnvStat(
                        "Humidity",
                        state.env.humidityPercent?.let { "${it.toInt()}%" } ?: "—",
                        Icons.Rounded.WaterDrop, colors.spo2, Modifier.weight(1f)
                    )
                    EnvStat(
                        "AQI",
                        state.env.aqiValue?.toString() ?: "—",
                        Icons.Rounded.Air, colors.air, Modifier.weight(1f)
                    )
                }
                if (state.env.fetchedAtMs == 0L) {
                    Spacer(Modifier.height(Space.m))
                    InfoBanner(
                        text = "No environment data yet. Values appear after the first sync.",
                        icon = Icons.Rounded.CloudOff,
                        tint = colors.textTertiary
                    )
                } else if (state.env.isStale) {
                    // Only warn when the reading is actually old. A snapshot
                    // read back from cache seconds after it was fetched is
                    // still current, and telling the user to reconnect for a
                    // live reading they already have reads as a fault.
                    Spacer(Modifier.height(Space.m))
                    InfoBanner(
                        text = "Conditions from ${state.env.dataAgeLabel.lowercase()}. " +
                            "Reconnect for a live reading.",
                        icon = Icons.Rounded.CloudOff,
                        tint = colors.bandMonitor
                    )
                }
            }

            Spacer(Modifier.height(120.dp))
        }

        FloatingActionButton(
            // Always starts the countdown at once. Missing permissions or
            // contacts are shown inside the countdown, never in its way.
            onClick = { sosVm.triggerSos(SosTriggerSource.MANUAL) },
            containerColor = SOSRed,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(LocalScaffoldPadding.current)
                .padding(Space.xxl)
        ) {
            Icon(Icons.Rounded.LocalHospital, contentDescription = "Emergency SOS")
        }
    }
}

/**
 * The headline card.
 *
 * Uses [ScoreRing], which renders an em dash rather than a number whenever the
 * score could not actually be computed - so this card can never present an
 * invented figure as a measurement.
 */
@Composable
private fun HeroScoreCard(score: RiskScore) {
    val colors = BipssTheme.colors
    val color = score.level.bandColor()

    BipssCard(accent = color, contentPadding = PaddingValues(Space.xl)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(score = score, color = color)
            Spacer(Modifier.width(Space.xl))
            Column(Modifier.weight(1f)) {
                Text(
                    score.type.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary
                )
                if (score.type.question.isNotBlank()) {
                    Text(
                        score.type.question,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary
                    )
                }
                Spacer(Modifier.height(Space.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (score.hasNumber) {
                        StatusPill(score.levelLabel ?: score.level.bandLabel(), color)
                        Spacer(Modifier.width(Space.s))
                    }
                    ScoreBadges(score)
                }
                ScoreReasonLine(score)
            }
        }

        AvailabilityChip(score.availability, Modifier.padding(top = Space.m))
        ConfidenceBar(score.confidence, Modifier.padding(top = Space.s))

        if (score.factors.isNotEmpty()) {
            Spacer(Modifier.height(Space.l))
            HairlineDivider()
            Spacer(Modifier.height(Space.m))
            Text(
                "WHAT'S DRIVING IT",
                style = com.bipss.app.ui.theme.SectionLabelStyle,
                color = colors.textTertiary
            )
            Spacer(Modifier.height(Space.s))
            FlowPills(score.factors.map { it.label to it.isElevated })
        }
    }
}

/** Compact row for a non-hero domain. Renders an em dash when uncomputable. */
@Composable
private fun ScoreRow(score: RiskScore, onClick: (() -> Unit)? = null) {
    val colors = BipssTheme.colors
    val color = scoreColor(score)

    BipssCard(onClick = onClick, contentPadding = PaddingValues(Space.l)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(score = score, size = 46.dp, strokeWidth = 5.dp, color = color)
            Spacer(Modifier.width(Space.l))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        score.type.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(Space.s))
                    ScoreBadges(score)
                }
                if (score.hasNumber) {
                    Text(
                        score.levelLabel ?: score.level.bandLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = color
                    )
                    // A partial score in a compact row previously showed a bare
                    // band label, so "Normal" computed from environment alone
                    // was indistinguishable from one that had seen the wearer.
                    ScoreReasonLine(score)
                } else {
                    ScoreReasonLine(score)
                }
            }
        }
    }
}

/**
 * Hydration logging.
 *
 * Until this existed nothing in the UI called logWaterIntake, so the hydration
 * input could never change - it sat at its default forever and showed up as a
 * permanent "Hydration Low" driver on every Heat Stress score.
 */
@Composable
private fun WaterLogCard(
    lastLogMs: Long?,
    totalMlToday: Int,
    onLogWater: (Int) -> Unit
) {
    val colors = BipssTheme.colors
    // Recomputed on a ticking clock - a label remembered only on lastLogMs
    // froze at whatever it said when the drink was logged.
    val nowMs = rememberNowMs()
    val label = if (lastLogMs == null) "Not logged yet" else {
        val mins = ((nowMs - lastLogMs) / 60_000).coerceAtLeast(0L)
        when {
            mins < 1L  -> "Just now"
            mins < 60L -> "$mins min ago"
            else       -> "${mins / 60}h ${mins % 60}m ago"
        }
    }

    BipssCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconBadge(Icons.Rounded.WaterDrop, colors.spo2)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text("Hydration", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                Text(
                    if (totalMlToday > 0) "Last water: $label  ·  ${totalMlToday}mL today" else "Last water: $label",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }
        Spacer(Modifier.height(Space.m))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = { onLogWater(250) }, modifier = Modifier.weight(1f)) {
                Text("+ 250mL")
            }
            FilledTonalButton(onClick = { onLogWater(500) }, modifier = Modifier.weight(1f)) {
                Text("+ 500mL")
            }
        }
        if (lastLogMs == null) {
            Spacer(Modifier.height(Space.s))
            Text(
                "Hydration is left out of your Heat Stress score until you log a drink - " +
                    "not having logged one isn't evidence of dehydration.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary
            )
        }
    }
}

@Composable
private fun EnvStat(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val colors = BipssTheme.colors
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(Space.s))
        Text(value, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
    }
}

/**
 * The colour a score is drawn in. Hazard verdict colours only for hazards:
 * Strain is load (the activity colour, whatever its size) and an experimental
 * indicator is neutral, never green-amber-red.
 */
@Composable
internal fun scoreColor(score: RiskScore): Color {
    val colors = BipssTheme.colors
    return when {
        !score.hasNumber -> colors.textTertiary
        score.type == com.bipss.app.domain.RiskScoreType.STRAIN -> colors.activity
        score.type.category == com.bipss.app.domain.ScoreCategory.EXPERIMENTAL -> colors.textSecondary
        else -> score.level.bandColor()
    }
}

/** Fidelity badge, plus a Provisional chip while the baseline behind the score is provisional. */
@Composable
internal fun ScoreBadges(score: RiskScore) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FidelityBadge(score.fidelity)
        if (score.provisional) {
            Spacer(Modifier.width(Space.s))
            StatusPill("Provisional", BipssTheme.colors.bandMonitor)
        }
    }
}

@Composable
internal fun RiskLevel.bandColor(): Color {
    val colors = BipssTheme.colors
    return when (this) {
        RiskLevel.LOW    -> colors.bandNormal
        RiskLevel.MEDIUM -> colors.bandMonitor
        RiskLevel.HIGH   -> colors.bandRisk
    }
}

internal fun RiskLevel.bandLabel(): String = when (this) {
    RiskLevel.LOW    -> "Normal"
    RiskLevel.MEDIUM -> "Monitor"
    RiskLevel.HIGH   -> "At risk"
}
