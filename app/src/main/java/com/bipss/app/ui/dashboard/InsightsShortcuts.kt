package com.bipss.app.ui.dashboard

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import com.bipss.app.R
import com.bipss.app.localization.AppText
import com.bipss.app.navigation.Routes
import com.bipss.app.ui.components.BipssCard
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space

/** A titled one-line card that opens a screen: the Insights tab's shortcuts. */
@Composable
private fun ShortcutCard(title: String, subtitle: String, onClick: () -> Unit) {
    BipssCard(onClick = onClick) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Space.s))
        Text(subtitle, color = BipssTheme.colors.textSecondary)
    }
}

/** Cardio fitness and Sleep stages, as before, with the new screens alongside them. */
@Composable
fun InsightsShortcuts(nav: NavController) {
    ShortcutCard(AppText.text(R.string.copy_2026882df169, "Cardio fitness"),
        AppText.text(R.string.copy_ed27b587031a, "VO₂ max from your band")) { nav.navigate(Routes.FITNESS) }
    Spacer(Modifier.height(Space.m))
    ShortcutCard(AppText.text(R.string.copy_4b2783146693, "Sleep planner"),
        AppText.text(R.string.copy_f43b33271267, "Tonight's bedtime and your sleep debt")) { nav.navigate(Routes.SLEEP_PLANNER) }
    Spacer(Modifier.height(Space.m))
    ShortcutCard(AppText.text(R.string.copy_c60c9fe2060e, "Sleep stages"),
        AppText.text(R.string.copy_f537b4860901, "Explore your overnight patterns")) { nav.navigate(Routes.SLEEP_STAGES) }
    Spacer(Modifier.height(Space.m))
    ShortcutCard(AppText.text(R.string.copy_53f750c31972, "Weekly report"),
        AppText.text(R.string.copy_0d6444f26ca2, "How your week went")) { nav.navigate(Routes.WEEKLY_REPORT) }
    Spacer(Modifier.height(Space.m))
    ShortcutCard(AppText.text(R.string.copy_ff64655deccc, "Daily journal"),
        AppText.text(R.string.copy_949d3ed75329, "See what helps your recovery")) { nav.navigate(Routes.JOURNAL) }
}
