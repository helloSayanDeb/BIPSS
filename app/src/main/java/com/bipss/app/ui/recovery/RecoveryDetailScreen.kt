package com.bipss.app.ui.recovery

import com.bipss.app.localization.AppText
import com.bipss.app.R

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
                title = { Text(AppText.text(R.string.copy_48f6a8d5688b, "Recovery")) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = AppText.text(R.string.copy_76900f1bfd16, "Back"))
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
                    title = AppText.text(R.string.copy_e176d08f27e2, "No night recorded yet"),
                    body = AppText.text(R.string.copy_80e7b149f578, "Recovery is worked out from a night's sleep. Wear the band to bed and it will appear the next morning.")
                )
            } else {
                BipssCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val yesterday = com.bipss.app.domain.time.LocalDay.previousDayRangeMs(System.currentTimeMillis()).first
                        val heading = if (result.nightStartMs >= yesterday) AppText.text(R.string.copy_b322a90abaab, "Last night")
                            else AppText.text(R.string.copy_27438853f041, "Night of ") + java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
                                .format(java.util.Date(result.nightStartMs))
                        Text(heading, style = MaterialTheme.typography.titleMedium,
                             color = colors.textPrimary, modifier = Modifier.weight(1f))
                        if (result.stage == BaselineStage.PROVISIONAL) {
                            StatusPill(AppText.text(R.string.copy_9b46c2e6d68c, "Provisional"), colors.bandMonitor)
                        }
                    }
                    Spacer(Modifier.height(Space.s))
                    if (result.score != null) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${result.score}", style = MetricValue, color = colors.textPrimary)
                            Spacer(Modifier.width(Space.s))
                            Text(AppText.text(R.string.copy_43405af2966a, "of 100"), style = MaterialTheme.typography.bodyMedium, color = colors.textTertiary,
                                 modifier = Modifier.padding(bottom = 6.dp))
                        }
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            AppText.text(R.string.copy_2676c4a08b84, "50 is an ordinary night for you; higher is better recovered. ") +
                                AppText.text(R.string.copy_c659488ba3fd, "The number combines the night's measurements listed below."),
                            style = MaterialTheme.typography.bodySmall, color = colors.textSecondary
                        )
                    } else {
                        Text(result.reason ?: AppText.text(R.string.copy_e100f464ad30, "Not enough data"), style = MaterialTheme.typography.bodyMedium,
                             color = colors.textSecondary)
                    }
                    Spacer(Modifier.height(Space.s))
                    Text(BaselineSchedule.stageLabel(result.dayN), style = MaterialTheme.typography.labelMedium,
                         color = colors.textTertiary)
                }
                BipssCard {
                    SectionLabel(AppText.text(R.string.copy_ba84db0885a4, "Against your usual night"))
                    Spacer(Modifier.height(Space.s))
                    RecoveryInput.entries.forEachIndexed { i, input ->
                        if (i > 0) HairlineDivider()
                        val c = result.components.firstOrNull { it.input == input }
                        InfoRow(input.label, c?.let { describe(it, result.stage) } ?: AppText.text(R.string.copy_040f7884d3b5, "Not measured"))
                    }
                }
            }
            Spacer(Modifier.height(Space.xxl))
        }
    }
}

private fun unit(input: RecoveryInput, v: Double): String = when (input) {
    RecoveryInput.PRV            -> AppText.text(R.string.copy_8e81b4a4fc79, "%1\$s ms", v.roundToInt())
    RecoveryInput.RESTING_HR     -> AppText.text(R.string.copy_b8300f6c066e, "%1\$s bpm", v.roundToInt())
    RecoveryInput.SLEEP_DURATION -> AppText.text(R.string.copy_49a3b5812797, "%1\$s h %2\$s min", (v / 60).toInt(), (v % 60).roundToInt())
    RecoveryInput.SKIN_TEMP      -> AppText.text(R.string.copy_89a292a70119, "%.1f °C").format(v)
}

/** "52 ms · above usual" - the value and, once a baseline exists, how it compares. */
private fun describe(c: RecoveryComponent, stage: BaselineStage): String {
    val z = c.z ?: return "${unit(c.input, c.value)} · " +
        if (stage == BaselineStage.CALIBRATING) AppText.text(R.string.copy_b4d7ee1cff58, "compared from day %1\$s", BaselineSchedule.PROVISIONAL_FROM) else AppText.text(R.string.copy_6e3843ae8ce6, "baseline still forming")
    val word = when {
        abs(z) < 0.5 -> "usual"
        z > 0        -> if (abs(z) >= 1.5) AppText.text(R.string.copy_0d60baab760a, "well above usual") else AppText.text(R.string.copy_ba2a218fa213, "above usual")
        else         -> if (abs(z) >= 1.5) AppText.text(R.string.copy_97b588add3a8, "well below usual") else AppText.text(R.string.copy_2694a845131a, "below usual")
    }
    return "${unit(c.input, c.value)} · $word"
}
