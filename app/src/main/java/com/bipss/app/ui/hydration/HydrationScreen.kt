package com.bipss.app.ui.hydration

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.ui.components.BipssCard
import com.bipss.app.ui.components.QuickLogCard
import com.bipss.app.ui.components.SectionLabel
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HydrationQuickCard(onOpen: () -> Unit, vm: HydrationViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    QuickLogCard(
        icon = Icons.Rounded.WaterDrop, tint = BipssTheme.colors.spo2, title = AppText.text(R.string.copy_1d9605ff4a17, "Hydration"),
        subtitle = if (!ui.loaded) AppText.text(R.string.copy_4a355e9497fa, "Reading your log…") else AppText.text(R.string.copy_57515703882a, "%1\$s mL logged today", ui.totalMl) +
            (ui.goalMl?.let { AppText.text(R.string.copy_7a816ec84b06, " · %1\$s mL goal", it) } ?: ""),
        onOpen = onOpen,
        footnote = error ?: AppText.text(R.string.copy_f0e33726daf5, "− corrects today's log. Tap Hydration for your goal and history.")
    ) {
        Column(Modifier.fillMaxWidth()) {
            ui.goalMl?.let { goal ->
                LinearProgressIndicator(progress = { (ui.totalMl.toFloat() / goal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(), color = BipssTheme.colors.spo2, drawStopIndicator = {})
                Spacer(Modifier.height(Space.m))
            }
            WaterStepper(ui.loaded, ui.totalMl > 0, vm::subtract, vm::add)
        }
    }
}

@Composable
private fun WaterStepper(loaded: Boolean, canSubtract: Boolean, subtract: () -> Unit, add: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        FilledTonalIconButton(onClick = subtract, enabled = loaded && canSubtract, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Rounded.Remove, contentDescription = AppText.text(R.string.copy_0e4bf014d172, "Subtract 250 mL from today's log"))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(AppText.text(R.string.copy_bf2986677d36, "250 mL"), style = MaterialTheme.typography.titleLarge, color = BipssTheme.colors.textPrimary)
            Text(AppText.text(R.string.copy_1657d472e4f3, "per tap"), style = MaterialTheme.typography.bodySmall, color = BipssTheme.colors.textSecondary)
        }
        FilledTonalIconButton(onClick = add, enabled = loaded, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Rounded.Add, contentDescription = AppText.text(R.string.copy_162f280f3a35, "Log 250 mL of water"))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HydrationDetailScreen(navController: NavController, vm: HydrationViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors
    var editGoal by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text(AppText.text(R.string.copy_1d9605ff4a17, "Hydration")) }, navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, AppText.text(R.string.copy_76900f1bfd16, "Back"))
            }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
            .padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.l)) {
            Spacer(Modifier.height(0.dp))
            BipssCard {
                Text(AppText.text(R.string.copy_2b065c7c9ce4, "Today"), style = MaterialTheme.typography.titleMedium, color = colors.textSecondary)
                Spacer(Modifier.height(Space.s))
                Text(if (ui.loaded) AppText.text(R.string.copy_e52749128c70, "%1\$s mL", ui.totalMl) else "—", style = MaterialTheme.typography.headlineLarge, color = colors.spo2)
                Spacer(Modifier.height(Space.s))
                ui.goalMl?.let { goal ->
                    Text(AppText.text(R.string.copy_ca6fd945696a, "Logged toward your %1\$s mL goal", goal), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                    Spacer(Modifier.height(Space.m))
                    LinearProgressIndicator(progress = { (ui.totalMl.toFloat() / goal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(), color = colors.spo2, drawStopIndicator = {})
                }
                TextButton(onClick = { editGoal = true }, enabled = ui.loaded) {
                    Text(if (ui.goalMl == null) AppText.text(R.string.copy_ecf5423f8a3b, "Set daily goal") else AppText.text(R.string.copy_9ffdfca0f6aa, "Change daily goal"))
                }
                Text(if (ui.suggestedGoal) AppText.text(R.string.copy_081ff23e0925, "A starting goal based on your weight. All drinks count; food provides fluid too. Adjust it to suit you and follow any prescribed fluid limit.")
                    else AppText.text(R.string.copy_69561f6900bb, "Your daily drinks target. Follow any fluid limit your clinician has given you."),
                    style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                Spacer(Modifier.height(Space.l))
                WaterStepper(ui.loaded, ui.totalMl > 0, vm::subtract, vm::add)
                Spacer(Modifier.height(Space.m))
                Text(AppText.text(R.string.copy_a846785ede94, "− removes up to 250 mL from today's most recent drinks."),
                    style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                error?.let { Text(it, color = colors.bandRisk, modifier = Modifier.padding(top = Space.s)) }
            }
            BipssCard {
                SectionLabel(AppText.text(R.string.copy_0603deca4fcb, "Last 7 days"))
                Spacer(Modifier.height(Space.m))
                val scale = maxOf(ui.history.maxOfOrNull { it.totalMl } ?: 0, 250)
                ui.history.forEach { day ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Text(if (day.epochDay == ui.today) AppText.text(R.string.copy_2b065c7c9ce4, "Today") else LocalDate.ofEpochDay(day.epochDay).format(DateTimeFormatter.ofPattern("EEE d")),
                            modifier = Modifier.width(66.dp), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        LinearProgressIndicator(progress = { day.totalMl.toFloat() / scale }, modifier = Modifier.weight(1f), color = colors.spo2, drawStopIndicator = {})
                        Text(AppText.text(R.string.copy_e52749128c70, "%1\$s mL", day.totalMl), modifier = Modifier.width(70.dp), style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
                    }
                }
                Spacer(Modifier.height(Space.s))
                Text(AppText.text(R.string.copy_ae7d0f94565a, "Logged water only. An empty day doesn't mean you drank nothing."),
                    style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
            }
            BipssCard {
                SectionLabel(AppText.text(R.string.copy_17db6b779796, "Today's drinks"))
                Spacer(Modifier.height(Space.m))
                if (ui.entries.isEmpty()) Text(if (ui.loaded) AppText.text(R.string.copy_5a655eb70e29, "No drinks logged today. Tap + to add 250 mL.") else AppText.text(R.string.copy_4a355e9497fa, "Reading your log…"),
                    style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                ui.entries.forEach { entry ->
                    val time = Instant.ofEpochMilli(entry.timestampMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a"))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = Space.s)) {
                            Text(AppText.text(R.string.copy_e52749128c70, "%1\$s mL", entry.amountMl), style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                            Spacer(Modifier.height(4.dp))
                            Text(time, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                        IconButton(onClick = { vm.remove(entry.id) }) {
                            Icon(Icons.Rounded.DeleteOutline, AppText.text(R.string.copy_9815571efcb6, "Remove %1\$s mL logged at %2\$s", entry.amountMl, time), tint = colors.textSecondary)
                        }
                    }
                }
            }
            Text(AppText.text(R.string.copy_39295d615c01, "Your log records drinks you add. The band doesn't measure hydration."),
                style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
            Spacer(Modifier.height(Space.m))
        }
    }
    if (editGoal) GoalDialog(ui.goalMl, onDismiss = { editGoal = false }, onSave = { vm.setGoal(it); editGoal = false })
}

@Composable
private fun GoalDialog(current: Int?, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var input by rememberSaveable { mutableStateOf(current?.toString().orEmpty()) }
    val value = com.bipss.app.localization.LocalNumbers.intOrNull(input)
    val valid = value != null && value in 250..5000
    AlertDialog(onDismissRequest = onDismiss, title = { Text(AppText.text(R.string.copy_065e92aeb01d, "Daily logging goal")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(AppText.text(R.string.copy_0b1691535866, "Choose your own target in mL. This is not a medical recommendation."))
            OutlinedTextField(value = input, onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) input = it },
                label = { Text(AppText.text(R.string.copy_2c9d40f30546, "Goal in mL")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = input.isNotEmpty() && !valid, supportingText = { Text(AppText.text(R.string.copy_1582a172fa61, "250–5000 mL")) })
        }
    }, confirmButton = { TextButton(onClick = { value?.let(onSave) }, enabled = valid) { Text(AppText.text(R.string.copy_88b76654519f, "Save goal")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppText.text(R.string.copy_19766ed6ccb2, "Cancel")) } })
}
