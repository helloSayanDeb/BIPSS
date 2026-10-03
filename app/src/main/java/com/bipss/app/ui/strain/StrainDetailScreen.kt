package com.bipss.app.ui.strain

import com.bipss.app.localization.AppText
import com.bipss.app.R

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
                title = { Text(AppText.text(R.string.copy_fcde25fd44fb, "Strain")) },
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
            when (val outcome = state.outcome) {
                null -> if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                is StrainOutcome.Unavailable -> EmptyState(
                    icon = Icons.Rounded.DirectionsWalk,
                    title = AppText.text(R.string.copy_66af2cba9e6d, "No strain score today"),
                    body = outcome.reason
                )
                is StrainOutcome.Available -> {
                    val r = outcome.result
                    BipssCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(AppText.text(R.string.copy_2b065c7c9ce4, "Today"), style = MaterialTheme.typography.titleMedium,
                                 color = colors.textPrimary, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(Space.s))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("%.1f".format(r.display), style = MetricValue, color = colors.textPrimary)
                            Spacer(Modifier.width(Space.s))
                            Text(AppText.text(R.string.copy_25c8122ba463, "of %1\$s", StrainScale.MAX_DISPLAY.roundToInt()), style = MaterialTheme.typography.bodyMedium,
                                 color = colors.textTertiary, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        Spacer(Modifier.height(Space.s))
                        Text(
                            AppText.text(R.string.copy_05d8d625137c, "Based on %1\$s of %2\$s active minutes", r.scoredMinutes, r.activeMinutes) +
                                if (r.scoredMinutes < r.activeMinutes) AppText.text(R.string.copy_8bf4afea2e8f, " (the rest had no usable heart rate).") else ".",
                            style = MaterialTheme.typography.bodySmall, color = colors.textSecondary
                        )
                    }
                    BipssCard {
                        SectionLabel(AppText.text(R.string.copy_1efabce5b38a, "How it's worked out"))
                        Spacer(Modifier.height(Space.s))
                        InfoRow(AppText.text(R.string.copy_bc931395daad, "Training impulse (TRIMP)"), "%.0f".format(r.trimp))
                        HairlineDivider()
                        InfoRow(AppText.text(R.string.copy_3fc0fe1697ca, "Resting heart rate"), AppText.text(R.string.copy_b8300f6c066e, "%1\$s bpm", r.hrRest.roundToInt()) + if (outcome.restIsEstimate) AppText.text(R.string.copy_fccf0ff7de2d, " (your stillest stretch today; no sleep recorded yet)") else AppText.text(R.string.copy_a0eedc34bb26, " (from last night's sleep)"))
                        HairlineDivider()
                        InfoRow(AppText.text(R.string.copy_9a5ee2f36204, "Maximum heart rate"), AppText.text(R.string.copy_5d7a5ff864cd, "%1\$s bpm (from age)", r.hrMax.roundToInt()))
                        HairlineDivider()
                        InfoRow(AppText.text(R.string.copy_5918a2970b58, "Exertion bouts"), "${r.bouts.size}")
                    }
                    if (r.bouts.isNotEmpty()) BipssCard {
                        SectionLabel(AppText.text(R.string.copy_8b5a625e3141, "Bouts"))
                        Spacer(Modifier.height(Space.s))
                        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                        r.bouts.forEachIndexed { i, b ->
                            if (i > 0) HairlineDivider()
                            InfoRow(
                                AppText.text(R.string.copy_a1572e58740c, "%1\$s · %2\$s min", fmt.format(Date(b.startMs)), b.minutes),
                                AppText.text(R.string.copy_e945975a0b97, "%1\$s% HRR · TRIMP %2\$s", (b.meanHrReserve * 100).roundToInt(), "%.0f".format(b.trimp))
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Space.xxl))
        }
    }
}
