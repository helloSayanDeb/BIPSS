package com.bipss.app.ui.activity

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.data.MetricTypes
import com.bipss.app.data.db.AppDatabase
import com.bipss.app.domain.time.LocalDay
import com.bipss.app.ui.components.*
import com.bipss.app.ui.metric.*
import com.bipss.app.ui.theme.*
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable fun NutritionScreen(nav: NavController, vm: ActivityViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val foods = ui.data.foods.filter { LocalDay.epochDay(it.atMs) == ui.today }
    WellnessPage(AppText.text(R.string.copy_be80ef360690, "Food journal"), nav) {
        error?.let { Text(it, color = BipssTheme.colors.bandRisk) }
        if (!ui.data.nutritionEnabled) {
            BipssCard {
                Text(AppText.text(R.string.copy_1206d6b7625d, "A simple record of your meals"), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Space.s))
                Text(AppText.text(R.string.copy_c8183bddf77b, "Add what you eat and, if you know them, calories and protein from the label."), color = BipssTheme.colors.textSecondary)
                Spacer(Modifier.height(Space.l))
                PrimaryButton(AppText.text(R.string.copy_1a604b7548c9, "Enable food journal"), onClick = { vm.nutrition(true) })
            }
        } else {
            BipssCard {
                Text(AppText.text(R.string.copy_2b065c7c9ce4, "Today"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Space.s))
                Text(AppText.text(R.string.copy_3f0afe37be32, "%1\$s meals & snacks", foods.size), style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(Space.m))
                InfoRow(AppText.text(R.string.copy_a1b8cb94c575, "Calories logged"), foods.mapNotNull { it.calories }.takeIf { it.isNotEmpty() }?.sum()?.let { AppText.text(R.string.copy_84744cf2ee7e, "%1\$s kcal", it) } ?: "—")
                InfoRow(AppText.text(R.string.copy_74ed962d86e9, "Protein logged"), foods.mapNotNull { it.proteinG }.takeIf { it.isNotEmpty() }?.sum()?.let { "$it g" } ?: "—")
                Text(AppText.text(R.string.copy_ebe2e830c7f9, "Totals include only the amounts you enter."), style = MaterialTheme.typography.bodySmall, color = BipssTheme.colors.textSecondary)
            }
            PrimaryButton(AppText.text(R.string.copy_c289c0040db1, "+ Log food"), onClick = { showAdd = true })
            foods.asReversed().forEach { food ->
                BipssCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(food.name, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(Space.s))
                            Text(listOfNotNull(food.calories?.let { AppText.text(R.string.copy_84744cf2ee7e, "%1\$s kcal", it) }, food.proteinG?.let { AppText.text(R.string.copy_07e979c5a7c3, "%1\$s g protein", it) })
                                .joinToString(" · ").ifBlank { AppText.text(R.string.copy_843fde0b013d, "Food logged") }, style = MaterialTheme.typography.bodySmall, color = BipssTheme.colors.textSecondary)
                            Text(java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(food.atMs)), style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { vm.deleteFood(food.id) }) { Icon(Icons.Rounded.DeleteOutline, AppText.text(R.string.copy_6f9c75753444, "Delete %1\$s", food.name)) }
                    }
                }
            }
            TextButton(onClick = { vm.nutrition(false) }) { Text(AppText.text(R.string.copy_6fb444d8a6fa, "Hide food journal")) }
        }
    }
    if (showAdd) {
        var name by rememberSaveable { mutableStateOf("") }
        var calories by rememberSaveable { mutableStateOf("") }
        var protein by rememberSaveable { mutableStateOf("") }
        val valid = name.isNotBlank() && (calories.isBlank() || com.bipss.app.localization.LocalNumbers.intOrNull(calories) in 0..10000) && (protein.isBlank() || com.bipss.app.localization.LocalNumbers.intOrNull(protein) in 0..1000)
        AlertDialog(onDismissRequest = { showAdd = false }, title = { Text(AppText.text(R.string.copy_efaaeace4aab, "Log food")) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text(AppText.text(R.string.copy_700292c528ff, "Meal or food")) })
                OutlinedTextField(calories, { calories = it.filter(Char::isDigit).take(5) }, label = { Text(AppText.text(R.string.copy_f70c44d6b3b2, "Calories · optional")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(protein, { protein = it.filter(Char::isDigit).take(4) }, label = { Text(AppText.text(R.string.copy_0f9d1472f393, "Protein in g · optional")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }, confirmButton = { TextButton(onClick = { vm.food(name.trim(), com.bipss.app.localization.LocalNumbers.intOrNull(calories), com.bipss.app.localization.LocalNumbers.intOrNull(protein)); showAdd = false }, enabled = valid) { Text(AppText.text(R.string.copy_1509f561f241, "Save")) } },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text(AppText.text(R.string.copy_19766ed6ccb2, "Cancel")) } })
    }
}

@Composable fun WorkoutScreen(id: String?, nav: NavController, vm: ActivityViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val db = koinInject<AppDatabase>()
    val recorded = ui.data.activities.firstOrNull { it.id == id }
    val active = if (id == null) ui.data.activeWorkout else null
    var type by rememberSaveable { mutableStateOf("Walk") }
    val now by produceState(System.currentTimeMillis(), active) {
        while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    val start = active?.startMs ?: recorded?.let { it.startMs ?: (it.atMs - it.minutes * 60_000L) }
    val end = recorded?.atMs ?: now
    val buckets by produceState(emptyList<com.bipss.app.data.db.dao.MetricBucket>(), start, recorded?.id) {
        if (start != null) do {
            val until = recorded?.atMs ?: System.currentTimeMillis()
            val rows = mergeBuckets(db.rawReadingDao().getBuckets(MetricTypes.HR, start, until, 30_000),
                db.minuteAggregateDao().getBuckets(MetricTypes.HR, start / 60_000, until / 60_000, 30_000))
            value = rows
            if (recorded != null) break
            delay(10_000)
        } while (true)
    }
    WellnessPage(recorded?.name?.let { AppText.label(it) } ?: AppText.text(R.string.copy_7b3abf24fddd, "Workout"), nav) {
        error?.let { Text(it, color = BipssTheme.colors.bandRisk) }
        if (id != null && recorded == null) Text(AppText.text(R.string.copy_8c0e8891607a, "This activity is no longer in your log."))
        else if (start == null) {
            Text(AppText.text(R.string.copy_bd97a2841595, "Make time to move"), style = MaterialTheme.typography.headlineMedium)
            Text(AppText.text(R.string.copy_60ef5cef46f2, "Choose an activity. Wear your band to see heart rate during the session."), color = BipssTheme.colors.textSecondary)
            ActivityTypeChooser(type) { type = it }
            PrimaryButton(AppText.text(R.string.copy_ecd6a6daf701, "Start %1\$s", AppText.label(type)), onClick = { vm.startWorkout(type) })
        } else {
            val seconds = ((end - start).coerceAtLeast(0L) / 1000)
            BipssCard {
                Text(AppText.label(active?.name ?: recorded!!.name), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Space.s))
                Text("%02d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60), style = MaterialTheme.typography.displayMedium, color = BipssTheme.colors.activity)
                Text(if (active != null) AppText.text(R.string.copy_c1f88e9d6c41, "In progress") else AppText.text(R.string.copy_22a970d2e5b1, "Completed"), style = MaterialTheme.typography.bodyMedium, color = BipssTheme.colors.textSecondary)
            }
            BipssCard {
                Text(AppText.text(R.string.copy_1447897ca9f5, "Heart rate"), style = MaterialTheme.typography.titleMedium)
                val stats = combineStats(buckets)
                val trace = insertGaps(bucketsToPoints(buckets, 30_000), 90_000)
                if (stats.sampleCount == 0) Text(AppText.text(R.string.copy_ae3ee67225e8, "No band heart-rate readings for this session yet."), Modifier.padding(top = Space.s), color = BipssTheme.colors.textSecondary)
                else {
                    MetricLineChart(trace, null, lineColor = BipssTheme.colors.heart, windowStartMs = start, windowEndMs = maxOf(start + 60_000, end), decimals = 0)
                    InfoRow(AppText.text(R.string.copy_b38ae2c5b845, "Average"), AppText.text(R.string.copy_d62366697763, "%.0f bpm").format(stats.avgValue))
                    InfoRow(AppText.text(R.string.copy_a57feec7a920, "Peak"), AppText.text(R.string.copy_d62366697763, "%.0f bpm").format(stats.maxValue))
                }
            }
            if (active != null) {
                WorkoutGps(active.name)
                PrimaryButton(AppText.text(R.string.copy_b2e6505a803a, "Finish & save"), onClick = { vm.finishWorkout(); nav.popBackStack() })
                TextButton(onClick = { vm.cancelWorkout(); nav.popBackStack() }) { Text(AppText.text(R.string.copy_228bfd767a78, "Discard session")) }
            } else recorded?.note?.takeIf { it.isNotBlank() }?.let { BipssCard { Text(it) } }
        }
    }
}
