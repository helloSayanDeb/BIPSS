package com.bipss.app.ui.sleep

import com.bipss.app.localization.AppText
import com.bipss.app.R

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.domain.sleep.DesaturationScreening
import com.bipss.app.domain.sleep.NightScreening
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.MetricValue
import com.bipss.app.ui.theme.Space
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sleep, plus the overnight oxygen-dip count.
 *
 * The count shows its working rather than a verdict: progress toward the
 * fourteen nights an average needs, and one browsable pill per night. It
 * reports numbers; what they mean is for a clinician.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepDetailScreen(
    navController: NavController,
    vm: SleepDetailViewModel = koinViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(AppText.text(R.string.copy_d466bcf52eb6, "Sleep")) },
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
                .padding(horizontal = Space.gutter)
        ) {
            androidx.compose.material3.TextButton(onClick = { navController.navigate(com.bipss.app.navigation.Routes.SLEEP_STAGES) }) {
                Text(AppText.text(R.string.copy_bf7e1c9d769b, "Explore sleep stages"))
            }
            Spacer(Modifier.height(Space.m))

            // -- Last night --------------------------------------------------
            val last = state.sessions.lastOrNull()
            if (last == null) {
                EmptyState(
                    icon = Icons.Rounded.Bedtime,
                    title = AppText.text(R.string.copy_e7818d7d0f0d, "No sleep recorded yet"),
                    body = AppText.text(R.string.copy_50c68651a6b8, "Wear your band overnight. Your sleep summary will appear after your next recorded night.")
                )
            } else {
                BipssCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(AppText.text(R.string.copy_257b14f3f959, "Latest sleep · %1\$s", SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(last.endMs))), style = MaterialTheme.typography.titleMedium,
                             color = colors.textPrimary, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(Space.s))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            AppText.text(R.string.copy_279d04514bff, "%1\$sh %2\$sm", last.totalSleepMs / 3_600_000, (last.totalSleepMs % 3_600_000) / 60_000),
                            style = MetricValue,
                            color = colors.textPrimary
                        )
                        Spacer(Modifier.width(Space.m))
                        com.bipss.app.data.SleepRepository.score(last).let {
                            Text(
                                AppText.text(R.string.copy_ed29f7132188, "score %1\$s", it),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textTertiary,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.m))
                    HairlineDivider()
                    InfoRow(AppText.text(R.string.copy_87b180c8f204, "Efficiency"), "${last.efficiencyPct}%")
                    HairlineDivider()
                    InfoRow(AppText.text(R.string.copy_6da9a0e51e28, "Awake after falling asleep"), AppText.text(R.string.copy_3e16be5f4e1d, "%1\$s min", last.wakeAfterOnsetMs / 60_000))
                    HairlineDivider()
                    InfoRow(AppText.text(R.string.copy_652e9be4e639, "Awakenings"), "${last.awakenings}")
                    last.avgHr?.let { HairlineDivider(); InfoRow(AppText.text(R.string.copy_90440886e9c7, "Average heart rate"), AppText.text(R.string.copy_b8300f6c066e, "%1\$s bpm", it.toInt())) }
                    last.avgSpo2?.let { HairlineDivider(); InfoRow(AppText.text(R.string.copy_814f8e271596, "Average SpO2"), "${it.toInt()}%") }
                }
            }

            // -- Breathing screening ------------------------------------------
            Spacer(Modifier.height(Space.xxl))
            SectionLabel(AppText.text(R.string.copy_17a957727dd5, "Overnight oxygen dips"))
            Spacer(Modifier.height(Space.m))

            BipssCard {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.bandNormal,
                    trackColor = colors.hairline
                )
                Spacer(Modifier.height(Space.s))
                Text(
                    AppText.text(R.string.copy_07bed06d948b, "%1\$s of %2\$s nights collected", state.nightsCollected, state.nightsRequired),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )

                if (!state.hasAnyNight) {
                    Spacer(Modifier.height(Space.m))
                    Text(
                        AppText.text(R.string.copy_f51b03b449e0, "Wear the band overnight to start counting. Each night is analysed on ") +
                            AppText.text(R.string.copy_2f08f1f7d438, "its own and shown here from the first one."),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary
                    )
                } else {
                    Spacer(Modifier.height(Space.l))
                    NightPill(
                        night = state.selected,
                        index = state.selectedIndex,
                        total = state.screenings.size,
                        onPrevious = { vm.selectPrevious() },
                        onNext = { vm.selectNext() }
                    )

                    Spacer(Modifier.height(Space.l))
                    // The aggregate statement appears only once the data can
                    // carry it. Below fourteen nights the app says so plainly
                    // rather than issuing a verdict it cannot support.
                    val summary = state.summary
                    if (summary != null) {
                        InfoBanner(
                            text = summary,
                            icon = Icons.Rounded.Info,
                            tint = colors.bandMonitor
                        )
                    } else {
                        InfoBanner(
                            text = AppText.text(R.string.copy_99a8330335d7, "An average appears after %1\$s nights. ", state.nightsRequired) +
                                AppText.text(R.string.copy_3bc015b2d4ee, "Individual nights are shown above."),
                            icon = Icons.Rounded.Info,
                            tint = colors.textTertiary
                        )
                    }

                    Spacer(Modifier.height(Space.m))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                context.startActivity(vm.physicianReportShare())
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Space.s))
                        Text(AppText.text(R.string.copy_d321a0a5ed9f, "Export for physician"))
                    }
                }
            }

            Spacer(Modifier.height(Space.m))
            Text(
                AppText.text(R.string.copy_6cbd050207c4, "This counts drops in blood oxygen during sleep detected from wrist movement - both ") +
                    AppText.text(R.string.copy_edf81a2f3c95, "estimates from a consumer band. It is not a diagnosis, and BIPSS is not a medical ") +
                    AppText.text(R.string.copy_d7f73a4cc808, "device. If you are concerned about your breathing during sleep, talk to a doctor."),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary
            )

            Spacer(Modifier.height(Space.huge))
        }
    }
}

/** One night, browsable with the arrows either side. */
@Composable
private fun NightPill(
    night: NightScreening?,
    index: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val colors = BipssTheme.colors
    if (night == null) return

    val dateFmt = SimpleDateFormat("d MMM", Locale.UK)
    val timeFmt = SimpleDateFormat("HH:mm", Locale.UK)
    val tint = odiColour(night.odi)

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, enabled = index > 0) {
            Icon(
                Icons.Rounded.ChevronLeft,
                contentDescription = AppText.text(R.string.copy_0e68927897f6, "Previous night"),
                tint = if (index > 0) colors.textPrimary else colors.textTertiary
            )
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = tint.copy(alpha = 0.15f),
            modifier = Modifier.weight(1f)
        ) {
            Column(Modifier.padding(Space.m)) {
                Text(
                    if (night.eventCount == 1) AppText.text(R.string.copy_a6c61e677619, "1 oxygen dip counted")
                    else AppText.text(R.string.copy_b65b3831c8c6, "%1\$s oxygen dips counted", night.eventCount),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    "${dateFmt.format(Date(night.sessionStartMs))} · " +
                        "${timeFmt.format(Date(night.sessionStartMs))}–" +
                        timeFmt.format(Date(night.sessionEndMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    DesaturationScreening.odiDescription(night.odi),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary
                )
                // Surfacing rejections rather than hiding them lets a reader
                // judge the night's signal quality instead of trusting a bare count.
                if (night.rejectedArtefactCount > 0) {
                    Text(
                        AppText.text(R.string.copy_3094b01b05f2, "%1\$s more not counted (movement or under 10 s)", night.rejectedArtefactCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary
                    )
                }
            }
        }

        IconButton(onClick = onNext, enabled = index < total - 1) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = AppText.text(R.string.copy_4ba23cc30c55, "Next night"),
                tint = if (index < total - 1) colors.textPrimary else colors.textTertiary
            )
        }
    }
}

/**
 * One neutral colour for every night. Green/amber/red at 5 and 15 per hour
 * would be clinical severity grading, which a consumer band's count cannot
 * support.
 */
@Composable
private fun odiColour(@Suppress("UNUSED_PARAMETER") odi: Float): Color {
    return BipssTheme.colors.sleep
}
