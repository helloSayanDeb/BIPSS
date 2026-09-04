package com.bipss.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.domain.RiskLevel
import com.bipss.app.domain.RiskScore
import com.bipss.app.domain.environmental.AlertSeverity
import com.bipss.app.navigation.LocalScaffoldPadding
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel

@Composable
fun DashboardInsightsScreen(
    navController: NavController,
    vm: DashboardViewModel = koinViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors
    val env = state.env

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

        if (state.riskScores.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Insights,
                title = "No scores yet",
                body = "Risk scores appear once there are readings to compare against your baseline."
            )
        } else {
            SectionLabel("Risk domains")
            Spacer(Modifier.height(Space.m))
            state.riskScores.sortedByDescending { it.score }.forEach { risk ->
                RiskDetailCard(risk)
                Spacer(Modifier.height(Space.m))
            }
        }

        Spacer(Modifier.height(Space.l))
        SectionLabel("Environment")
        Spacer(Modifier.height(Space.m))
        BipssCard {
            InfoRow("Ambient temperature", "${env.ambientTempC.toInt()} °C")
            HairlineDivider()
            InfoRow("Humidity", "${env.humidityPercent.toInt()} %")
            HairlineDivider()
            InfoRow("UV index", "%.1f".format(env.uvIndex))
            HairlineDivider()
            InfoRow("Air quality index", "${env.aqiValue}  ·  ${env.aqiRiskLevel.name.replace('_', ' ').lowercase()}")
            HairlineDivider()
            InfoRow("Dominant pollutant", env.dominantPollutant)
            env.pressureHpa?.let {
                HairlineDivider(); InfoRow("Pressure", "${it.toInt()} hPa")
            }
            env.windSpeedKph?.let {
                HairlineDivider(); InfoRow("Wind", "${it.toInt()} km/h")
            }
            Spacer(Modifier.height(Space.m))
            InfoBanner(
                text = if (env.isCachedData)
                    "Cached reading from ${env.dataAgeLabel.lowercase()}. Values are extrapolated until the network returns."
                else
                    "Live reading, updated ${env.dataAgeLabel.lowercase()}.",
                icon = if (env.isCachedData) Icons.Rounded.CloudOff else Icons.Rounded.Air,
                tint = if (env.isCachedData) colors.bandMonitor else colors.bandNormal
            )
        }

        if (env.activeAlerts.isNotEmpty()) {
            Spacer(Modifier.height(Space.xxl))
            SectionLabel("Active alerts")
            Spacer(Modifier.height(Space.m))
            env.activeAlerts.take(5).forEach { alert ->
                BipssCard(accent = env.alertSeverity.color()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.WarningAmber, env.alertSeverity.color())
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(
                                alert.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.textPrimary
                            )
                            Text(
                                alert.category,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textTertiary
                            )
                        }
                    }
                    if (alert.description.isNotBlank()) {
                        Spacer(Modifier.height(Space.s))
                        Text(
                            alert.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                }
                Spacer(Modifier.height(Space.m))
            }
        }

        Spacer(Modifier.height(Space.huge))
    }
}

@Composable
private fun RiskDetailCard(risk: RiskScore) {
    val colors = BipssTheme.colors
    val color = when (risk.level) {
        RiskLevel.LOW    -> colors.bandNormal
        RiskLevel.MEDIUM -> colors.bandMonitor
        RiskLevel.HIGH   -> colors.bandRisk
    }
    val band = when (risk.level) {
        RiskLevel.LOW    -> "Normal"
        RiskLevel.MEDIUM -> "Monitor"
        RiskLevel.HIGH   -> "At risk"
    }

    BipssCard(accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingGauge(progress = risk.score / 100f, color = color, size = 64.dp, strokeWidth = 7.dp) {
                Text(
                    "${risk.score}",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
            }
            Spacer(Modifier.width(Space.l))
            Column(Modifier.weight(1f)) {
                Text(
                    risk.type.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                Spacer(Modifier.height(6.dp))
                StatusPill(band, color)
            }
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
    }
}

@Composable
private fun AlertSeverity.color(): Color {
    val colors = BipssTheme.colors
    return when (this) {
        AlertSeverity.EXTREME  -> colors.bandRisk
        AlertSeverity.WARNING  -> colors.bandRisk
        AlertSeverity.WATCH    -> colors.bandMonitor
        AlertSeverity.ADVISORY -> colors.bandMonitor
        AlertSeverity.NONE     -> colors.bandNormal
    }
}
