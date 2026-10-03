package com.bipss.app.ui.planner

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.R
import com.bipss.app.data.SleepPlanState
import com.bipss.app.localization.AppText
import com.bipss.app.ui.activity.WellnessPage
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel

@Composable
fun PlannerScreen(nav: NavController, vm: PlannerViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val reminder by vm.reminder.collectAsStateWithLifecycle()
    var pickWake by rememberSaveable { mutableStateOf(false) }
    WellnessPage(AppText.text(R.string.copy_4b2783146693, "Sleep planner"), nav) {
        val s = state
        if (s != null) {
            PlanHero(s, onEditWake = { pickWake = true })
            NeedBreakdown(s)
            if (s.nights.isNotEmpty()) NightsCard(s)
            ReminderCard(reminder, vm::setReminder)
        }
    }
    val wake = state?.plan?.wakeMin
    if (pickWake && wake != null) WakeTimeDialog(wake, onDismiss = { pickWake = false }) { vm.setWakeTime(it); pickWake = false }
}

@Composable
private fun PlanHero(s: SleepPlanState, onEditWake: () -> Unit) {
    val colors = BipssTheme.colors
    BipssCard {
        Text(AppText.text(R.string.copy_8a7adbce0a68, "Tonight"), style = MaterialTheme.typography.titleMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Space.s))
        Text(AppText.text(R.string.copy_d19d5a9c659f, "Be in bed by %1\$s", clockText(s.plan.bedtimeMin)),
            style = MaterialTheme.typography.headlineMedium, color = colors.sleep)
        Spacer(Modifier.height(Space.xs))
        Text(AppText.text(R.string.copy_8e8da2b659d1, "For about %1\$s of sleep, to wake at %2\$s", durationText(s.plan.needMin), clockText(s.plan.wakeMin)),
            color = colors.textSecondary)
        TextButton(onClick = onEditWake, contentPadding = PaddingValues(0.dp)) {
            Text(AppText.text(R.string.copy_6a3cd190eb53, "Change wake time"))
        }
    }
}

@Composable
private fun NeedBreakdown(s: SleepPlanState) {
    val p = s.plan
    BipssCard {
        InfoRow(AppText.text(R.string.copy_b71a27b401e5, "Your usual need"), durationText(p.baselineNeedMin))
        if (p.strainExtraMin > 0) InfoRow(AppText.text(R.string.copy_1d06426d62af, "After a hard day"), "+" + durationText(p.strainExtraMin))
        if (p.debtExtraMin > 0) InfoRow(AppText.text(R.string.copy_f21c57b2f30c, "Catching up"), "+" + durationText(p.debtExtraMin))
        HairlineDivider()
        InfoRow(AppText.text(R.string.copy_1744c7dc0848, "Sleep debt, last 7 nights"),
            if (p.debtMin == 0) AppText.text(R.string.copy_dc937b598926, "None") else durationText(p.debtMin))
        s.consistency?.let { InfoRow(AppText.text(R.string.copy_b80b9aa43f54, "Sleep consistency"), "%.0f%%".format(it)) }
        if (p.baselineIsDefault) Text(
            AppText.text(R.string.copy_44f0989d1946, "Your usual need is a default of 8 hours until %1\$s nights are recorded.",
                com.bipss.app.domain.sleep.SleepPlanner.NIGHTS_FOR_BASELINE),
            style = MaterialTheme.typography.bodySmall, color = BipssTheme.colors.textTertiary)
    }
}
