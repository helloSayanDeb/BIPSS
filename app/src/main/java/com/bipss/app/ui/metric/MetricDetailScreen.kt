package com.bipss.app.ui.metric

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.data.MetricTypes
import com.bipss.app.ui.components.BipssCard
import com.bipss.app.ui.components.InfoBanner
import com.bipss.app.ui.components.SectionLabel
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.MetricUnit
import com.bipss.app.ui.theme.MetricValue
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Full history for one metric.
 *
 * The chart deliberately breaks its line wherever the band was not worn rather
 * than interpolating across the gap. A continuous line through a twelve-hour
 * hole would be inventing twelve hours of data, and the gaps are also what make
 * a real recording look real.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricDetailScreen(
    metricType: String,
    navController: NavController,
    vm: MetricDetailViewModel = koinViewModel { parametersOf(metricType) }
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors
    val meta = metricMeta(metricType)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(meta.label) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = AppText.text(R.string.copy_76900f1bfd16, "Back"))
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).padding(horizontal = Space.gutter),
            contentPadding = PaddingValues(top = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.l)
        ) {
            item {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        state.currentValue?.let { formatValue(metricType, it) } ?: "—",
                        style = MetricValue,
                        color = if (state.currentValue != null) colors.textPrimary else colors.textTertiary
                    )
                    if (meta.unit.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            meta.unit,
                            style = MetricUnit,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(Space.xs))
                // Live while the band is sending this reading; otherwise say what the number is.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.isLive) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(colors.bandNormal))
                        Spacer(Modifier.width(Space.s))
                        Text(AppText.text(R.string.copy_7870f7ada23e, "Live from your band"), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    } else if (state.currentValue != null) {
                        Text(AppText.text(R.string.copy_0acd34970b0a, "Latest reading in the chart"), style = MaterialTheme.typography.labelMedium, color = colors.textTertiary)
                    }
                }
            }

            item {
                TimeRangeSelector(
                    selected = state.range,
                    onSelect = { vm.setRange(it) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                BipssCard {
                    val end = state.updatedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
                    MetricLineChart(
                        points = state.points,
                        baseline = state.baseline,
                        lineColor = metricColor(metricType),
                        windowStartMs = end - state.range.durationMs,
                        windowEndMs = end,
                        timeTicks = timeTicks(state.range, end),
                        decimals = decimalsFor(metricType)
                    )
                }
            }

            item {
                if (!state.hasData && !state.isLoading) {
                    InfoBanner(
                        text = if (state.range == TimeRange.LIVE)
                            AppText.text(R.string.copy_0f62cb64d173, "No readings in the last 5 minutes.")
                        else AppText.text(R.string.copy_671564f7f8af, "No data recorded in this period. Wear the band to start building history."),
                        icon = Icons.Rounded.Info,
                        tint = colors.textTertiary
                    )
                } else {
                    BipssCard {
                        SectionLabel(
                            when (state.range) {
                                TimeRange.LIVE  -> AppText.text(R.string.copy_ca6a7a4c781e, "Last 5 minutes")
                                TimeRange.DAY   -> AppText.text(R.string.copy_5c37cf8f018b, "Last 24 hours")
                                TimeRange.WEEK  -> AppText.text(R.string.copy_0603deca4fcb, "Last 7 days")
                                TimeRange.MONTH -> AppText.text(R.string.copy_f8f03fb441b8, "Last 30 days")
                            }
                        )
                        Spacer(Modifier.height(Space.m))
                        MetricStatsRow(
                            minValue     = state.stats?.minValue,
                            avgValue     = state.stats?.avgValue,
                            maxValue     = state.stats?.maxValue,
                            restingValue = state.restingValue,
                            // Resting HR comes from raw rows, which only span a week.
                            restingLabel = if (state.range == com.bipss.app.ui.metric.TimeRange.MONTH) AppText.text(R.string.copy_54c9d1ea2c54, "RESTING · 7D") else AppText.text(R.string.copy_266af3af0b6a, "RESTING"),
                            unit         = meta.unit,
                            decimals     = decimalsFor(metricType)
                        )
                    }
                }
            }

            item {
                state.baseline?.let { baseline ->
                    BipssCard {
                        SectionLabel(AppText.text(R.string.copy_41cb8b8120ad, "Your baseline"))
                        Spacer(Modifier.height(Space.s))
                        Text(
                            "${formatValue(metricType, baseline.toFloat())} ${meta.unit}".trim(),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            AppText.text(R.string.copy_7b4da3cfc6fa, "The dashed line on the chart. Everything here is scored against ") +
                                AppText.text(R.string.copy_5e0082594a8b, "your own history, not a population average."),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                }
            }

            item {
                if (state.hasData) {
                    BipssCard {
                        SectionLabel(AppText.text(R.string.copy_b5accd0d9b46, "Reading the chart"))
                        Spacer(Modifier.height(Space.s))
                        Text(
                            AppText.text(R.string.copy_22aa8ae61958, "Breaks in the line are periods with no readings - usually the band ") +
                                AppText.text(R.string.copy_4ddae9b646b0, "being off. Those gaps are left empty rather than filled in."),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(Space.huge)) }
        }
    }
}

/** Temperatures to a tenth of a degree; heart rate, SpO2 and PRV as whole numbers, as the band reports them. */
private fun decimalsFor(metricType: String): Int = when (metricType) {
    MetricTypes.SKIN_TEMP, MetricTypes.AMBIENT -> 1
    else -> 0
}

private fun formatValue(metricType: String, value: Float): String = when (metricType) {
    MetricTypes.SKIN_TEMP, MetricTypes.AMBIENT -> "%.1f".format(value)
    else -> value.toInt().toString()
}

@Composable
private fun metricColor(metricType: String) = when (metricType) {
    MetricTypes.HR        -> BipssTheme.colors.heart
    MetricTypes.SPO2      -> BipssTheme.colors.spo2
    MetricTypes.SKIN_TEMP -> BipssTheme.colors.temp
    MetricTypes.PRV       -> BipssTheme.colors.activity
    else                  -> BipssTheme.colors.air
}
