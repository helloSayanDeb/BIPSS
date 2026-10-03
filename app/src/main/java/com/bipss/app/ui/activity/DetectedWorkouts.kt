package com.bipss.app.ui.activity

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.R
import com.bipss.app.data.WorkoutRepository
import com.bipss.app.data.db.entity.DetectedWorkoutEntity
import com.bipss.app.localization.AppText
import com.bipss.app.navigation.Routes
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun summary(w: DetectedWorkoutEntity) = AppText.text(R.string.copy_afd53b076df4, "%1\$s min · average %2\$s bpm · peak %3\$s bpm",
    w.minutes, w.meanHr.toInt(), w.maxHr.toInt())

private fun whenText(w: DetectedWorkoutEntity) =
    SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(w.startMs))

/** Efforts the band found, to name or dismiss; the ones already named stay listed. */
@Composable
fun DetectedWorkoutsSection(repo: WorkoutRepository = koinInject()) {
    val recent by repo.recent(10).collectAsStateWithLifecycle(emptyList())
    if (recent.isEmpty()) return
    SectionHeader(AppText.text(R.string.copy_db29b8e71b0a, "Found by your band"))
    recent.forEach { w -> DetectedWorkoutCard(w, repo) }
}

@Composable
private fun DetectedWorkoutCard(w: DetectedWorkoutEntity, repo: WorkoutRepository) {
    val colors = BipssTheme.colors
    val scope = rememberCoroutineScope()
    var type by rememberSaveable(w.id) { mutableStateOf(w.type) }
    BipssCard {
        if (w.status == "NEW") {
            Text(AppText.text(R.string.copy_a8d5b13cc704, "Workout detected"), style = MaterialTheme.typography.titleMedium)
        } else {
            Text(AppText.text(R.string.copy_a1572e58740c, "%1\$s · %2\$s min", AppText.label(w.type), w.minutes), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(Space.xs))
        Text(whenText(w), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Text(summary(w), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        if (w.status == "NEW") {
            Spacer(Modifier.height(Space.m))
            ActivityTypeChooser(type) { type = it }
            Spacer(Modifier.height(Space.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                PrimaryButton(AppText.text(R.string.copy_1509f561f241, "Save"), onClick = { scope.launch { repo.confirm(w.id, type) } }, modifier = Modifier.weight(1f))
                SecondaryButton(AppText.text(R.string.copy_dbfc844903ff, "Not a workout"), onClick = { scope.launch { repo.dismiss(w.id) } }, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A line on Home when the band has found a workout nobody has looked at yet. */
@Composable
fun DetectedWorkoutBanner(nav: NavController, repo: WorkoutRepository = koinInject()) {
    val pending by repo.pending.collectAsStateWithLifecycle(emptyList())
    val latest = pending.firstOrNull() ?: return
    BipssCard(onClick = { nav.navigate(Routes.ACTIVITY_DETAIL) }) {
        Text(AppText.text(R.string.copy_a8d5b13cc704, "Workout detected"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Space.xs))
        Text(AppText.text(R.string.copy_9fc3a32254b7, "%1\$s min · average %2\$s bpm. Tap to name it.", latest.minutes, latest.meanHr.toInt()),
            color = BipssTheme.colors.textSecondary)
    }
}
