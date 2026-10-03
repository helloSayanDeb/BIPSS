package com.bipss.app.ui.activity

import com.bipss.app.localization.AppText
import com.bipss.app.R

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.domain.DailyWellness
import com.bipss.app.navigation.Routes
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.*
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WellnessPage(title: String, nav: NavController, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = {
        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, AppText.text(R.string.copy_76900f1bfd16, "Back")) }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Space.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.l), content = content)
    }
}

@Composable
fun ActivityScreen(nav: NavController, vm: ActivityViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = BipssTheme.colors
    var showLog by rememberSaveable { mutableStateOf(false) }
    var showGoal by rememberSaveable { mutableStateOf(false) }
    WellnessPage(AppText.text(R.string.copy_38da1505ca83, "Activity"), nav) {
        BipssCard {
            Text(AppText.text(R.string.copy_7fb1f033938a, "Today's steps"), style = MaterialTheme.typography.titleMedium, color = colors.textSecondary)
            Spacer(Modifier.height(Space.s))
            Text(ui.todaySteps?.let { "%,d".format(it) } ?: "—", style = MaterialTheme.typography.displayMedium, color = colors.activity)
            Text(AppText.text(R.string.copy_7a56eacd87be, "of %1\$s steps", ui.data.goal), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            Spacer(Modifier.height(Space.l))
            LinearProgressIndicator(progress = { ((ui.todaySteps ?: 0).toFloat() / ui.data.goal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = colors.activity)
            TextButton(onClick = { showGoal = true }) { Text(AppText.text(R.string.copy_a0b9366b439c, "Change goal")) }
            Text(if (ui.data.bandStepAnchor == null) AppText.text(R.string.copy_f7f6a1632dcb, "Connect your band with step tracking firmware to see your steps.")
                else AppText.text(R.string.copy_15ef04de723f, "Steps from your band. Last received ") + java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault())
                    .format(java.util.Date(ui.data.bandStepAnchor!!.receivedMs)),
                style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            Text(AppText.text(R.string.copy_ae63219e55a8, "Wrist movement can affect the count."), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
        }
        BipssCard {
            SectionLabel(AppText.text(R.string.copy_0603deca4fcb, "Last 7 days"))
            Spacer(Modifier.height(Space.m))
            for (day in (ui.today - 6)..ui.today) {
                val count = ui.data.bandSteps[day]
                Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (day == ui.today) AppText.text(R.string.copy_2b065c7c9ce4, "Today") else LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("EEE")), Modifier.width(50.dp), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { ((count ?: 0).toFloat() / maxOf(ui.data.goal, ui.data.bandSteps.values.maxOrNull() ?: 0)).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f), color = colors.activity)
                    Text(count?.toString() ?: "—", Modifier.width(60.dp).padding(start = Space.s), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        DetectedWorkoutsSection()
        SectionHeader(AppText.text(R.string.copy_3bf62105cc84, "Your activities"), trailing = { TextButton(onClick = { showLog = true }) { Text(AppText.text(R.string.copy_85c0ee8deb5b, "+ Log")) } })
        PrimaryButton(if (ui.data.activeWorkout != null) AppText.text(R.string.copy_fdb1bbe86cdf, "Return to workout") else AppText.text(R.string.copy_dc117fe6ea28, "Start a workout"), onClick = { nav.navigate(Routes.WORKOUT_LIVE) })
        Text(AppText.text(R.string.copy_5e959a982fac, "%1\$s minutes logged in the last 7 days", ui.weekMinutes), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        if (ui.data.activities.isEmpty()) BipssCard { Text(AppText.text(R.string.copy_387d46a31655, "Walk, cycle, stretch or work out. Keep a record of what you do."), color = colors.textSecondary) }
        ui.data.activities.asReversed().take(30).forEach { entry ->
            BipssCard(onClick = { nav.navigate(Routes.workout(entry.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(AppText.text(R.string.copy_a1572e58740c, "%1\$s · %2\$s min", AppText.label(entry.name), entry.minutes), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(Space.s))
                        Text(java.text.SimpleDateFormat("EEE d MMM, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(entry.atMs)), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        if (entry.note.isNotBlank()) Text(entry.note, Modifier.padding(top = Space.s), style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = { vm.delete(entry.id) }) { Icon(Icons.Rounded.DeleteOutline, AppText.text(R.string.copy_6f9c75753444, "Delete %1\$s", AppText.label(entry.name))) }
                }
            }
        }
        error?.let { Text(it, color = colors.bandRisk) }
    }
    if (showGoal) NumberDialog(AppText.text(R.string.copy_c0c05b15a42c, "Daily step goal"), ui.data.goal, 1000..30000, "steps", { showGoal = false }) { vm.goal(it); showGoal = false }
    if (showLog) ActivityDialog({ showLog = false }) { name, minutes, note -> vm.log(name, minutes, note); showLog = false }
}

@Composable
private fun ActivityDialog(onDismiss: () -> Unit, onSave: (String, Int, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("Walk") }
    var minutes by rememberSaveable { mutableStateOf("30") }
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(AppText.text(R.string.copy_28d5cede2df6, "Log an activity")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            ActivityTypeChooser(name) { name = it }
            OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(3) }, label = { Text(AppText.text(R.string.copy_4f846a84e7fc, "Minutes")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(note, { note = it.take(300) }, label = { Text(AppText.text(R.string.copy_6239b93e96e9, "Notes (optional)")) }, maxLines = 3)
        }
    }, confirmButton = { TextButton(onClick = { onSave(name, com.bipss.app.localization.LocalNumbers.intOrNull(minutes)!!, note) }, enabled = com.bipss.app.localization.LocalNumbers.intOrNull(minutes) in 1..600) { Text(AppText.text(R.string.copy_4dd5cbfb6eec, "Save activity")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppText.text(R.string.copy_19766ed6ccb2, "Cancel")) } })
}

@Composable
private fun NumberDialog(title: String, initial: Int, range: IntRange, unit: String, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var input by rememberSaveable { mutableStateOf(initial.toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(input, { input = it.filter(Char::isDigit).take(5) }, label = { Text(unit) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), supportingText = { Text("${range.first}–${range.last}") })
    }, confirmButton = { TextButton(onClick = { onSave(com.bipss.app.localization.LocalNumbers.intOrNull(input)!!) }, enabled = com.bipss.app.localization.LocalNumbers.intOrNull(input) in range) { Text(AppText.text(R.string.copy_1509f561f241, "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppText.text(R.string.copy_19766ed6ccb2, "Cancel")) } })
}

@Composable
fun BodyScreen(nav: NavController, vm: ActivityViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val profile = ui.profile
    WellnessPage(AppText.text(R.string.copy_7826b2bdbdc1, "Body measurements"), nav) {
        BipssCard {
            val bmi = profile?.let { DailyWellness.bmi(it.heightCm, it.weightKg, it.age) }
            Text(AppText.text(R.string.copy_f5bf4f7b6bd9, "Body mass index"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.s))
            Text(bmi?.let { "%.1f".format(it) } ?: "—", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(Space.s))
            Text(if (bmi == null) AppText.text(R.string.copy_6f91d827f359, "Add your height, weight and age to see adult BMI (ages 20+).") else AppText.text(R.string.copy_c822b5846f69, "BMI relates your weight to your height. It doesn't measure body fat or overall fitness."), style = MaterialTheme.typography.bodyMedium, color = BipssTheme.colors.textSecondary)
            Spacer(Modifier.height(Space.l))
            InfoRow(AppText.text(R.string.copy_bfe2eca40179, "Height"), profile?.heightCm?.let { AppText.text(R.string.copy_d17db548b175, "%.0f cm").format(it) } ?: "—")
            InfoRow(AppText.text(R.string.copy_81d27ef6d503, "Weight"), profile?.weightKg?.let { AppText.text(R.string.copy_9007d1e37ece, "%.1f kg").format(it) } ?: "—")
            TextButton(onClick = { nav.navigate(Routes.ONBOARDING_PROFILE_EDIT) }) { Text(AppText.text(R.string.copy_56d4c3830da4, "Update measurements")) }
        }
        BipssCard(onClick = { nav.navigate(Routes.HYDRATION_DETAIL) }) {
            Text(AppText.text(R.string.copy_b29a9c9c0544, "Your hydration goal"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.s))
            Text(AppText.text(R.string.copy_ef5a3d044b11, "Use a starting goal based on your weight, or choose your own daily target."), style = MaterialTheme.typography.bodyMedium, color = BipssTheme.colors.textSecondary)
        }
    }
}

@Composable
fun BreathingScreen(nav: NavController) {
    var running by rememberSaveable { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        if (running) {
            val start = android.os.SystemClock.elapsedRealtime()
            while (elapsed < 60_000L) {
                withFrameNanos { elapsed = (android.os.SystemClock.elapsedRealtime() - start).coerceAtMost(60_000L) }
            }
            running = false
        }
    }
    val phase = elapsed % 10_000L
    WellnessPage(AppText.text(R.string.copy_f2e47e95bb9f, "Take a breathing break"), nav) {
        Text(AppText.text(R.string.copy_21c48b924bcd, "One quiet minute. Sit comfortably and breathe gently, without holding your breath."), style = MaterialTheme.typography.bodyLarge)
        BipssCard {
            Box(Modifier.fillMaxWidth().height(270.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { if (phase < 4000) phase / 4000f else 1f - (phase - 4000) / 6000f },
                    modifier = Modifier.size(210.dp), strokeWidth = 9.dp, color = BipssTheme.colors.sleep)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (!running) { if (elapsed == 60_000L) AppText.text(R.string.copy_bd4c2cbfd010, "Well done") else AppText.text(R.string.copy_c75befc64765, "Ready?") } else if (phase < 4000) AppText.text(R.string.copy_75b777957631, "Breathe in") else AppText.text(R.string.copy_2be379864e7d, "Breathe out"), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(Space.s))
                    Text(if (running) AppText.text(R.string.copy_7dab52618509, "%1\$ss remaining", (60_000L - elapsed + 999) / 1000) else AppText.text(R.string.copy_48b07a67a5d8, "At your own pace"), style = MaterialTheme.typography.bodySmall)
                }
            }
            PrimaryButton(if (running) AppText.text(R.string.copy_cae7d57bc067, "Stop") else AppText.text(R.string.copy_48e8cf5804bd, "Start 1 minute"), onClick = { if (running) running = false else { elapsed = 0; running = true } })
        }
        Text(AppText.text(R.string.copy_93bd697282af, "Return to your normal breathing if you feel uncomfortable or dizzy."), style = MaterialTheme.typography.bodySmall, color = BipssTheme.colors.textSecondary)
    }
}
