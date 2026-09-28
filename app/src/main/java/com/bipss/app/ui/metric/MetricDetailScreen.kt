package com.bipss.app.ui.metric

import androidx.compose.foundation.layout.*
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
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).padding(horizontal = Space.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.m)
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
                    MetricLineChart(
                        points = state.points,
                        baseline = state.baseline,
                        lineColor = metricColor(metricType)
                    )
                }
            }

            item {
                if (!state.hasData && !state.isLoading) {
                    InfoBanner(
                        text = "No data recorded in this period. Wear the band to start building history.",
                        icon = Icons.Rounded.Info,
                        tint = colors.textTertiary
                    )
                } else {
                    BipssCard {
                        SectionLabel("This ${state.range.label.lowercase()}")
                        Spacer(Modifier.height(Space.m))
                        MetricStatsRow(
                            minValue     = state.stats?.minValue,
                            avgValue     = state.stats?.avgValue,
                            maxValue     = state.stats?.maxValue,
                            restingValue = state.restingValue,
                            // Resting HR comes from raw rows, which only span a week.
                            restingLabel = if (state.range == com.bipss.app.ui.metric.TimeRange.MONTH) "RESTING · 7D" else "RESTING",
                            unit         = meta.unit
                        )
                    }
                }
            }

            item {
                state.baseline?.let { baseline ->
                    BipssCard {
                        SectionLabel("Your baseline")
                        Spacer(Modifier.height(Space.s))
                        Text(
                            "${formatValue(metricType, baseline.toFloat())} ${meta.unit}".trim(),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            "The dashed line on the chart. Everything here is scored against " +
                                "your own history, not a population average.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                }
            }

            item {
                if (state.hasData) {
                    BipssCard {
                        SectionLabel("Reading the chart")
                        Spacer(Modifier.height(Space.s))
                        Text(
                            "Breaks in the line are periods with no readings - usually the band " +
                                "being off. Those gaps are left empty rather than filled in.",
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

private fun formatValue(metricType: String, value: Float): String = when (metricType) {
    MetricTypes.SKIN_TEMP, MetricTypes.AMBIENT -> "%.1f".format(value)
    else -> value.toInt().toString()
}

@Composable
private fun metricColor(metricType: String) = when (metricType) {
    MetricTypes.HR        -> BipssTheme.colors.heart
    MetricTypes.SPO2      -> BipssTheme.colors.spo2
    MetricTypes.SKIN_TEMP -> BipssTheme.colors.temp
    MetricTypes.HRV       -> BipssTheme.colors.activity
    else                  -> BipssTheme.colors.air
}
