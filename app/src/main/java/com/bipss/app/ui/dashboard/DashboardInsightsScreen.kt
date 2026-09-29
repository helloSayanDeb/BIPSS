package com.bipss.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Park
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Coronavirus
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import com.bipss.app.domain.time.relativeAgeLabel
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.domain.RiskScore
import com.bipss.app.domain.environmental.AlertSeverity
import com.bipss.app.domain.environmental.AqiSource
import com.bipss.app.domain.environmental.EnvironmentContext
import com.bipss.app.domain.environmental.EnvironmentalSnapshot
import com.bipss.app.domain.environmental.RankedAlert
import com.bipss.app.navigation.LocalScaffoldPadding
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import com.google.accompanist.permissions.*
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun DashboardInsightsScreen(
    navController: NavController,
    vm: DashboardViewModel = koinViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors
    val env = state.env
    val context = LocalContext.current

    val locationPermission = rememberLocationPermission()
    val hasLocation = locationPermission.isGranted

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(LocalScaffoldPadding.current)
            .padding(horizontal = Space.gutter)
    ) {
        Spacer(Modifier.height(Space.l))
        Text("Insights", style = MaterialTheme.typography.headlineLarge, color = colors.textPrimary)
        Text(
            "Every score below is computed on-device from your own baselines.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )

        Spacer(Modifier.height(Space.xxl))

        RestingPrvCard(state.restingPrv) {
            navController.navigate(com.bipss.app.navigation.Routes.metricDetail(com.bipss.app.data.MetricTypes.PRV))
        }
        Spacer(Modifier.height(Space.xl))

        if (state.riskScores.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Insights,
                title = "No scores yet",
                body = "Scores appear once there are readings to compare against your baseline."
            )
        } else {
            SectionLabel("Risk domains")
            Spacer(Modifier.height(Space.m))
            // Computable domains first, ordered by score; the rest below with
            // their reason, so an uncomputed domain is visible but never ranked
            // as though it were a low score.
            state.riskScores
                .filter { it.type.category != com.bipss.app.domain.ScoreCategory.EXPERIMENTAL }
                .sortedWith(compareByDescending<RiskScore> { it.hasNumber }.thenByDescending { it.score })
                .forEach { risk ->
                    RiskDetailCard(risk)
                    Spacer(Modifier.height(Space.m))
                }
            // Experimental indicators apart from the risk domains.
            val experimental = state.experimentalScores
            if (experimental.isNotEmpty()) {
                Spacer(Modifier.height(Space.l))
                SectionLabel("Experimental")
                Spacer(Modifier.height(Space.m))
                experimental.forEach { risk ->
                    RiskDetailCard(risk)
                    Spacer(Modifier.height(Space.m))
                }
            }
        }

        // -- Where you are ---------------------------------------------------
        state.contextInference?.let { inference ->
            Spacer(Modifier.height(Space.l))
            SectionLabel("Where you are")
            Spacer(Modifier.height(Space.m))
            BipssCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(
                        if (inference.context == EnvironmentContext.INDOOR) Icons.Rounded.Home
                        else Icons.Rounded.Park,
                        colors.air
                    )
                    Spacer(Modifier.width(Space.m))
                    Text(
                        when (inference.context) {
                            EnvironmentContext.INDOOR  -> "You appear to be indoors"
                            EnvironmentContext.OUTDOOR -> "You appear to be outdoors"
                            else                       -> "Location context unclear"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary
                    )
                }
                Spacer(Modifier.height(Space.s))
                Text(
                    inference.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
                if (inference.sensorAqi != null && inference.apiAqi != null) {
                    Text(
                        "Band ${inference.sensorAqi}  ·  Regional ${inference.apiAqi}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary
                    )
                }

                // Collecting the correction is the feature: it is what the
                // threshold gets tuned against later. The copy does not claim
                // the tuning already happens.
                Spacer(Modifier.height(Space.m))
                if (state.contextFeedbackGiven) {
                    Text(
                        "Thanks — recorded. Corrections like this are what the threshold gets tuned against.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Is this right?",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                        Spacer(Modifier.width(Space.m))
                        IconButton(onClick = { vm.submitContextFeedback(true) }) {
                            Icon(
                                Icons.Rounded.ThumbUp,
                                contentDescription = "Correct",
                                tint = colors.bandNormal,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = { vm.submitContextFeedback(false) }) {
                            Icon(
                                Icons.Rounded.ThumbDown,
                                contentDescription = "Wrong",
                                tint = colors.bandMonitor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── Location-dependent sections ────────────────────────────────────
        // Environment, alerts and advisories are all fetched for where the user
        // is. Without location permission nothing is fetched, and these
        // sections sit under an overlay that says why: blurred on Android 12+
        // (a heavier scrim before that, where blur is unavailable). The content
        // underneath is hidden from screen readers and cannot be touched.
        Spacer(Modifier.height(Space.l))
        Box {
            Column(
                if (hasLocation) Modifier
                else Modifier.blur(12.dp).clearAndSetSemantics { }
            ) {
                // -- Environment -------------------------------------------------
                SectionLabel("Environment")
                Spacer(Modifier.height(Space.m))
                BipssCard {
                    InfoRow("Ambient temperature", env.ambientTempC?.let { "${it.toInt()} °C" } ?: "—")
                    HairlineDivider()
                    InfoRow("Humidity", env.humidityPercent?.let { "${it.toInt()} %" } ?: "—")
                    HairlineDivider()
                    InfoRow("UV index", env.uvIndex?.let { "%.1f".format(it) } ?: "—")
                    HairlineDivider()
                    InfoRow(
                        "Air quality index",
                        env.aqiValue?.let { aqi ->
                            val band = env.aqiRiskLevel?.label ?: ""
                            if (band.isBlank()) "$aqi" else "$aqi  ·  $band"
                        } ?: "—"
                    )
                    if (env.aqiValue != null) {
                        InfoRow("AQI source", env.aqiSource.label)
                    }
                    // The asterisk is explained rather than left dangling: the band and
                    // the regional station measure different air, and saying so is more
                    // useful than silently averaging them.
                    if (env.aqiSource == AqiSource.BLENDED && env.regionalAqiValue != null) {
                        Text(
                            "* Regional average (CPCB): ${env.regionalAqiValue} — you may be indoors",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    env.dominantPollutant?.let {
                        HairlineDivider(); InfoRow("Dominant pollutant", it)
                    }
                    env.pressureHpa?.let { HairlineDivider(); InfoRow("Pressure", "${it.toInt()} hPa") }
                    env.windSpeedKph?.let { HairlineDivider(); InfoRow("Wind", "${it.toInt()} km/h") }
                    env.districtName?.let { HairlineDivider(); InfoRow("Location", it) }

                    Spacer(Modifier.height(Space.m))
                    val envNowMs = rememberNowMs()
                    val envAge = relativeAgeLabel(env.fetchedAtMs, envNowMs)
                    val envStale = env.fetchedAtMs > 0L && envNowMs - env.fetchedAtMs > EnvironmentalSnapshot.STALE_THRESHOLD_MS
                    InfoBanner(
                        text = when {
                            env.fetchedAtMs == 0L -> "No environment data yet. Values appear after the first sync."
                            envStale              -> "Reading from $envAge. Values are not updating."
                            else                  -> "Updated $envAge."
                        },
                        icon = if (envStale || env.fetchedAtMs == 0L) Icons.Rounded.CloudOff else Icons.Rounded.Air,
                        tint = if (envStale || env.fetchedAtMs == 0L) colors.bandMonitor else colors.bandNormal
                    )
                }

                // -- Alerts --------------------------------------------------------
                // Rendered straight from the last real fetch. The only progress
                // indicator is the refresh spinner, which is visible for exactly as
                // long as the request actually runs.
                Spacer(Modifier.height(Space.xxl))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Active alerts", Modifier.weight(1f))
                    if (state.isRefreshing) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = colors.textSecondary,
                            strokeWidth = 2.dp
                        )
                    } else if (state.isOnline) {
                        GhostButton("Refresh", { vm.refreshEnvironment(force = true) })
                    }
                }
                Spacer(Modifier.height(Space.m))

                val nowMs = rememberNowMs()
                val checkedAge = relativeAgeLabel(env.alertsCheckedAtMs, nowMs)
                val checkedSources = env.alertSourcesChecked.joinToString(" and ")

                if (env.alertCheckFailed) {
                    // The latest attempt reached no feed. Whatever is listed below
                    // is from the last check that actually succeeded, and says so.
                    InfoBanner(
                        text = if (env.alertsCheckedAtMs > 0L)
                            "Couldn't reach the alert feeds just now. Showing the result of the last successful check ($checkedAge)."
                        else "Couldn't reach the alert feeds, and no earlier check has succeeded.",
                        icon = Icons.Rounded.CloudOff,
                        tint = colors.bandMonitor
                    )
                    Spacer(Modifier.height(Space.m))
                }

                when {
                    state.rankedAlerts.isNotEmpty() -> state.rankedAlerts.forEach { ranked ->
                        AlertCard(ranked)
                        Spacer(Modifier.height(Space.m))
                    }
                    // "No alerts" is only ever claimed for a check that really happened.
                    env.alertsCheckedAtMs == 0L -> EmptyState(
                        icon = Icons.Rounded.Schedule,
                        title = "Alerts not checked yet",
                        body = "Alerts appear after the first successful check of the NDMA and IMD feeds."
                    )
                    else -> EmptyState(
                        // A positive signal, not a blank space: an empty list here means the
                        // geo-filter is working, whereas five out-of-state cards meant it was not.
                        icon = Icons.Rounded.CheckCircle,
                        title = "No active alerts for your area",
                        body = env.districtName?.let { "Checked $checkedSources for $it, $checkedAge." }
                            ?: "Checked $checkedSources, $checkedAge."
                    )
                }

                // -- Health advisories ---------------------------------------------
                Spacer(Modifier.height(Space.l))
                SectionLabel("Health advisories")
                Spacer(Modifier.height(Space.m))

                if (env.advisories.isEmpty()) {
                    EmptyState(
                        icon = Icons.Rounded.Coronavirus,
                        title = "No active health advisories",
                        body = "Monitoring WHO outbreak news. District-level surveillance (IDSP) " +
                            "is planned for a future release."
                    )
                } else {
                    env.advisories.take(4).forEach { advisory ->
                        BipssCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(Icons.Rounded.Coronavirus, colors.bandMonitor)
                                Spacer(Modifier.width(Space.m))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        advisory.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = colors.textPrimary
                                    )
                                    // The scope label is mandatory: these sources are
                                    // national or global, and implying local detection
                                    // would be a claim the app cannot support.
                                    Text(
                                        "${advisory.scope.label}  ·  ${advisory.source}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textTertiary
                                    )
                                }
                            }
                            if (advisory.summary.isNotBlank()) {
                                Spacer(Modifier.height(Space.s))
                                Text(
                                    advisory.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary
                                )
                            }
                        }
                        Spacer(Modifier.height(Space.m))
                    }
                }
            }
            if (!hasLocation) {
                LocationRequiredOverlay(
                    modifier = Modifier.matchParentSize(),
                    permanentlyDenied = locationPermission.isPermanentlyDenied,
                    onTap = {
                        if (locationPermission.isPermanentlyDenied) com.bipss.app.sos.openAppSettings(context)
                        else locationPermission.request()
                    }
                )
            }
        }

        Spacer(Modifier.height(Space.huge))
    }
}

// ── Location required overlay ────────────────────────────────────────────────

/**
 * Covers the location-dependent sections when location permission is off.
 * The whole area is one button: it asks for the permission, or - once
 * Android no longer shows the dialog - opens the app's settings. A grant is
 * noticed when the app returns and leads to the location setup screen.
 */
@Composable
private fun LocationRequiredOverlay(
    permanentlyDenied: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BipssTheme.colors
    val message = "Grant location permission to see local environment data and alerts"
    val scrimAlpha = if (android.os.Build.VERSION.SDK_INT >= 31) 0.45f else 0.9f
    Box(
        modifier
            .background(MaterialTheme.colorScheme.background.copy(alpha = scrimAlpha))
            .clickable(
                onClickLabel = if (permanentlyDenied) "Open settings" else "Allow location",
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onTap
            )
            .semantics(mergeDescendants = true) { contentDescription = message },
        contentAlignment = Alignment.TopCenter
    ) {
        BipssCard(modifier = Modifier.padding(top = Space.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.LocationOff, colors.bandMonitor)
                Spacer(Modifier.width(Space.m))
                Text(
                    message,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(Space.m))
            Text(
                if (permanentlyDenied) "Location is turned off for BIPSS. Tap to open its settings."
                else "Tap to allow. Weather, air quality and alerts are fetched for where you are; " +
                    "nothing location-based is fetched without it.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
    }
}

@Composable
private fun AlertCard(ranked: RankedAlert) {
    val colors = BipssTheme.colors
    val alert = ranked.alert
    val accent = alert.severity.severityColor()

    BipssCard(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.WarningAmber, accent)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(alert.title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                Text(
                    "${alert.severity.label}  ·  ${alert.areaLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary
                )
            }
            StatusPill(ranked.relevance.label, accent)
        }

        // The single most compelling line in the app, and it appears only when
        // the matching domain score was genuinely computed and genuinely high.
        ranked.personalNote?.let { note ->
            Spacer(Modifier.height(Space.m))
            InfoBanner(text = note, icon = Icons.Rounded.WarningAmber, tint = colors.bandRisk)
        }

        if (alert.description.isNotBlank()) {
            Spacer(Modifier.height(Space.s))
            Text(
                alert.description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
        alert.instruction?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(Space.s))
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
        }
    }
}

@Composable
private fun RiskDetailCard(risk: RiskScore) {
    val colors = BipssTheme.colors
    val color = scoreColor(risk)

    BipssCard(accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(score = risk, size = 64.dp, strokeWidth = 7.dp, color = color)
            Spacer(Modifier.width(Space.l))
            Column(Modifier.weight(1f)) {
                Text(
                    risk.type.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (risk.hasNumber) {
                        StatusPill(risk.levelLabel ?: risk.level.bandLabel(), color)
                        Spacer(Modifier.width(Space.s))
                    }
                    ScoreBadges(risk)
                }
                ScoreReasonLine(risk)
            }
        }

        AvailabilityChip(risk.availability, Modifier.padding(top = Space.m))
        ConfidenceBar(risk.confidence, Modifier.padding(top = Space.s))
        if (risk.type == com.bipss.app.domain.RiskScoreType.AUTONOMIC_LOAD) {
            Spacer(Modifier.height(Space.s))
            Text(
                com.bipss.app.domain.ali.AutonomicLoad.TOOLTIP,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }

        if (risk.factors.isNotEmpty()) {
            Spacer(Modifier.height(Space.l))
            HairlineDivider()
            Spacer(Modifier.height(Space.m))
            Text(
                "CONTRIBUTORS",
                style = com.bipss.app.ui.theme.SectionLabelStyle,
                color = colors.textTertiary
            )
            Spacer(Modifier.height(Space.s))
            FlowPills(risk.factors.map { it.label to it.isElevated })
        }
        if (risk.measuredOutOf in 1 until risk.fullBudget) {
            Spacer(Modifier.height(Space.s))
            Text(
                "Scored against the ${risk.measuredOutOf} of ${risk.fullBudget} points we could measure.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary
            )
        }
    }
}

@Composable
private fun AlertSeverity.severityColor(): Color {
    val colors = BipssTheme.colors
    return when (this) {
        AlertSeverity.EXTREME  -> colors.bandRisk
        AlertSeverity.WARNING  -> Color(0xFFF57C00)
        AlertSeverity.WATCH    -> colors.bandMonitor
        AlertSeverity.ADVISORY -> colors.bandNormal
        AlertSeverity.NONE     -> colors.textTertiary
    }
}

/**
 * The featured PRV figure: a median over clean, still epochs, never a live
 * value (which mostly reflects posture and movement). Empty, with the reason,
 * until there is enough such signal.
 */
@Composable
private fun RestingPrvCard(prv: RestingPrv?, onOpen: () -> Unit) {
    val colors = BipssTheme.colors
    val now = com.bipss.app.ui.components.rememberNowMs()
    BipssCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Pulse rate variability",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            com.bipss.app.ui.components.FidelityBadge(com.bipss.app.domain.Fidelity.ESTIMATE)
        }
        Spacer(Modifier.height(Space.s))
        if (prv != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${prv.rmssdMs}", style = com.bipss.app.ui.theme.MetricValue, color = colors.textPrimary)
                Spacer(Modifier.width(4.dp))
                Text("ms", style = com.bipss.app.ui.theme.MetricUnit, color = colors.textTertiary,
                     modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(Space.xs))
            Text(
                "RMSSD, median of ${prv.minutes} min of still, clean signal in the last 24 h. " +
                    "Most recent window: ${com.bipss.app.domain.time.relativeAgeLabel(prv.latestMs, now)}.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        } else {
            Text(
                "Appears after a few minutes of sitting or lying still with a firmware-v2 band worn snugly. " +
                    "It is only taken from clean, still signal, because movement swamps it.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
    }
}
