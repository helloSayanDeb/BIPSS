package com.bipss.app.ui.bioage

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.R
import com.bipss.app.domain.bioage.*
import com.bipss.app.localization.AppText
import com.bipss.app.ui.activity.WellnessPage
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel

@Composable
fun BioAgeScreen(nav: NavController, vm: BioAgeViewModel = koinViewModel()) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    var leanDialog by rememberSaveable { mutableStateOf(false) }
    WellnessPage(AppText.text(R.string.copy_ed172d683572, "BIPSS BioAge"), nav) {
        val s = snapshot
        val result = s?.result
        if (s != null && result == null) {
            BipssCard {
                Text(AppText.text(R.string.copy_d61e90db8a9f, "Add your age in your profile to see BioAge."),
                    color = BipssTheme.colors.textSecondary)
            }
        } else if (s != null && result != null) {
            BioAgeHero(result)
            s.pace?.let { PaceCard(it) }
            if (result.stage != BioAgeStage.CALIBRATING) {
                SectionHeader(AppText.text(R.string.copy_45a6b020003e, "What moves it"))
                BipssCard {
                    result.contributors.forEachIndexed { i, c ->
                        if (i > 0) HairlineDivider()
                        ContributorRow(c, onAddLeanMass = { leanDialog = true })
                    }
                }
                TrendCard(s)
            } else {
                BipssCard {
                    Text(AppText.text(R.string.copy_f7d3fa646063, "Wear your band day and night. Your BioAge appears after %1\$s days of wear.",
                        BioAge.MIN_DAYS_PROVISIONAL), color = BipssTheme.colors.textSecondary)
                }
            }
            Text(AppText.text(R.string.copy_60b46a8b72c6, "A lifestyle estimate, not a medical biological age."),
                style = MaterialTheme.typography.bodySmall, color = BipssTheme.colors.textTertiary)
        }
    }
    if (leanDialog) LeanMassDialog(snapshot?.leanMassPct, onDismiss = { leanDialog = false }) {
        vm.setLeanMass(it); leanDialog = false
    }
}

@Composable
internal fun BioAgeHero(r: BioAgeResult) {
    val colors = BipssTheme.colors
    BipssCard {
        Text(AppText.text(R.string.copy_ed172d683572, "BIPSS BioAge"), style = MaterialTheme.typography.titleMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Space.s))
        val bio = r.bioAge
        if (bio == null) {
            Text(AppText.text(R.string.copy_c692da42e924, "Day %1\$s of %2\$s", r.validDays.coerceAtMost(BioAge.MIN_DAYS_PROVISIONAL), BioAge.MIN_DAYS_PROVISIONAL),
                style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary)
            Spacer(Modifier.height(Space.m))
            LinearProgressIndicator(progress = { (r.validDays.toFloat() / BioAge.MIN_DAYS_PROVISIONAL).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(), color = colors.activity)
        } else {
            val delta = r.yearsDelta ?: 0.0
            val tint = when { delta < -0.05 -> colors.bandNormal; delta > 0.05 -> colors.bandRisk; else -> colors.textSecondary }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.1f".format(bio), style = MaterialTheme.typography.displayLarge, color = tint)
                Spacer(Modifier.width(Space.s))
                Text(AppText.text(R.string.copy_1c50993b009c, "years"), style = MaterialTheme.typography.titleMedium,
                    color = colors.textSecondary, modifier = Modifier.padding(bottom = Space.s))
            }
            Spacer(Modifier.height(Space.s))
            Text(yearsDeltaText(delta), style = MaterialTheme.typography.titleSmall, color = tint)
            Spacer(Modifier.height(Space.xs))
            val basis = if (r.stage == BioAgeStage.PROVISIONAL)
                AppText.text(R.string.copy_f906d162880f, "Provisional · based on %1\$s days", r.validDays)
            else AppText.text(R.string.copy_d1dcc8cc5639, "Your age is %1\$s · based on %2\$s days", r.chronologicalAge, r.windowDays)
            Text(basis, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}
