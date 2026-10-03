package com.bipss.app.ui.dashboard

import com.bipss.app.localization.AppText
import com.bipss.app.R

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
import androidx.compose.material.icons.rounded.HelpOutline
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import com.bipss.app.domain.environmental.AdvisoryScope
import com.bipss.app.domain.environmental.AdvisorySummarizer
import com.bipss.app.domain.environmental.HealthAdvisory
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
import com.bipss.app.domain.environmental.AqiDisplay
import com.bipss.app.domain.environmental.EnvContext
import com.bipss.app.domain.environmental.EnvContextKind
import com.bipss.app.domain.environmental.Reconciliation
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
        Text(AppText.text(R.string.copy_2a932f90172e, "Insights"), style = MaterialTheme.typography.headlineLarge, color = colors.textPrimary)
        Spacer(Modifier.height(Space.s))
        Text(
            AppText.text(R.string.copy_d500431fe4cf, "Your patterns, local conditions and health guidance."),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )

        Spacer(Modifier.height(Space.xxl))

        BipssCard(onClick = { navController.navigate(com.bipss.app.navigation.Routes.FITNESS) }) {
            Text(AppText.text(R.string.copy_2026882df169, "Cardio fitness"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.s))
            Text(AppText.text(R.string.copy_273d33c128ff, "Estimated VO₂ max and fitness age"), color = colors.textSecondary)
        }
        Spacer(Modifier.height(Space.m))
        BipssCard(onClick = { navController.navigate(com.bipss.app.navigation.Routes.SLEEP_STAGES) }) {
            Text(AppText.text(R.string.copy_c60c9fe2060e, "Sleep stages"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.s))
            Text(AppText.text(R.string.copy_f537b4860901, "Explore your overnight patterns"), color = colors.textSecondary)
        }
        Spacer(Modifier.height(Space.xl))

        RestingPrvCard(state.restingPrv) {
            navController.navigate(com.bipss.app.navigation.Routes.metricDetail(com.bipss.app.data.MetricTypes.PRV))
        }
        Spacer(Modifier.height(Space.xl))

        if (state.riskScores.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Insights,
                title = AppText.text(R.string.copy_1ab6157ed652, "No scores yet"),
                body = AppText.text(R.string.copy_b7c9fa698209, "Scores appear once there are readings to compare against your baseline.")
            )
        } else {
            SectionLabel(AppText.text(R.string.copy_b9e6b65d6aef, "Daily insights"))
            Spacer(Modifier.height(Space.m))
            // Computable domains first, ordered by score; the rest below with
            // their reason, so an uncomputed domain is visible but never ranked
            // as though it were a low score.
            state.riskScores
                .filter { it.type.category != com.bipss.app.domain.ScoreCategory.EXPERIMENTAL }
                .sortedWith(compareByDescending<RiskScore> { it.hasNumber }.thenByDescending { it.score })
                .forEach { risk ->
                    InsightEntry(risk.type, navController) { RiskDetailCard(risk) }
                    Spacer(Modifier.height(Space.m))
                }
        }

        state.riskScores.firstOrNull { it.type == com.bipss.app.domain.RiskScoreType.AUTONOMIC_LOAD }?.let { load ->
            Spacer(Modifier.height(Space.l))
            InsightEntry(load.type, navController) {
                BipssCard {
                    Text(AppText.text(R.string.copy_1705c824e382, "Autonomic Load"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(Space.s))
                    Text(if (load.hasNumber) load.valueLabel else AppText.text(R.string.copy_fb608f364327, "Building your resting pattern"),
                        style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
                    Spacer(Modifier.height(Space.s))
                    Text(if (load.hasNumber) AppText.text(R.string.copy_35565fc8792d, "How your resting pulse and pulse variability compare with your usual range.")
                        else load.missingInputs.joinToString(". ") { AppText.display(it) }.ifBlank { AppText.text(R.string.copy_3a035a8db03b, "Wear your band regularly and include quiet resting time.") },
                        style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                    var explain by rememberSaveable { mutableStateOf(false) }
                    TextButton(onClick = { explain = !explain }) { Text(if (explain) AppText.text(R.string.copy_552218e690ca, "Less detail") else AppText.text(R.string.copy_e13c287fab38, "About this indicator")) }
                    if (explain) Text(AppText.text(R.string.copy_0393c0804a1c, "An experimental wellness indicator using still, clean readings and your personal baseline. Heat, recent activity, poor sleep and other factors can change it. It cannot identify the cause or diagnose stress or illness."),
                        style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
        }
        }

        // -- Where you are ---------------------------------------------------
        // From GNSS signal and movement (spec 5.9) - never from the air readings.
        state.airContext?.context?.let { context ->
            Spacer(Modifier.height(Space.l))
            SectionLabel(AppText.text(R.string.copy_54cd982fb2dd, "Where you are"))
            Spacer(Modifier.height(Space.m))
            AirContextCard(
                context = context,
                ageMs = state.airContext?.ageMs,
                feedbackGiven = state.contextFeedbackGiven,
                onFeedback = vm::submitContextFeedback
            )
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
                SectionLabel(AppText.text(R.string.copy_9e471951a1b4, "Environment"))
                Spacer(Modifier.height(Space.m))
                BipssCard {
                    InfoRow(AppText.text(R.string.copy_17988f163090, "Ambient temperature"), env.ambientTempC?.let { "${it.toInt()} °C" } ?: "—")
                    HairlineDivider()
                    InfoRow(AppText.text(R.string.copy_46819ccb5e6d, "Humidity"), env.humidityPercent?.let { "${it.toInt()} %" } ?: "—")
                    HairlineDivider()
                    InfoRow(AppText.text(R.string.copy_a71daa9a7728, "UV index"), env.uvIndex?.let { "%.1f".format(it) } ?: "—")
                    HairlineDivider()
                    AirQualityRows(
                        display = state.airContext?.display ?: AqiDisplay.StationOnly(env.aqiValue),
                        stationLabel = env.aqiRiskLevel?.label,
                        reconciliation = state.airContext?.reconciliation,
                        env = env
                    )
                    env.dominantPollutant?.let {
                        HairlineDivider(); InfoRow(AppText.text(R.string.copy_173d8be9daa2, "Dominant pollutant"), it)
                    }
                    env.pressureHpa?.let { HairlineDivider(); InfoRow(AppText.text(R.string.copy_1f8407f9eeb1, "Pressure"), AppText.text(R.string.copy_d5bbf7c9ea7d, "%1\$s hPa", it.toInt())) }
                    env.windSpeedKph?.let { HairlineDivider(); InfoRow(AppText.text(R.string.copy_d151346fe7ee, "Wind"), AppText.text(R.string.copy_c38337861854, "%1\$s km/h", it.toInt())) }
                    env.districtName?.let { HairlineDivider(); InfoRow(AppText.text(R.string.copy_15b61974b270, "Location"), it) }

                    Spacer(Modifier.height(Space.m))
                    val envNowMs = rememberNowMs()
                    val envAge = relativeAgeLabel(env.fetchedAtMs, envNowMs)
                    val envStale = env.fetchedAtMs > 0L && envNowMs - env.fetchedAtMs > EnvironmentalSnapshot.STALE_THRESHOLD_MS
                    InfoBanner(
                        text = when {
                            env.fetchedAtMs == 0L -> AppText.text(R.string.copy_c5ea0fd90055, "No environment data yet. Values appear after the first sync.")
                            envStale              -> AppText.text(R.string.copy_59bf969e566c, "Reading from %1\$s. Values are not updating.", envAge)
                            else                  -> AppText.text(R.string.copy_2bbc5d5958f1, "Updated %1\$s.", envAge)
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
                    SectionLabel(AppText.text(R.string.copy_7ebb72a4d750, "Active alerts"), Modifier.weight(1f))
                    if (state.isRefreshing) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = colors.textSecondary,
                            strokeWidth = 2.dp
                        )
                    } else if (state.isOnline) {
                        GhostButton(AppText.text(R.string.copy_0e9161011702, "Refresh"), { vm.refreshEnvironment(force = true) })
                    }
                }
                Spacer(Modifier.height(Space.m))

                val nowMs = rememberNowMs()
                val checkedAge = relativeAgeLabel(env.alertsCheckedAtMs, nowMs)
                val checkedSources = env.alertSourcesChecked.joinToString(AppText.text(R.string.copy_e3ee915a8e8c, " and "))

                if (env.alertCheckFailed) {
                    // The latest attempt reached no feed. Whatever is listed below
                    // is from the last check that actually succeeded, and says so.
                    InfoBanner(
                        text = if (env.alertsCheckedAtMs > 0L)
                            AppText.text(R.string.copy_ab3542da12ee, "Couldn't reach the alert feeds just now. Showing the result of the last successful check (%1\$s).", checkedAge)
                        else AppText.text(R.string.copy_0aca4a3384f3, "Couldn't reach the alert feeds, and no earlier check has succeeded."),
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
                        title = AppText.text(R.string.copy_966b1282afe5, "Alerts not checked yet"),
                        body = AppText.text(R.string.copy_601c67ed4c19, "Connect to the internet to check local safety alerts.")
                    )
                    else -> EmptyState(
                        // A positive signal, not a blank space: an empty list here means the
                        // geo-filter is working, whereas five out-of-state cards meant it was not.
                        icon = Icons.Rounded.CheckCircle,
                        title = AppText.text(R.string.copy_7643ea640793, "No active alerts for your area"),
                        body = env.districtName?.let { AppText.text(R.string.copy_57ddaab81f22, "Checked %1\$s for %2\$s, %3\$s.", checkedSources, it, checkedAge) }
                            ?: AppText.text(R.string.copy_049db096690b, "Checked %1\$s, %2\$s.", checkedSources, checkedAge)
                    )
                }

                // -- Health advisories ---------------------------------------------
                Spacer(Modifier.height(Space.l))
                SectionLabel(AppText.text(R.string.copy_130b2065924a, "Health advisories"))
                Spacer(Modifier.height(Space.m))

                if (env.advisories.isEmpty()) {
                    val checked = env.advisorySources
                    EmptyState(
                        icon = Icons.Rounded.Coronavirus,
                        title = if (checked.isEmpty()) AppText.text(R.string.copy_65852a6e9b45, "Outbreak reports not checked yet") else AppText.text(R.string.copy_3c4c67792961, "No outbreak reports in the last 30 days"),
                        body = if (checked.isEmpty()) AppText.text(R.string.copy_ff5b330dea90, "WHO, CDC and ECDC couldn't be reached. Tap Refresh above to try again.")
                               else AppText.text(R.string.copy_166da8cb3f6f, "Checked %1\$s. Reports older than 30 days aren't shown.", checked.joinToString(", "))
                    )
                } else {
                    var showAll by rememberSaveable { mutableStateOf(false) }
                    val shown = if (showAll) env.advisories else env.advisories.take(ADVISORIES_SHOWN)
                    shown.forEach { advisory ->
                        AdvisoryCard(advisory)
                        Spacer(Modifier.height(Space.m))
                    }
                    if (env.advisories.size > ADVISORIES_SHOWN) {
                        TextButton(onClick = { showAll = !showAll }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text(if (showAll) AppText.text(R.string.copy_255beb6510e7, "Show fewer") else AppText.text(R.string.copy_6b146acbc568, "Show %1\$s more", env.advisories.size - ADVISORIES_SHOWN))
                        }
                    }
                    Text(
                        AppText.text(R.string.copy_0a0cad817b0e, "From %1\$s, ", env.advisorySources.ifEmpty { listOf("WHO", "CDC", "ECDC") }.joinToString(", ")) +
                            AppText.text(R.string.copy_2b2555f04a56, "reports from the last 30 days."),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary
                    )
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

// ── Health advisories ────────────────────────────────────────────────────────

/** Cards shown before "Show more". */
private const val ADVISORIES_SHOWN = 3

/**
 * One outbreak: where, who reported it, a phone-made summary of the latest
 * report, WHO's risk verdict when it gave one, and a link to the source.
 */
@Composable
private fun AdvisoryCard(advisory: HealthAdvisory) {
    val colors = BipssTheme.colors
    val context = LocalContext.current
    val open: (() -> Unit)? = advisory.url?.let { url ->
        {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
    BipssCard(onClick = open) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                Icons.Rounded.Coronavirus,
                if (advisory.scope == AdvisoryScope.NATIONAL) colors.bandRisk else colors.bandMonitor
            )
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(advisory.title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                Spacer(Modifier.height(Space.xs))
                // Where it is, always: these sources are national or global, and
                // implying local detection would be a claim the app cannot support.
                Text(
                    listOfNotNull(
                        advisory.place ?: advisory.scope.label,
                        advisory.source,
                        AdvisorySummarizer.shortDate(advisory.publishedMs)
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary
                )
            }
        }
        if (advisory.summary.isNotBlank()) {
            Spacer(Modifier.height(Space.m))
            Text(advisory.summary, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        val extras = listOfNotNull(
            advisory.riskNote,
            advisory.travelLevel?.let { AppText.text(R.string.copy_545bdfc88ca8, "CDC travel notice: level %1\$s of 4.", it) }
        )
        extras.forEach {
            Spacer(Modifier.height(Space.s))
            Text(it, style = MaterialTheme.typography.labelMedium, color = colors.textPrimary)
        }
        if (advisory.updateCount > 1 || open != null) {
            Spacer(Modifier.height(Space.m))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (advisory.updateCount > 1) {
                    Text(
                        AppText.text(R.string.copy_45d9e9d26835, "%1\$s reports since ", advisory.updateCount) +
                            AdvisorySummarizer.shortDate(advisory.firstReportedMs ?: advisory.publishedMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                        modifier = Modifier.weight(1f)
                    )
                } else Spacer(Modifier.weight(1f))
                if (open != null) {
                    Text(AppText.text(R.string.copy_f3e23734b256, "Full report"), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                    Spacer(Modifier.width(Space.xs))
                    Icon(
                        Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null,
                        tint = colors.textSecondary, modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

// ── Indoor / outdoor ─────────────────────────────────────────────────────────

@Composable
private fun AirContextCard(
    context: EnvContext,
    ageMs: Long?,
    feedbackGiven: Boolean,
    onFeedback: (Boolean) -> Unit
) {
    val colors = BipssTheme.colors
    BipssCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                when (context.kind) {
                    EnvContextKind.INDOOR  -> Icons.Rounded.Home
                    EnvContextKind.OUTDOOR -> Icons.Rounded.Park
                    EnvContextKind.UNKNOWN -> Icons.Rounded.HelpOutline
                },
                colors.air
            )
            Spacer(Modifier.width(Space.m))
            Text(
                when (context.kind) {
                    EnvContextKind.INDOOR  -> AppText.text(R.string.copy_17b20fcf1e5e, "You appear to be indoors")
                    EnvContextKind.OUTDOOR -> AppText.text(R.string.copy_e76b15900a6f, "You appear to be outdoors")
                    EnvContextKind.UNKNOWN -> AppText.text(R.string.copy_738eae10390f, "Can't tell if you're indoors or out")
                },
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(Space.s))
        context.evidence.forEach {
            Text("· $it", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        Spacer(Modifier.height(Space.s))
        Text(
            AppText.text(R.string.copy_b74b18123a69, "From GPS signal and your band's movement") +
                (ageMs?.let { AppText.text(R.string.copy_181eef707b2a, ", checked %1\$s min ago.", (it / 60_000L).coerceAtLeast(0)) } ?: "."),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary
        )
        // Corrections are what the thresholds get tuned against later; the
        // copy does not claim the tuning already happens.
        if (context.kind != EnvContextKind.UNKNOWN) {
            Spacer(Modifier.height(Space.m))
            if (feedbackGiven) {
                Text(
                    AppText.text(R.string.copy_58089a03f3d2, "Thanks - recorded. Corrections like this are what the GPS thresholds get tuned against."),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(AppText.text(R.string.copy_2180cc4c2802, "Is this right?"), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    Spacer(Modifier.width(Space.m))
                    IconButton(onClick = { onFeedback(true) }) {
                        Icon(Icons.Rounded.ThumbUp, contentDescription = AppText.text(R.string.copy_aca01a1a3a27, "Correct"), tint = colors.bandNormal, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { onFeedback(false) }) {
                        Icon(Icons.Rounded.ThumbDown, contentDescription = AppText.text(R.string.copy_a633bed8890a, "Wrong"), tint = colors.bandMonitor, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/**
 * Air quality as the context decides: the station outdoors (the band as
 * corroboration), the band against the user's own range indoors (the station
 * labelled as outdoors), or both, labelled, when it is unclear. Never an
 * average of the two - they measure different air on different scales.
 */
@Composable
private fun AirQualityRows(display: AqiDisplay, stationLabel: String?, reconciliation: Reconciliation?, env: EnvironmentalSnapshot) {
    val colors = BipssTheme.colors
    fun station(aqi: Int?) = aqi?.let { if (stationLabel.isNullOrBlank()) "$it" else "$it  ·  $stationLabel" } ?: "—"
    // Where the outdoor figure comes from: a CPCB station, or the CAMS model
    // when no station reading is available - never presented as the other.
    val modelled = env.aqiSource == AqiSource.MODEL
    val outdoorRow = if (modelled) AppText.text(R.string.copy_ba63c2251308, "Air quality · estimated") else AppText.text(R.string.copy_28380990b09e, "Air quality · nearby station")
    val outdoorsNear = if (modelled) AppText.text(R.string.copy_129ebb26b320, "Outdoors near you · estimated") else AppText.text(R.string.copy_57453267edbf, "Outdoors near you")
    val outdoorsUnclear = if (modelled) AppText.text(R.string.copy_9a56f0b61821, "Outdoor estimate") else AppText.text(R.string.copy_f4bfa44e917a, "Nearby outdoor station")
    val nowMs = rememberNowMs()
    val sourceNote = env.aqiNote?.let { note ->
        val observed = env.aqiObservedAtMs
        if (env.aqiSource == AqiSource.CPCB && observed != null) AppText.text(R.string.copy_275d1a6121ff, "%1\$s Reading %2\$s.", note, relativeAgeLabel(observed, nowMs)) else note
    }
    fun band(uba: Int) = AppText.text(R.string.copy_16ed3de5d2de, "%1\$s of 5  ·  %2\$s", uba, AqiDisplay.ubaLabel(uba))
    val learning = reconciliation?.let { AppText.text(R.string.copy_284d0402a4a5, "day %1\$s of %2\$s", it.dayN, com.bipss.app.domain.BaselineSchedule.PROVISIONAL_FROM) }
    @Composable
    fun note(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary,
                                  modifier = Modifier.padding(bottom = 4.dp))
    when (display) {
        is AqiDisplay.StationOnly -> InfoRow(outdoorRow, station(display.cpcbAqi))
        is AqiDisplay.Outdoor -> {
            InfoRow(outdoorRow, station(display.cpcbAqi))
            InfoRow(AppText.text(R.string.copy_da31939ed995, "Band air sensor"), band(display.bandUba))
            note(
                display.expected?.let {
                    AppText.text(R.string.copy_8ea407a0180a, "Outdoors here, a band reading of %1\$s has come with station AQI ", display.bandUba) +
                        AppText.text(R.string.copy_ffc414505eb7, "%1\$s-%2\$s (median %3\$s, %4\$s checks).", it.q1.toInt(), it.q3.toInt(), it.median.toInt(), it.n)
                } ?: AppText.text(R.string.copy_de4f408b473a, "Still learning how the band lines up with the station outdoors") + (learning?.let { " ($it)." } ?: ".")
            )
        }
        is AqiDisplay.Indoor -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { InfoRow(AppText.text(R.string.copy_e9c767e33e0f, "Band air sensor (indoors)"), band(display.bandUba)) }
            }
            note(
                display.usual?.let {
                    val median = it.medianUba.toInt()
                    val high = it.p90Uba.toInt()
                    if (display.bandUba > it.p90Uba) AppText.text(R.string.copy_770057b0a92f, "Above your usual indoor air (typically %1\$s, rarely above %2\$s).", median, high)
                    else AppText.text(R.string.copy_55ed64b8e5bd, "Within your usual indoor air (typically %1\$s, rarely above %2\$s).", median, high)
                } ?: AppText.text(R.string.copy_4f5452dd5dfb, "Still learning your usual indoor air") + (learning?.let { " ($it)." } ?: ".")
            )
            InfoRow(outdoorsNear, station(display.cpcbAqi))
        }
        is AqiDisplay.Unclear -> {
            InfoRow(AppText.text(R.string.copy_da31939ed995, "Band air sensor"), band(display.bandUba))
            InfoRow(outdoorsUnclear, station(display.cpcbAqi))
            note(AppText.text(R.string.copy_b0f56183e571, "Shown side by side: whether you're indoors or out is unclear right now."))
        }
    }
    sourceNote?.let { note(it) }
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
    val message = AppText.text(R.string.copy_ae912af09596, "Grant location permission to see local environment data and alerts")
    val scrimAlpha = if (android.os.Build.VERSION.SDK_INT >= 31) 0.45f else 0.9f
    Box(
        modifier
            .background(MaterialTheme.colorScheme.background.copy(alpha = scrimAlpha))
            .clickable(
                onClickLabel = if (permanentlyDenied) AppText.text(R.string.copy_ca381c1e76e6, "Open settings") else AppText.text(R.string.copy_843d87d29be6, "Allow location"),
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
                if (permanentlyDenied) AppText.text(R.string.copy_75747ce18d4c, "Location is turned off for BIPSS. Tap to open its settings.")
                else AppText.text(R.string.copy_459b4c07bf3e, "Tap to allow. Weather, air quality and alerts are fetched for where you are; ") +
                    AppText.text(R.string.copy_88aff02ec452, "nothing location-based is fetched without it."),
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
                Spacer(Modifier.height(Space.xs))
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
    var expanded by remember { mutableStateOf(false) }
    BipssCard(onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(score = risk, size = 64.dp, strokeWidth = 7.dp, color = color)
            Spacer(Modifier.width(Space.l))
            Column(Modifier.weight(1f)) {
                Text(risk.type.displayName, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Spacer(Modifier.height(Space.s))
                Text(if (risk.hasNumber) scoreStatus(risk) else when (risk.type) {
                    com.bipss.app.domain.RiskScoreType.SLEEP, com.bipss.app.domain.RiskScoreType.READINESS -> AppText.text(R.string.copy_8c3b009a850a, "Wear your band overnight to record your sleep.")
                    com.bipss.app.domain.RiskScoreType.RECOVERY -> AppText.text(R.string.copy_6c8b286df413, "Your personal baseline builds with regular overnight wear.")
                    com.bipss.app.domain.RiskScoreType.STRAIN -> AppText.text(R.string.copy_49a82c802e3f, "Wear your band during the day to track your effort.")
                    else -> AppText.text(R.string.copy_c4dda4bcdd85, "More readings are needed. Connect your band and check local conditions.")
                },
                    style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleLarge, color = colors.textSecondary)
        }
        if (expanded) {
            Spacer(Modifier.height(Space.l))
            Text(when (risk.type) {
                com.bipss.app.domain.RiskScoreType.HEAT_STRESS -> AppText.text(R.string.copy_65c46af20d44, "Combines local heat and humidity with available body readings and your drink log. Take breaks and keep cool when conditions are demanding.")
                com.bipss.app.domain.RiskScoreType.RESPIRATORY -> AppText.text(R.string.copy_e2cdaee27daa, "Local air quality can help you decide when to reduce outdoor exertion or choose an indoor activity.")
                com.bipss.app.domain.RiskScoreType.FLOOD -> AppText.text(R.string.copy_8e1a7d7540dd, "Local weather and active warnings help you plan around hazardous conditions.")
                com.bipss.app.domain.RiskScoreType.RECOVERY -> AppText.text(R.string.copy_56b5157b6bac, "Looks at overnight readings against your usual pattern. Balance the score with how you feel.")
                com.bipss.app.domain.RiskScoreType.SLEEP -> AppText.text(R.string.copy_b47c6ab722e5, "Reflects recorded sleep duration and time asleep during the night.")
                com.bipss.app.domain.RiskScoreType.STRAIN -> AppText.text(R.string.copy_918e7894dd9b, "Describes cardiovascular effort during your day. A higher number means more effort, not better health.")
                else -> AppText.text(R.string.copy_2a57701ad4e1, "A summary of the readings available for your day. Tap individual measurements to explore their history.")
            }, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            if (risk.provisional) {
                Spacer(Modifier.height(Space.s))
                Text(AppText.text(R.string.copy_84ac7d37c118, "Your personal baseline is still building."), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            if (risk.factors.isNotEmpty()) {
                Spacer(Modifier.height(Space.l))
                SectionLabel(AppText.text(R.string.copy_9b58b91036fc, "What's contributing"))
                Spacer(Modifier.height(Space.s))
                FlowPills(risk.factors.map { AppText.display(it.label) to it.isElevated })
            }
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
                AppText.text(R.string.copy_73bf2d3e3d42, "Pulse rate variability"),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
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
                AppText.text(R.string.copy_e745536fd35a, "Measured while resting. Tap for your history."),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        } else {
            Text(
                AppText.text(R.string.copy_36760374a8f2, "Wear your band snugly and sit or lie comfortably for a few minutes."),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
    }
}

@Composable
private fun InsightEntry(type: com.bipss.app.domain.RiskScoreType, nav: NavController, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Box(Modifier.weight(1f)) { content() }
        androidx.compose.material3.FilledTonalIconButton(
            onClick = { nav.navigate(com.bipss.app.navigation.Routes.insightDetail(type)) },
            modifier = Modifier.width(52.dp).height(76.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        ) {
            Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = AppText.text(R.string.copy_a5a5092b2a4b, "Open %1\$s details", type.displayName))
        }
    }
}
