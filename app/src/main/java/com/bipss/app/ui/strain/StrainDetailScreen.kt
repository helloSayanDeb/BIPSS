package com.bipss.app.ui.strain

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.data.StrainOutcome
import com.bipss.app.domain.Fidelity
import com.bipss.app.domain.strain.StrainScale
import com.bipss.app.domain.strain.TrimpSex
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.MetricValue
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrainDetailScreen(
    navController: NavController,
    vm: StrainDetailViewModel = koinViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Strain") },
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
            when (val outcome = state.outcome) {
                null -> if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                is StrainOutcome.Unavailable -> EmptyState(
                    icon = Icons.Rounded.DirectionsWalk,
                    title = "No strain score today",
                    body = outcome.reason
                )
                is StrainOutcome.Available -> {
                    val r = outcome.result
                    BipssCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Today", style = MaterialTheme.typography.titleMedium,
                                 color = colors.textPrimary, modifier = Modifier.weight(1f))
                            FidelityBadge(if (outcome.restIsEstimate) Fidelity.ESTIMATE else Fidelity.PRODUCTION)
                        }
                        Spacer(Modifier.height(Space.s))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("%.1f".format(r.display), style = MetricValue, color = colors.textPrimary)
                            Spacer(Modifier.width(Space.s))
                            Text("of ${StrainScale.MAX_DISPLAY.roundToInt()}", style = MaterialTheme.typography.bodyMedium,
                                 color = colors.textTertiary, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        Spacer(Modifier.height(Space.s))
                        Text(
                            "Based on ${r.scoredMinutes} of ${r.activeMinutes} active minutes" +
                                if (r.scoredMinutes < r.activeMinutes) " (the rest had no usable heart rate)." else ".",
                            style = MaterialTheme.typography.bodySmall, color = colors.textSecondary
                        )
                    }
                    BipssCard {
                        SectionLabel("How it's worked out")
                        Spacer(Modifier.height(Space.s))
                        InfoRow("Training impulse (TRIMP)", "%.0f".format(r.trimp))
                        HairlineDivider()
                        InfoRow("Resting heart rate", "${r.hrRest.roundToInt()} bpm" + if (outcome.restIsEstimate) " (estimate)" else "")
                        HairlineDivider()
                        InfoRow("Maximum heart rate", "${r.hrMax.roundToInt()} bpm (from age)")
                        HairlineDivider()
                        InfoRow("Exertion bouts", "${r.bouts.size}")
                        Spacer(Modifier.height(Space.s))
                        Text(
                            buildString {
                                append("Only minutes with enough movement count: a raised heart rate while you are still is not training load. ")
                                append("Each bout is scored by Banister's TRIMP from its duration and heart-rate reserve, and the total is shown on a 0-21 scale. ")
                                if (outcome.restIsEstimate) append("Your resting heart rate is estimated from your lowest five-minute heart rate while still today, until the band has recorded a night's sleep. ")
                                if (r.sex == TrimpSex.UNSPECIFIED) append("It uses the average of the published male and female weightings.")
                            },
                            style = MaterialTheme.typography.bodySmall, color = colors.textSecondary
                        )
                    }
                    if (r.bouts.isNotEmpty()) BipssCard {
                        SectionLabel("Bouts")
                        Spacer(Modifier.height(Space.s))
                        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                        r.bouts.forEachIndexed { i, b ->
                            if (i > 0) HairlineDivider()
                            InfoRow(
                                "${fmt.format(Date(b.startMs))} · ${b.minutes} min",
                                "${(b.meanHrReserve * 100).roundToInt()}% HRR · TRIMP ${"%.0f".format(b.trimp)}"
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Space.xxl))
        }
    }
}
