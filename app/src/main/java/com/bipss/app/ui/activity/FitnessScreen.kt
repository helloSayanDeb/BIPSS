package com.bipss.app.ui.activity

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.R
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.Vo2Repository
import com.bipss.app.data.toBioSex
import com.bipss.app.domain.bioage.BioAge
import com.bipss.app.domain.time.LocalDay
import com.bipss.app.localization.AppText
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import org.koin.compose.koinInject

/**
 * Cardio fitness: the VO2 max the band has worked out. Nothing to fill in -
 * it comes from measured activity, sleeping heart rate and steady walks.
 */
@Composable
fun FitnessScreen(nav: NavController, repo: Vo2Repository = koinInject(),
    prefs: UserPreferencesRepository = koinInject()) {
    val colors = BipssTheme.colors
    val today = remember { LocalDay.epochDay(System.currentTimeMillis()) }
    val history by repo.since(today - 90).collectAsStateWithLifecycle(emptyList())
    val profile by prefs.userProfile.collectAsStateWithLifecycle(null)
    val latest = history.lastOrNull()
    WellnessPage(AppText.text(R.string.copy_2026882df169, "Cardio fitness"), nav) {
        if (latest == null) {
            BipssCard {
                Text(AppText.text(R.string.copy_541897773a44, "VO₂ max"), style = MaterialTheme.typography.titleMedium, color = colors.textSecondary)
                Spacer(Modifier.height(Space.s))
                Text(AppText.text(R.string.copy_361d092fdb15, "Wear your band day and night for a week to get your first estimate."),
                    color = colors.textSecondary)
            }
        } else {
            BipssCard {
                Text(AppText.text(R.string.copy_541897773a44, "VO₂ max"), style = MaterialTheme.typography.titleMedium, color = colors.textSecondary)
                Spacer(Modifier.height(Space.s))
                Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                    Text("%.1f".format(latest.value), style = MaterialTheme.typography.displayLarge, color = colors.activity)
                    Spacer(Modifier.width(Space.s))
                    Text("± %.0f".format(latest.sigma.coerceAtLeast(1.0)), style = MaterialTheme.typography.titleMedium,
                        color = colors.textSecondary, modifier = Modifier.padding(bottom = Space.s))
                }
                Text(AppText.text(R.string.copy_1d1e884c6fc3, "mL/kg/min"), color = colors.textSecondary)
                profile?.let { p ->
                    val target = BioAge.vo2Target(p.age, p.gender.toBioSex())
                    Spacer(Modifier.height(Space.m))
                    val above = latest.value >= target
                    StatusPill(
                        if (above) AppText.text(R.string.copy_344bfafb0aa9, "At or above the %1\$s target for your age", "%.0f".format(target))
                        else AppText.text(R.string.copy_6b3fa23da088, "Below the %1\$s target for your age", "%.0f".format(target)),
                        if (above) colors.bandNormal else colors.bandMonitor
                    )
                }
            }
            if (history.size >= 2) BipssCard {
                Text(AppText.text(R.string.copy_c287ac938c17, "Recent trend"), style = MaterialTheme.typography.titleMedium, color = colors.textSecondary)
                Spacer(Modifier.height(Space.m))
                Sparkline(history.map { it.value.toFloat() }, colors.activity, Modifier.fillMaxWidth().height(72.dp))
            }
            BipssCard {
                InfoRow(AppText.text(R.string.copy_ee3bba5a6362, "From daily activity and resting heart rate"),
                    latest.passive?.let { "%.1f".format(it) } ?: "—")
                val walks = history.sumOf { it.activeSegments }
                InfoRow(AppText.text(R.string.copy_eeabb1182bc3, "Steady walks and runs counted"), walks.toString())
                latest.active?.let { InfoRow(AppText.text(R.string.copy_01d2bd841437, "From those walks and runs"), "%.1f".format(it)) }
            }
            Text(AppText.text(R.string.copy_2efbd2bcc7da, "A steady 10-minute brisk walk or run sharpens it."),
                style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
        }
    }
}
