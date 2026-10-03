package com.bipss.app.ui.journal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.R
import com.bipss.app.domain.journal.Habit
import com.bipss.app.domain.journal.HabitEffect
import com.bipss.app.domain.journal.JournalInsights
import com.bipss.app.localization.AppText
import com.bipss.app.ui.activity.WellnessPage
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel

@Composable
fun JournalScreen(nav: NavController, vm: JournalViewModel = koinViewModel()) {
    val back by vm.daysBack.collectAsStateWithLifecycle()
    val answers by vm.answers.collectAsStateWithLifecycle()
    val effects by vm.effects.collectAsStateWithLifecycle()
    WellnessPage(AppText.text(R.string.copy_ff64655deccc, "Daily journal"), nav) {
        SegmentedControl(listOf(0, 1), back, { if (it == 0) AppText.text(R.string.copy_2b065c7c9ce4, "Today") else AppText.text(R.string.copy_566181254b29, "Yesterday") }, vm::select)
        BipssCard {
            Habit.entries.forEachIndexed { i, h ->
                if (i > 0) HairlineDivider()
                HabitRow(h, answers[h]) { yes -> vm.answer(h, yes) }
            }
        }
        if (effects.any { it.yesDays + it.noDays > 0 }) {
            SectionHeader(AppText.text(R.string.copy_79884b819837, "What your mornings show"))
            BipssCard { effects.filter { it.yesDays + it.noDays > 0 }.forEachIndexed { i, e ->
                if (i > 0) HairlineDivider()
                EffectRow(e)
            } }
        }
    }
}

@Composable
private fun HabitRow(h: Habit, answer: Boolean?, onAnswer: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Text(questionText(h), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        FilterChip(selected = answer == true, onClick = { onAnswer(true) }, label = { Text(AppText.text(R.string.copy_85a39ab345d6, "Yes")) })
        Spacer(Modifier.width(Space.s))
        FilterChip(selected = answer == false, onClick = { onAnswer(false) }, label = { Text(AppText.text(R.string.copy_1ea442a134b2, "No")) })
    }
}

@Composable
private fun EffectRow(e: HabitEffect) {
    val colors = BipssTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = Space.m)) {
        Text(habitName(e.habit), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Space.xs))
        val d = e.difference
        if (e.reliable && d != null) {
            val points = kotlin.math.abs(d).toInt().coerceAtLeast(1)
            Text(if (d < 0) AppText.text(R.string.copy_ecdc5dfd7464, "Recovery averaged %1\$s points lower the next morning (%2\$s days with, %3\$s without)", points, e.yesDays, e.noDays)
                else AppText.text(R.string.copy_e1096e446b4b, "Recovery averaged %1\$s points higher the next morning (%2\$s days with, %3\$s without)", points, e.yesDays, e.noDays),
                style = MaterialTheme.typography.bodySmall, color = if (d < 0) colors.bandMonitor else colors.bandNormal)
        } else {
            Text(AppText.text(R.string.copy_30f08a7fdd25, "Keep logging: %1\$s days with, %2\$s without. It needs %3\$s of each.", e.yesDays, e.noDays, JournalInsights.MIN_DAYS_EACH),
                style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}
