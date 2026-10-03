package com.bipss.app.ui.export

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.R
import com.bipss.app.localization.AppText
import com.bipss.app.ui.activity.WellnessPage
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel

@Composable
fun ExportScreen(nav: NavController, vm: ExportViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var days by rememberSaveable { mutableIntStateOf(30) }
    val launch = { kind: ExportKind -> vm.run(kind, days) { context.startActivity(it) } }
    WellnessPage(AppText.text(R.string.copy_9c80b04be554, "Export your data"), nav) {
        ui.error?.let { Text(it, color = BipssTheme.colors.bandRisk) }
        ExportCard(AppText.text(R.string.copy_85e1926550d0, "Health report"),
            AppText.text(R.string.copy_c5c1b1749568, "A PDF of your sleep, steps, heart rate, BioAge and VO₂ max."),
            AppText.text(R.string.copy_e45411b9a471, "Create PDF"), ui.busy == ExportKind.HEALTH_REPORT, ui.busy == null,
            { launch(ExportKind.HEALTH_REPORT) }) {
            SegmentedControl(listOf(30, 90, 180), days, { AppText.text(R.string.copy_debd3794fc2c, "%1\$s days", it) }, { days = it })
            Spacer(Modifier.height(Space.m))
        }
        ExportCard(AppText.text(R.string.copy_e65608cab0f5, "Overnight oxygen record"),
            AppText.text(R.string.copy_ec6ccae7555f, "A PDF of every night and every oxygen dip, to show a doctor."),
            AppText.text(R.string.copy_e45411b9a471, "Create PDF"), ui.busy == ExportKind.OXYGEN_RECORD, ui.busy == null,
            { launch(ExportKind.OXYGEN_RECORD) })
        ExportCard(AppText.text(R.string.copy_da7c7a272506, "All your data"),
            AppText.text(R.string.copy_c87c94753c6e, "CSV files for spreadsheets: daily summaries, sleep, oxygen dips, water, workouts, journal and readings."),
            AppText.text(R.string.copy_8463eb4799ce, "Create ZIP"), ui.busy == ExportKind.CSV, ui.busy == null,
            { launch(ExportKind.CSV) })
    }
}

@Composable
private fun ExportCard(
    title: String, body: String, action: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {}
) {
    BipssCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Space.s))
        Text(body, color = BipssTheme.colors.textSecondary)
        Spacer(Modifier.height(Space.m))
        extra()
        PrimaryButton(if (busy) AppText.text(R.string.copy_c79ed9492e3c, "Creating…") else action, onClick = onClick, enabled = enabled, loading = busy)
    }
}
