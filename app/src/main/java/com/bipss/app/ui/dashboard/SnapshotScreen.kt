package com.bipss.app.ui.dashboard

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.data.MetricTypes
import com.bipss.app.domain.time.LocalDay
import com.bipss.app.navigation.Routes
import com.bipss.app.ui.activity.WellnessPage
import com.bipss.app.ui.components.*
import com.bipss.app.ui.metric.*
import com.bipss.app.ui.theme.*
import org.koin.androidx.compose.koinViewModel
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun SnapshotScreen(day: Long, nav: NavController, vm: DayOverviewViewModel = koinViewModel()) {
    LaunchedEffect(day) { vm.select(day) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors
    WellnessPage(AppText.text(R.string.copy_6ad27bd4ec33, "Snapshot"), nav) {
        Text(LocalDate.ofEpochDay(ui.day).format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.titleLarge)
        DashboardDateSelector(ui.day, vm::select)
        SectionHeader(AppText.text(R.string.copy_d466bcf52eb6, "Sleep"))
        BipssCard {
            val sleep = ui.sleep
            Text(sleep?.let { AppText.text(R.string.copy_279d04514bff, "%1\$sh %2\$sm", it.totalSleepMs / 3_600_000, (it.totalSleepMs / 60_000) % 60) } ?: "—",
                style = MaterialTheme.typography.displaySmall, color = colors.sleep)
            Spacer(Modifier.height(Space.s))
            Text(sleep?.let { "${time(it.startMs)} – ${time(it.endMs)}" } ?: AppText.text(R.string.copy_ea04c1f56b48, "No sleep recorded for this day"), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            if (sleep != null) {
                Spacer(Modifier.height(Space.l))
                InfoRow(AppText.text(R.string.copy_42b64099e0ba, "Sleep score"), ui.health.sleep?.toString() ?: "—")
                InfoRow(AppText.text(R.string.copy_f44280e29037, "Time asleep"), AppText.text(R.string.copy_2e53da5a7298, "%1\$s% of the sleep period", sleep.efficiencyPct))
                Spacer(Modifier.height(Space.s))
                LinearProgressIndicator(progress = { (sleep.efficiencyPct / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = colors.sleep)
                InfoRow(AppText.text(R.string.copy_5450684f3973, "Awake during the night"), AppText.text(R.string.copy_3e16be5f4e1d, "%1\$s min", sleep.wakeAfterOnsetMs / 60_000))
                InfoRow(AppText.text(R.string.copy_5f1f15d3b632, "Interruptions"), sleep.awakenings.toString())
                sleep.avgSpo2?.let { InfoRow(AppText.text(R.string.copy_c75da3d06120, "Average blood oxygen"), "%.0f%%".format(it)) }
            }
        }
        SectionHeader(AppText.text(R.string.copy_38da1505ca83, "Activity"))
        BipssCard {
            Text(ui.health.strain?.let { "%.1f".format(it) } ?: "—", style = MaterialTheme.typography.displaySmall, color = colors.activity)
            Text(AppText.text(R.string.copy_d9bc3f4e9547, "Daily strain · out of 21"), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            Spacer(Modifier.height(Space.l))
            InfoRow(AppText.text(R.string.copy_1de3df70ddf4, "Steps"), ui.steps?.let { "%,d".format(it) } ?: AppText.text(R.string.copy_b37c7879f6c3, "Not recorded"))
            InfoRow(AppText.text(R.string.copy_b335ff34787a, "Logged activity"), AppText.text(R.string.copy_3e16be5f4e1d, "%1\$s min", ui.activities.sumOf { it.minutes }))
            InfoRow(AppText.text(R.string.copy_fdfd56ca6a17, "Water logged"), AppText.text(R.string.copy_e52749128c70, "%1\$s mL", ui.waterMl))
        }
        DayEvents(ui, { nav.navigate(Routes.workout(it)) })
        SectionHeader(AppText.text(R.string.copy_48f6a8d5688b, "Recovery"))
        BipssCard {
            Text(ui.health.recovery?.toString() ?: "—", style = MaterialTheme.typography.displaySmall, color = colors.bandNormal)
            Text(if (ui.health.recovery == null) AppText.text(R.string.copy_f4276110ca98, "No recovery score recorded for this day") else AppText.text(R.string.copy_443b1ad122a6, "Recovery · out of 100"), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            Spacer(Modifier.height(Space.l))
            InfoRow(AppText.text(R.string.copy_177eba7d5046, "Average pulse variability"), ui.vitals[MetricTypes.PRV]?.avgValue?.let { AppText.text(R.string.copy_65595dc0d22e, "%.0f ms").format(it) } ?: "—")
            InfoRow(AppText.text(R.string.copy_2bfbb848ae7f, "Sleeping heart rate"), ui.sleep?.avgHr?.let { AppText.text(R.string.copy_d62366697763, "%.0f bpm").format(it) } ?: "—")
            InfoRow(AppText.text(R.string.copy_0b855b71533c, "Average skin temperature"), ui.vitals[MetricTypes.SKIN_TEMP]?.avgValue?.let { AppText.text(R.string.copy_89a292a70119, "%.1f °C").format(it) } ?: "—")
        }
        SectionHeader(AppText.text(R.string.copy_2cecbe070240, "Heart rate through the day"))
        BipssCard {
            if (ui.heartTrace.isEmpty()) Text(AppText.text(R.string.copy_f36041b8e842, "No heart-rate readings recorded for this day."), color = colors.textSecondary)
            else {
                InfoRow(AppText.text(R.string.copy_b38ae2c5b845, "Average"), ui.vitals[MetricTypes.HR]?.avgValue?.let { AppText.text(R.string.copy_d62366697763, "%.0f bpm").format(it) } ?: "—")
                MetricLineChart(ui.heartTrace, null, lineColor = colors.heart, height = 180.dp,
                    windowStartMs = LocalDay.startOfDayMs(ui.day), windowEndMs = LocalDay.startOfDayMs(ui.day + 1),
                    timeTicks = listOf(LocalDay.startOfDayMs(ui.day) to AppText.text(R.string.copy_e4b2fb57081f, "12 am"), LocalDay.startOfDayMs(ui.day) + 12 * 3_600_000L to AppText.text(R.string.copy_51d966e495d1, "12 pm")), decimals = 0)
            }
        }
        PrimaryButton(AppText.text(R.string.copy_70b288a0e6a0, "Explore your trends"), onClick = { nav.navigate(Routes.TRENDS) })
    }
}

@Composable fun TrendsScreen(nav: NavController, vm: DayOverviewViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var range by remember { mutableIntStateOf(7) }
    val today = LocalDay.epochDay(System.currentTimeMillis())
    val colors = BipssTheme.colors
    WellnessPage(AppText.text(R.string.copy_068863289325, "Your trends"), nav) {
        Text(AppText.text(R.string.copy_8b3a66540493, "A longer view of your daily rhythm"), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            listOf(7, 30, 90).forEach { days -> FilterChip(selected = range == days, onClick = { range = days }, label = { Text(AppText.text(R.string.copy_debd3794fc2c, "%1\$s days", days)) }) }
        }
        listOf(AppText.text(R.string.copy_d466bcf52eb6, "Sleep"), AppText.text(R.string.copy_48f6a8d5688b, "Recovery"), AppText.text(R.string.copy_fcde25fd44fb, "Strain"), AppText.text(R.string.copy_1de3df70ddf4, "Steps")).forEachIndexed { index, title ->
            val points = ((today - range + 1)..today).map { day ->
                val h = ui.activity.health[day]
                val v = when(index) { 0 -> h?.sleep?.toFloat(); 1 -> h?.recovery?.toFloat(); 2 -> h?.strain; else -> ui.activity.bandSteps[day]?.toFloat() }
                GraphPoint(LocalDay.startOfDayMs(day), v ?: Float.NaN)
            }
            val measured = points.filter { it.value.isFinite() }
            BipssCard {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Space.s))
                if (measured.isEmpty()) Text(AppText.text(R.string.copy_c6c5ede69d79, "Your trend appears as daily records build."), color = colors.textSecondary)
                else {
                    Text(if (index == 2) "%.1f".format(measured.map { it.value }.average()) else "%.0f".format(measured.map { it.value }.average()),
                        style = MaterialTheme.typography.headlineLarge)
                    Text(AppText.text(R.string.copy_34619cbd0e85, "Average across %1\$s recorded days", measured.size), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    MetricLineChart(points, null, height = 150.dp,
                        lineColor = listOf(colors.sleep, colors.bandNormal, colors.activity, colors.activity)[index],
                        windowStartMs = LocalDay.startOfDayMs(today - range + 1), windowEndMs = LocalDay.startOfDayMs(today + 1), decimals = if (index == 2) 1 else 0,
                        timeTicks = listOf(LocalDay.startOfDayMs(today - range + 1) to LocalDate.ofEpochDay(today - range + 1).format(DateTimeFormatter.ofPattern("d MMM")), LocalDay.startOfDayMs(today) to AppText.text(R.string.copy_2b065c7c9ce4, "Today")))
                }
            }
        }
    }
}

private fun time(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a"))
