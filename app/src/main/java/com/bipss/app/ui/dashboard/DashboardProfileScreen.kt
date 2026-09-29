package com.bipss.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.navigation.LocalScaffoldPadding
import com.bipss.app.navigation.Routes
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel

@Composable
fun DashboardProfileScreen(
    navController: NavController,
    vm: DashboardViewModel = koinViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(LocalScaffoldPadding.current)
            .padding(horizontal = Space.gutter)
    ) {
        Spacer(Modifier.height(Space.l))
        Text("More", style = MaterialTheme.typography.headlineLarge, color = colors.textPrimary)

        Spacer(Modifier.height(Space.xl))

        BipssCard(onClick = { navController.navigate(Routes.SETTINGS) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Settings, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Space.l))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        "Profile, device, AI model, privacy and emergency contacts",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(Modifier.height(Space.xxl))
        SectionLabel("System status")
        Spacer(Modifier.height(Space.m))
        BipssCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    if (state.isOnline) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff,
                    if (state.isOnline) colors.bandNormal else colors.bandMonitor,
                    size = 34.dp
                )
                Spacer(Modifier.width(Space.m))
                Text(
                    if (state.isOnline) "Network online" else "Offline — using cached data",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
            }
            Spacer(Modifier.height(Space.m))
            HairlineDivider()
            InfoRow("Environment synced", state.env.dataAgeLabel)
            HairlineDivider()
            InfoRow(
                "Health status",
                when (state.healthStatus) {
                    HealthStatus.NORMAL  -> "Normal"
                    HealthStatus.MONITOR -> "Monitor"
                    HealthStatus.AT_RISK -> "At risk"
                }
            )
            HairlineDivider()
            InfoRow("Band", if (state.hasBand) "Streaming" else "Not streaming")
        }

        if (state.riskScores.isNotEmpty()) {
            Spacer(Modifier.height(Space.xxl))
            SectionLabel("Risk summary")
            Spacer(Modifier.height(Space.m))
            BipssCard {
                state.riskScores.forEachIndexed { i, score ->
                    // A score that could not be computed is a dash, never "0 / 100".
                    InfoRow(score.type.displayName, if (score.hasNumber) score.valueWithScale else "—")
                    if (i < state.riskScores.lastIndex) HairlineDivider()
                }
            }
        }

        Spacer(Modifier.height(Space.huge))
    }
}
