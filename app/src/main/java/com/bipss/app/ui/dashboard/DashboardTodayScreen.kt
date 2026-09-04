package com.bipss.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.domain.RiskLevel
import com.bipss.app.domain.RiskScore
import com.bipss.app.domain.RiskScoreType
import com.bipss.app.navigation.LocalScaffoldPadding
import com.bipss.app.sos.*
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.ModeLocalOnly
import com.bipss.app.ui.theme.ModeMinimal
import com.bipss.app.ui.theme.ModeOptimal
import com.bipss.app.ui.theme.ModePhoneOnly
import com.bipss.app.ui.theme.SOSRed
import com.bipss.app.ui.theme.Space
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
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

    val smsPermState = rememberMultiplePermissionsState(listOf(android.Manifest.permission.SEND_SMS))
    val locPermState = rememberPermissionState(android.Manifest.permission.ACCESS_FINE_LOCATION)

    when (val s = sosState) {
        is SosUiState.Countdown -> SosCountdownOverlay(state = s, onCancel = { sosVm.cancelSos() })
        is SosUiState.FetchingLocation, is SosUiState.SendingSms -> SosSendingSpinner()
        is SosUiState.Sent -> SosSentOverlay(state = s, onDismiss = { sosVm.dismissResult() })
        is SosUiState.FalseAlarm -> SosFalseAlarmOverlay(onDismiss = { sosVm.dismissResult() })
        else -> Unit
    }

    val hasLiveBand = state.heartRate > 0
    val mode = when {
        hasLiveBand && state.isOnline  -> "Live" to ModeOptimal
        hasLiveBand && !state.isOnline -> "Offline · band only" to ModeLocalOnly
        !hasLiveBand && state.isOnline -> "No band" to ModePhoneOnly
        else                           -> "Offline · cached" to ModeMinimal
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

            // ── Header ─────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Today",
                        style = MaterialTheme.typography.headlineLarge,
                        color = colors.textPrimary
                    )
                    Text(
                        state.env.dataAgeLabel.let { "Environment synced $it".lowercase().replaceFirstChar { c -> c.uppercase() } },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary
                    )
                }
                ModeChip(mode.first, mode.second)
            }

            Spacer(Modifier.height(Space.xl))

            // ── Leading risk ──────────────────────────────────────────────
            val leading = state.riskScores.maxByOrNull { it.score }
            if (leading != null) {
                RiskHeroCard(
                    domain = leading.type.displayName,
                    score = leading.score,
                    bandLabel = leading.level.label(),
                    color = leading.level.color(),
                    factors = leading.factors.map { it.label to it.isElevated },
                )
            } else {
                BipssCard {
                    Text(
                        "Collecting your first readings…",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        "Risk scores appear once the band streams data or Health Connect has history to read.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
            }

            Spacer(Modifier.height(Space.xxl))

            // ── Vitals 2×2 ────────────────────────────────────────────────
            SectionHeader("Vitals")
            Spacer(Modifier.height(Space.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                MetricTile(
                    label = "Heart rate",
                    value = state.heartRate.orDash(),
                    unit = "bpm",
                    icon = Icons.Rounded.Favorite,
                    tint = colors.heart,
                    history = state.heartRateHistory,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "Blood oxygen",
                    value = state.spO2.orDash(),
                    unit = "%",
                    icon = Icons.Rounded.Bloodtype,
                    tint = colors.spo2,
                    history = state.spO2History,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(Space.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                MetricTile(
                    label = "Skin temp",
                    value = if (state.skinTempC > 0f) "%.1f".format(state.skinTempC) else "—",
                    unit = "°C",
                    icon = Icons.Rounded.DeviceThermostat,
                    tint = colors.temp,
                    history = state.skinTempHistory,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    label = "Steps",
                    value = if (state.stepCount > 0) "${state.stepCount}" else "—",
                    unit = "",
                    icon = Icons.AutoMirrored.Rounded.DirectionsWalk,
                    tint = colors.activity,
                    history = emptyList(),
                    modifier = Modifier.weight(1f)
                )
            }

            // ── Remaining risk domains ────────────────────────────────────
            val others = state.riskScores.filter { it != leading }
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(Space.xxl))
                SectionHeader("Other risk domains")
                Spacer(Modifier.height(Space.m))
                others.forEach { risk ->
                    RiskRow(
                        domain = risk.type.displayName,
                        score = risk.score,
                        bandLabel = risk.level.label(),
                        color = risk.level.color(),
                    )
                    Spacer(Modifier.height(Space.m))
                }
            }

            // ── Environment ───────────────────────────────────────────────
            Spacer(Modifier.height(Space.l))
            SectionHeader("Environment")
            Spacer(Modifier.height(Space.m))
            BipssCard {
                Row {
                    EnvStat(
                        "Temperature", "${state.env.ambientTempC.toInt()}°C",
                        Icons.Rounded.DeviceThermostat, colors.temp, Modifier.weight(1f)
                    )
                    EnvStat(
                        "Humidity", "${state.env.humidityPercent.toInt()}%",
                        Icons.Rounded.WaterDrop, colors.spo2, Modifier.weight(1f)
                    )
                    EnvStat(
                        "AQI", "${state.env.aqiValue}",
                        Icons.Rounded.Air, colors.air, Modifier.weight(1f)
                    )
                }
                if (state.env.isCachedData || state.env.isStale) {
                    Spacer(Modifier.height(Space.m))
                    InfoBanner(
                        text = "Showing cached conditions from ${state.env.dataAgeLabel.lowercase()} — reconnect for a live reading.",
                        icon = Icons.Rounded.CloudOff,
                        tint = colors.bandMonitor
                    )
                }
            }

            Spacer(Modifier.height(120.dp))
        }

        // ── SOS ───────────────────────────────────────────────────────────
        FloatingActionButton(
            onClick = {
                if (!smsPermState.allPermissionsGranted) smsPermState.launchMultiplePermissionRequest()
                else {
                    if (!locPermState.status.isGranted) locPermState.launchPermissionRequest()
                    sosVm.triggerSos(SosTriggerSource.MANUAL)
                }
            },
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

private fun Int.orDash(): String = if (this > 0) "$this" else "—"

@Composable
private fun RiskLevel.color(): Color {
    val colors = BipssTheme.colors
    return when (this) {
        RiskLevel.LOW    -> colors.bandNormal
        RiskLevel.MEDIUM -> colors.bandMonitor
        RiskLevel.HIGH   -> colors.bandRisk
    }
}

private fun RiskLevel.label(): String = when (this) {
    RiskLevel.LOW    -> "Normal"
    RiskLevel.MEDIUM -> "Monitor"
    RiskLevel.HIGH   -> "At risk"
}
