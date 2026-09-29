package com.bipss.app.ui.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.domain.BaselineSchedule
import com.bipss.app.domain.BaselineStage
import com.bipss.app.domain.recovery.RecoveryComponent
import com.bipss.app.domain.recovery.RecoveryInput
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.MetricValue
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryDetailScreen(
    navController: NavController,
    vm: RecoveryDetailViewModel = koinViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recovery") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(pad)
                .padding(horizontal = Space.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.m)
        ) {
            Spacer(Modifier.height(Space.s))
            val result = state.result
            if (result == null) {
                if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                else EmptyState(
                    icon = Icons.Rounded.Bedtime,
                    title = "No night recorded yet",
                    body = "Recovery is worked out from a night's sleep. Wear the band to bed and it will appear the next morning."
                )
            } else {
                BipssCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val yesterday = com.bipss.app.domain.time.LocalDay.previousDayRangeMs(System.currentTimeMillis()).first
                        val heading = if (result.nightStartMs >= yesterday) "Last night"
                            else "Night of " + java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
                                .format(java.util.Date(result.nightStartMs))
                        Text(heading, style = MaterialTheme.typography.titleMedium,
                             color = colors.textPrimary, modifier = Modifier.weight(1f))
                        if (result.stage == BaselineStage.PROVISIONAL) {
                            StatusPill("Provisional", colors.bandMonitor)
                        }
                    }
                    Spacer(Modifier.height(Space.s))
                    if (result.score != null) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${result.score}", style = MetricValue, color = colors.textPrimary)
                            Spacer(Modifier.width(Space.s))
                            Text("of 100", style = MaterialTheme.typography.bodyMedium, color = colors.textTertiary,
                                 modifier = Modifier.padding(bottom = 6.dp))
                        }
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            "50 is an ordinary night for you; higher is better recovered. " +
                                "The number combines the night's measurements listed below.",
                            style = MaterialTheme.typography.bodySmall, color = colors.textSecondary
                        )
                    } else {
                        Text(result.reason ?: "Not enough data", style = MaterialTheme.typography.bodyMedium,
                             color = colors.textSecondary)
                    }
                    Spacer(Modifier.height(Space.s))
                    Text(BaselineSchedule.stageLabel(result.dayN), style = MaterialTheme.typography.labelMedium,
                         color = colors.textTertiary)
                }
                BipssCard {
                    SectionLabel("Against your usual night")
                    Spacer(Modifier.height(Space.s))
                    RecoveryInput.entries.forEachIndexed { i, input ->
                        if (i > 0) HairlineDivider()
                        val c = result.components.firstOrNull { it.input == input }
                        InfoRow(input.label, c?.let { describe(it, result.stage) } ?: "Not measured")
                    }
                }
            }
            Spacer(Modifier.height(Space.xxl))
        }
    }
}

private fun unit(input: RecoveryInput, v: Double): String = when (input) {
    RecoveryInput.PRV            -> "${v.roundToInt()} ms"
    RecoveryInput.RESTING_HR     -> "${v.roundToInt()} bpm"
    RecoveryInput.SLEEP_DURATION -> "${(v / 60).toInt()} h ${(v % 60).roundToInt()} min"
    RecoveryInput.SKIN_TEMP      -> "%.1f °C".format(v)
}

/** "52 ms · above usual" - the value and, once a baseline exists, how it compares. */
private fun describe(c: RecoveryComponent, stage: BaselineStage): String {
    val z = c.z ?: return "${unit(c.input, c.value)} · " +
        if (stage == BaselineStage.CALIBRATING) "compared from day ${BaselineSchedule.PROVISIONAL_FROM}" else "baseline still forming"
    val word = when {
        abs(z) < 0.5 -> "usual"
        z > 0        -> if (abs(z) >= 1.5) "well above usual" else "above usual"
        else         -> if (abs(z) >= 1.5) "well below usual" else "below usual"
    }
    return "${unit(c.input, c.value)} · $word"
}
