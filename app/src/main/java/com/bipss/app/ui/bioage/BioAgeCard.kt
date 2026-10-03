package com.bipss.app.ui.bioage

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.R
import com.bipss.app.domain.bioage.BioAge
import com.bipss.app.domain.bioage.BioAgeStage
import com.bipss.app.localization.AppText
import com.bipss.app.navigation.Routes
import com.bipss.app.ui.components.BipssCard
import com.bipss.app.ui.components.StatusPill
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel

/** The BioAge entry on the Insights tab: the number when there is one, the progress toward it when not. */
@Composable
fun BioAgeCard(nav: NavController, vm: BioAgeViewModel = koinViewModel()) {
    val colors = BipssTheme.colors
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val result = snapshot?.result
    BipssCard(onClick = { nav.navigate(Routes.BIOAGE) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(AppText.text(R.string.copy_ed172d683572, "BIPSS BioAge"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Space.s))
                val bio = result?.bioAge
                when {
                    result == null -> Text(AppText.text(R.string.copy_b7b0038864f4, "Your body's age, from how you live"), color = colors.textSecondary)
                    bio == null -> Text(AppText.text(R.string.copy_c692da42e924, "Day %1\$s of %2\$s", result.validDays.coerceAtMost(BioAge.MIN_DAYS_PROVISIONAL),
                        BioAge.MIN_DAYS_PROVISIONAL), color = colors.textSecondary)
                    else -> Text(yearsDeltaText(result.yearsDelta ?: 0.0), color = colors.textSecondary)
                }
            }
            val bio = result?.bioAge
            if (bio != null) {
                val delta = result.yearsDelta ?: 0.0
                val tint = when { delta < -0.05 -> colors.bandNormal; delta > 0.05 -> colors.bandRisk; else -> colors.textPrimary }
                Column(horizontalAlignment = Alignment.End) {
                    Text("%.1f".format(bio), style = MaterialTheme.typography.headlineMedium, color = tint)
                    if (result.stage == BioAgeStage.PROVISIONAL) StatusPill(AppText.text(R.string.copy_9b46c2e6d68c, "Provisional"), colors.textSecondary)
                }
            }
        }
    }
}
