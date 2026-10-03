package com.bipss.app.ui.report

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bipss.app.R
import com.bipss.app.data.WeeklyReportData
import com.bipss.app.domain.report.Stat
import com.bipss.app.localization.AppText
import com.bipss.app.ui.activity.WellnessPage
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space
import org.koin.androidx.compose.koinViewModel

@Composable
fun ReportScreen(nav: NavController, vm: ReportViewModel = koinViewModel()) {
    val report by vm.report.collectAsStateWithLifecycle()
    val start by vm.startDay.collectAsStateWithLifecycle()
    val colors = BipssTheme.colors
    WellnessPage(AppText.text(R.string.copy_53f750c31972, "Weekly report"), nav) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::previous) {
                Icon(Icons.Rounded.ChevronLeft, AppText.text(R.string.copy_c0f8bbcd4176, "Previous week"))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(weekRangeText(start), style = MaterialTheme.typography.titleMedium)
                if (vm.isCurrentWeek(start)) Text(AppText.text(R.string.copy_d510fa0ac6a5, "This week so far"),
                    style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            IconButton(onClick = vm::next, enabled = vm.canGoNext) {
                Icon(Icons.Rounded.ChevronRight, AppText.text(R.string.copy_21fbde19cfb0, "Next week"))
            }
        }
        val r = report
        if (r != null) {
            if (r.summary.daysWithData == 0) {
                BipssCard {
                    Text(AppText.text(R.string.copy_12e47857152d, "Nothing was recorded this week."), color = colors.textSecondary)
                }
            } else {
                ReportBody(r)
                val context = androidx.compose.ui.platform.LocalContext.current
                SecondaryButton(AppText.text(R.string.copy_ee00ac0fd74a, "Share as PDF"),
                    onClick = { vm.share { context.startActivity(it) } }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ReportBody(r: WeeklyReportData) {
    val s = r.summary
    val colors = BipssTheme.colors
    StatRow(
        StatTileData(AppText.text(R.string.copy_d466bcf52eb6, "Sleep"), s.sleepMin, ::minutesText, colors.sleep),
        StatTileData(AppText.text(R.string.copy_48f6a8d5688b, "Recovery"), s.recovery, { "%.0f".format(it) }, colors.bandNormal)
    )
    StatRow(
        StatTileData(AppText.text(R.string.copy_fcde25fd44fb, "Strain"), s.strain, { "%.1f".format(it) }, colors.heart),
        StatTileData(AppText.text(R.string.copy_bf30f480add1, "Steps a day"), s.steps, { "%,.0f".format(it) }, colors.activity)
    )
    StatRow(
        StatTileData(AppText.text(R.string.copy_3fc0fe1697ca, "Resting heart rate"), s.restingHr, { "%.0f bpm".format(it) }, colors.heart),
        StatTileData(AppText.text(R.string.copy_0f3630412996, "Active minutes"), s.activeMin, { "%.0f".format(it) }, colors.activity)
    )
    if (s.highlights.isNotEmpty()) BipssCard {
        SectionLabel(AppText.text(R.string.copy_6e517828c3d8, "Highlights"))
        Spacer(Modifier.height(Space.s))
        s.highlights.forEach { h ->
            Text(highlightText(h.kind, h.epochDay, h.value), Modifier.padding(vertical = Space.xs))
        }
    }
    val a = r.bioAgeAtStart
    val b = r.bioAgeAtEnd
    if (a != null && b != null) BipssCard {
        InfoRow(AppText.text(R.string.copy_765295b69000, "BioAge at the start of the week"), "%.1f".format(a))
        InfoRow(AppText.text(R.string.copy_514b2a67c888, "BioAge at the end"), "%.1f".format(b))
    }
}

private class StatTileData(val label: String, val stat: Stat, val format: (Double) -> String, val tint: androidx.compose.ui.graphics.Color)

@Composable
private fun StatRow(left: StatTileData, right: StatTileData) {
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        StatTile(left, Modifier.weight(1f).fillMaxHeight())
        StatTile(right, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun StatTile(d: StatTileData, modifier: Modifier) {
    val colors = BipssTheme.colors
    BipssCard(modifier = modifier, fillHeight = true) {
        Text(d.label, style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
        Text(d.stat.value?.let(d.format) ?: "—", style = MaterialTheme.typography.headlineSmall, color = d.tint)
        Text(changeText(d.stat, d.format) ?: "", style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary, textAlign = TextAlign.Start)
    }
}
