package com.bipss.app.ui.dashboard

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.data.DailyActivityData
import com.bipss.app.data.DailyActivityRepository
import com.bipss.app.domain.*
import com.bipss.app.domain.time.LocalDay
import com.bipss.app.navigation.Routes
import com.bipss.app.ui.activity.WellnessPage
import com.bipss.app.ui.components.*
import com.bipss.app.ui.metric.GraphPoint
import com.bipss.app.ui.metric.MetricLineChart
import com.bipss.app.ui.theme.*
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun InsightDetailScreen(type: RiskScoreType, nav: NavController,
    vm: DashboardViewModel = koinViewModel(), repo: DailyActivityRepository = koinInject()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val data by repo.data.collectAsStateWithLifecycle(initialValue = DailyActivityData())
    val now = rememberNowMs()
    val today = LocalDay.epochDay(now)
    var day by rememberSaveable(type.name) { mutableLongStateOf(today) }
    var range by rememberSaveable(type.name) { mutableIntStateOf(7) }
    var metric by rememberSaveable(type.name) { mutableStateOf("score") }
    val current = state.riskScores.firstOrNull { it.type == type }?.let(InsightRecord::from)
    val records = remember(data.insights, data.health, type, today, current) {
        val saved = data.insights.mapNotNull { (d, records) -> records[type.name]?.let { d to it } }.toMap().toMutableMap()
        // Earlier versions saved only the three daily pillars; retain those real numbers.
        data.health.forEach { (d, h) ->
            val value = when (type) { RiskScoreType.SLEEP -> h.sleep?.toDouble(); RiskScoreType.RECOVERY -> h.recovery?.toDouble(); RiskScoreType.STRAIN -> h.strain?.toDouble(); else -> null }
            if (d !in saved && value != null) saved[d] = InsightRecord(type.name, 0L, value,
                "${number(value)} / ${if (type == RiskScoreType.STRAIN) 21 else 100}", if (type == RiskScoreType.STRAIN) 21 else 100,
                AppText.text(R.string.copy_298b0fb0a5f1, "Recorded score"), true, false, emptyList(), emptyList(), emptyList(), AppText.text(R.string.copy_7a3836ba1440, "Input details were not saved with this earlier record."))
        }
        if (current != null) saved[today] = current
        saved.toMap()
    }
    val selected = records[day]
    val start = today - range + 1
    val window = records.filterKeys { it in start..today }.toSortedMap()
    val inputOptions = window.values.flatMap { it.inputs }.filter { it.value != null }.distinctBy { it.key }
    val input = inputOptions.firstOrNull { it.key == metric }
    val chartKey = if (input == null) "score" else metric
    val points = (start..today).map { d ->
        val record = records[d]
        val value = if (chartKey == "score") record?.value else record?.inputs?.firstOrNull { it.key == chartKey }?.value
        GraphPoint(LocalDay.startOfDayMs(d), value?.toFloat() ?: Float.NaN)
    }
    val present = points.filter { it.value.isFinite() }
    val colors = BipssTheme.colors
    WellnessPage(type.displayName, nav) {
        DashboardDateSelector(day, { day = it })
        BipssCard {
            Text(if (day == today) AppText.text(R.string.copy_2b065c7c9ce4, "Today") else date(day), style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
            Spacer(Modifier.height(Space.s))
            Text(selected?.display ?: "—", style = MaterialTheme.typography.displaySmall, color = colors.textPrimary)
            Spacer(Modifier.height(Space.s))
            Text(selected?.status?.let(AppText::display) ?: AppText.text(R.string.copy_10a3cf67d97f, "No calculation recorded for this day."), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            selected?.takeIf { it.atMs > 0 }?.let {
                Spacer(Modifier.height(Space.s))
                Text(AppText.text(R.string.copy_4de60bf219d2, "Calculated %1\$s", time(it.atMs)), style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
            }
            if (selected?.partial == true) Text(AppText.text(R.string.copy_4b3f17a98a8c, "Based on partial data"), style = MaterialTheme.typography.labelMedium, color = colors.bandMonitor)
            if (selected?.provisional == true) Text(AppText.text(R.string.copy_bf32ea333fd9, "Your usual range is still building"), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        }
        SectionHeader(AppText.text(R.string.copy_f0e187b30f85, "Your history"))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            listOf(7, 30, 90).forEach { n -> FilterChip(selected = range == n, onClick = { range = n }, label = { Text(AppText.text(R.string.copy_debd3794fc2c, "%1\$s days", n)) }) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            FilterChip(selected = chartKey == "score", onClick = { metric = "score" }, label = { Text(if (type == RiskScoreType.AUTONOMIC_LOAD) AppText.text(R.string.copy_43a17a8495f7, "Load level") else AppText.text(R.string.copy_38e5a46cbc5a, "Score")) })
            inputOptions.forEach { item -> FilterChip(selected = chartKey == item.key, onClick = { metric = item.key }, label = { Text(AppText.display(item.label)) }) }
        }
        BipssCard {
            Text(input?.let { AppText.display(it.label) + if (it.unit.isNotBlank()) " · ${it.unit}" else "" } ?: if (type == RiskScoreType.AUTONOMIC_LOAD) AppText.text(R.string.copy_030ed29be826, "Recorded load levels") else "${type.displayName} · /${selected?.scale?.takeIf { it > 0 } ?: if (type == RiskScoreType.STRAIN) 21 else 100}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.m))
            if (type == RiskScoreType.AUTONOMIC_LOAD && chartKey == "score") {
                Text(AppText.text(R.string.copy_9cf6811f0eed, "Load is recorded as Low, Elevated or High. Choose an input above to see its numeric trend."), color = colors.textSecondary)
            } else if (present.isEmpty()) {
                Text(AppText.text(R.string.copy_9c1f2440f875, "No values recorded in this period. Your chart will build as readings become available."), color = colors.textSecondary)
            } else {
                MetricLineChart(points, null, height = 180.dp, lineColor = MaterialTheme.colorScheme.primary,
                    windowStartMs = LocalDay.startOfDayMs(start), windowEndMs = LocalDay.startOfDayMs(today + 1),
                    timeTicks = listOf(LocalDay.startOfDayMs(start) to date(start), LocalDay.startOfDayMs(today) to AppText.text(R.string.copy_2b065c7c9ce4, "Today")), decimals = 1)
                Text(AppText.text(R.string.copy_a37839c6c17b, "%1\$s of %2\$s days recorded · gaps mean no record", present.size, range), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            Spacer(Modifier.height(Space.s))
            Text(AppText.text(R.string.copy_1c73375d8a68, "Latest saved calculation for each day, not a daily average. Today can change."), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
        }
        if (window.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                window.entries.toList().asReversed().forEach { (d, record) ->
                    FilterChip(selected = day == d, onClick = { day = d }, label = { Text("${date(d)} · ${record.display}") })
                }
            }
        }
        SectionHeader(AppText.text(R.string.copy_a374c0a69c3d, "Inputs for %1\$s", if (day == today) "today" else date(day)))
        if (selected == null || selected.inputs.isEmpty()) {
            BipssCard { Text(if (selected == null) AppText.text(R.string.copy_f7d41196da1b, "No inputs saved for this day.") else AppText.text(R.string.copy_3fd0be8ce6ef, "Input details were not saved with this record."), color = colors.textSecondary) }
        } else selected.inputs.forEach { item ->
            BipssCard {
                InfoRow(AppText.display(item.label), item.value?.let { "${number(it)} ${item.unit}".trim() } ?: if (item.isNumeric) AppText.text(R.string.copy_67a926f7008e, "Not available") else "")
                if (item.detail.isNotBlank()) Text(AppText.display(item.detail), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                item.baseline?.let { Text(AppText.text(R.string.copy_6c0148ca8b76, "Your usual value: %1\$s %2\$s", number(it), item.unit), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary) }
            }
        }
        selected?.missing?.takeIf { it.isNotEmpty() }?.let { missing ->
            BipssCard { Text(AppText.text(R.string.copy_b60de3ca37f2, "Still needed"), style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(Space.s)); Text(missing.joinToString(" · "), color = colors.textSecondary) }
        }
        if (selected?.factors?.isNotEmpty() == true) {
            BipssCard { Text(AppText.text(R.string.copy_881f505f1d84, "What contributed"), style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(Space.s)); selected.factors.forEach { Text(AppText.display(it), style = MaterialTheme.typography.bodyMedium) } }
        }
        BipssCard {
            Text(AppText.text(R.string.copy_36ffdc3470f3, "How to read this insight"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.s))
            Text(selected?.method?.let(AppText::display) ?: type.method, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            Spacer(Modifier.height(Space.m))
            Text(when (type) {
                RiskScoreType.STRAIN -> AppText.text(R.string.copy_5130ea1679ad, "Higher means more cardiovascular effort, not better health. Compare with your recovery and how you feel.")
                RiskScoreType.FLOOD -> AppText.text(R.string.copy_1644d017d24e, "This reflects listed warnings, not a prediction of flooding at your exact location. Follow current local instructions.")
                RiskScoreType.AUTONOMIC_LOAD -> AppText.text(R.string.copy_ea941287fee9, "Several factors can change this experimental indicator. It does not measure emotional stress or diagnose illness.")
                else -> if (type.higherIsBetter) AppText.text(R.string.copy_c10e3ef1b2bb, "Higher generally indicates a stronger result for this measure. Consider your own pattern and how you feel.") else AppText.text(R.string.copy_82dd7b6d5514, "Higher indicates more concern in the available inputs. This estimate cannot diagnose or rule out illness.")
            }, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        val route = when (type) { RiskScoreType.SLEEP -> Routes.SLEEP_DETAIL; RiskScoreType.RECOVERY -> Routes.RECOVERY_DETAIL; RiskScoreType.STRAIN -> Routes.STRAIN_DETAIL; else -> null }
        if (route != null && day == today) OutlinedButton(onClick = { nav.navigate(route) }, modifier = Modifier.fillMaxWidth()) { Text(AppText.text(R.string.copy_ec53598c022f, "Explore %1\$s readings", type.displayName.lowercase())) }
    }
}

private fun number(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.getDefault(), "%.1f", value)
private fun date(day: Long) = LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("d MMM"))
private fun time(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, h:mm a"))
