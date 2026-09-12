package com.bipss.app.ui.sleep

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
import com.bipss.app.domain.sleep.ApneaScreening
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
 * Sleep, plus the overnight breathing screening.
 *
 * The screening deliberately shows its working rather than a verdict: a
 * progress bar toward the fourteen nights an average needs, and one browsable
 * pill per night. A judge or a clinician can see exactly what it will say on
 * night fourteen without the app having to pretend it already knows.
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
                title = { Text("Sleep") },
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
                .padding(horizontal = Space.gutter)
        ) {
            Spacer(Modifier.height(Space.m))

            // -- Last night --------------------------------------------------
            val last = state.sessions.lastOrNull()
            if (last == null) {
                EmptyState(
                    icon = Icons.Rounded.Bedtime,
                    title = "No sleep recorded yet",
                    body = "Wear the band overnight. Detection runs the following morning and " +
                        "needs at least 30 minutes of sleep to register a session."
                )
            } else {
                BipssCard {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${last.totalSleepMs / 3_600_000}h ${(last.totalSleepMs % 3_600_000) / 60_000}m",
                            style = MetricValue,
                            color = colors.textPrimary
                        )
                        Spacer(Modifier.width(Space.m))
                        state.lastNightScore?.let {
                            Text(
                                "score $it",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textTertiary,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.m))
                    HairlineDivider()
                    InfoRow("Efficiency", "${last.efficiencyPct}%")
                    HairlineDivider()
                    InfoRow("Awake after falling asleep", "${last.wakeAfterOnsetMs / 60_000} min")
                    HairlineDivider()
                    InfoRow("Awakenings", "${last.awakenings}")
                    last.avgHr?.let { HairlineDivider(); InfoRow("Average heart rate", "${it.toInt()} bpm") }
                    last.avgSpo2?.let { HairlineDivider(); InfoRow("Average SpO2", "${it.toInt()}%") }
                }
            }

            // -- Breathing screening ------------------------------------------
            Spacer(Modifier.height(Space.xxl))
            SectionLabel("Sleep-disordered breathing screening")
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
                    "${state.nightsCollected} of ${state.nightsRequired} nights collected",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )

                if (!state.hasAnyNight) {
                    Spacer(Modifier.height(Space.m))
                    Text(
                        "Wear the band overnight to begin screening. Each night is analysed on " +
                            "its own and shown here from the first one.",
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
                            text = "A screening summary appears after ${state.nightsRequired} nights. " +
                                "Individual nights are shown above.",
                            icon = Icons.Rounded.Info,
                            tint = colors.textTertiary
                        )
                    }

                    Spacer(Modifier.height(Space.m))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val report = vm.buildPhysicianReport()
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "BIPSS overnight breathing record")
                                    putExtra(Intent.EXTRA_TEXT, report)
                                }
                                context.startActivity(
                                    Intent.createChooser(send, "Share record")
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Space.s))
                        Text("Export for physician")
                    }
                }
            }

            Spacer(Modifier.height(Space.m))
            Text(
                "This screening counts oxygen desaturation events. It is not a diagnosis, and " +
                    "BIPSS is not a medical device. Discuss any concern with a physician.",
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
                contentDescription = "Previous night",
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
                    if (night.eventCount == 1) "1 desaturation event"
                    else "${night.eventCount} desaturation events",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
                Text(
                    "${dateFmt.format(Date(night.sessionStartMs))} · " +
                        "${timeFmt.format(Date(night.sessionStartMs))}–" +
                        timeFmt.format(Date(night.sessionEndMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
                Text(
                    "ODI ${String.format("%.1f", night.odi)}/hour · ${ApneaScreening.odiCategory(night.odi)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary
                )
                // Surfacing rejections rather than hiding them lets a reader
                // judge the night's signal quality instead of trusting a bare count.
                if (night.rejectedArtefactCount > 0) {
                    Text(
                        "${night.rejectedArtefactCount} excluded as movement artefact",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary
                    )
                }
            }
        }

        IconButton(onClick = onNext, enabled = index < total - 1) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = "Next night",
                tint = if (index < total - 1) colors.textPrimary else colors.textTertiary
            )
        }
    }
}

@Composable
private fun odiColour(odi: Float): Color {
    val colors = BipssTheme.colors
    return when {
        odi < 5f  -> colors.bandNormal
        odi < 15f -> colors.bandMonitor
        else      -> colors.bandRisk
    }
}
