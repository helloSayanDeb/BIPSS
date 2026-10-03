package com.bipss.app.ui.sleep

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.data.*
import com.bipss.app.ui.activity.WellnessPage
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SleepStagesScreen(nav: NavController, repo: SleepStageRepository = koinInject(), sleep: SleepRepository = koinInject()) {
    val enabled by repo.enabled.collectAsStateWithLifecycle(false)
    val records by repo.records.collectAsStateWithLifecycle(emptyList())
    var selectedStart by rememberSaveable { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var details by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val colors = BipssTheme.colors
    val selected = records.find { it.startMs == selectedStart } ?: records.lastOrNull()
    LaunchedEffect(records.lastOrNull()?.createdMs) { if (records.isNotEmpty()) message = null }
    val date = remember { SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()) }
    WellnessPage(AppText.text(R.string.copy_c60c9fe2060e, "Sleep stages"), nav) {
        BipssCard {
            Text(AppText.text(R.string.copy_8c9107f9972e, "Explore your sleep"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Space.s))
            Text(AppText.text(R.string.copy_8d6818e8ea59, "Estimated Wake, Light, Deep and REM. Experimental: accuracy on your band is not yet validated."), color = colors.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(AppText.text(R.string.copy_acf287aed9e2, "Record for sleep estimates"), Modifier.weight(1f).padding(top = Space.m))
                Switch(checked = enabled, enabled = !busy, modifier = Modifier.semantics { contentDescription = AppText.text(R.string.copy_acf287aed9e2, "Record for sleep estimates") },
                    onCheckedChange = { value -> scope.launch { repo.setEnabled(value) } })
            }
            Text(AppText.text(R.string.copy_3f236e710422, "Keep your band connected overnight. Pulse recordings stay on this phone for up to 72 hours."), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        PrimaryButton(if (busy) AppText.text(R.string.copy_095f29344cae, "Analysing…") else AppText.text(R.string.copy_9f5367a9d5af, "Analyse latest night"), enabled = enabled && !busy, onClick = {
            scope.launch {
                busy = true; message = null
                try {
                    val night = sleep.lastNight()
                    message = if (night == null) AppText.text(R.string.copy_96c1f17a7061, "No completed night yet. Wear your band overnight and check back after your sleep summary appears.")
                        else repo.analyse(night.startMs, night.endMs)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = AppText.label("Couldn't analyse this night. Your recording is kept so you can try again.") }
                finally { busy = false }
            }
        })
        message?.let { Text(AppText.display(it), color = colors.textSecondary) }
        selected?.let { night ->
            SectionHeader(date.format(Date(night.startMs)))
            BipssCard {
                Text(AppText.label("Estimated stages"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Space.m))
                val labels = listOf(AppText.label("Wake"), AppText.label("REM"), AppText.label("Light"), AppText.label("Deep"), AppText.label("Unknown"))
                val palette = listOf(colors.activity, colors.sleep, colors.bandNormal, colors.sleep.copy(alpha = .55f), colors.textTertiary)
                val rowForStage = mapOf(0 to 0, 3 to 1, 1 to 2, 2 to 3, -1 to 4)
                Canvas(Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = AppText.label("Sleep stage timeline. Unknown sections have insufficient signal or model confidence.") }) {
                    val dx = size.width / night.stages.size.coerceAtLeast(1)
                    var i = 0
                    while (i < night.stages.size) {
                        val stage = night.stages[i]
                        var end = i + 1
                        while (end < night.stages.size && night.stages[end] == stage) end++
                        val row = rowForStage[stage] ?: 4
                        drawRect(palette[row], Offset(i * dx, row * size.height / 5), Size(((end - i) * dx).coerceAtLeast(1f), size.height / 5 - 3.dp.toPx()))
                        i = end
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val clock = SimpleDateFormat("h:mm a", Locale.getDefault())
                    Text(clock.format(Date(night.startMs)), style = MaterialTheme.typography.bodySmall)
                    Text(clock.format(Date(night.startMs + night.stages.size * 30_000L)), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(Space.m))
                labels.forEachIndexed { row, label ->
                    val stage = rowForStage.entries.first { it.value == row }.key
                    val minutes = night.stages.count { it == stage } / 2.0
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, color = palette[row])
                        Text(AppText.text(R.string.copy_3e16be5f4e1d, "%1\$s min", "%.1f".format(minutes)))
                    }
                }
                Text(AppText.text(R.string.copy_68feca8d26ef, "%1\$s% usable pulse coverage", night.capturedPercent), color = colors.textSecondary)
            }
        }
        if (records.size > 1) {
            SectionHeader(AppText.label("Previous nights"))
            records.asReversed().forEach { night -> TextButton(onClick = { selectedStart = night.startMs }) { Text(date.format(Date(night.startMs))) } }
        }
        TextButton(onClick = { details = !details }) { Text(if (details) AppText.label("Hide method") else AppText.label("About these estimates")) }
        if (details) BipssCard {
            Text(AppText.label("The wav2sleep model analyses the shape of your pulse across a completed night. It was trained on sleep-study recordings, not this wristband. Poor signal and uncertain predictions appear as Unknown. Movement, fit and sensor differences may affect results. These stages are for exploration, not diagnosis, and do not change your sleep score."), color = colors.textSecondary)
            TextButton(onClick = { uri.openUri("https://github.com/joncarter1/wav2sleep") }) { Text(AppText.label("Model and research")) }
        }
        TextButton(enabled = !busy, onClick = { scope.launch { repo.clear(); message = AppText.label("Sleep-stage recordings and estimates cleared.") } }) { Text(AppText.label("Turn off and clear stage data")) }
    }
}
