package com.bipss.app.ui.bioage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bipss.app.R
import com.bipss.app.data.BioAgeSnapshot
import com.bipss.app.domain.bioage.*
import com.bipss.app.localization.AppText
import com.bipss.app.localization.LocalNumbers
import com.bipss.app.ui.components.*
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space

@Composable
internal fun PaceCard(p: PaceResult) {
    val colors = BipssTheme.colors
    val tint = when (p.label) {
        PaceLabel.REVERSING, PaceLabel.SLOWING -> colors.bandNormal
        PaceLabel.STEADY -> colors.textSecondary
        PaceLabel.ACCELERATED -> colors.bandRisk
    }
    BipssCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(AppText.text(R.string.copy_c9db6f9452ff, "Pace of Aging"), style = MaterialTheme.typography.titleMedium, color = colors.textSecondary)
                Spacer(Modifier.height(Space.s))
                Text("%.1f×".format(p.pace), style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary)
            }
            StatusPill(paceLabelText(p.label), tint)
        }
        Spacer(Modifier.height(Space.m))
        PaceGauge(p.pace, tint)
        Spacer(Modifier.height(Space.s))
        Text(AppText.text(R.string.copy_4e71829381fa, "Your last 30 days against the past six months."),
            style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
    }
}

/** A track from -1x to 3x with a marker; 1x (the calendar's own pace) is ticked. */
@Composable
private fun PaceGauge(pace: Double, tint: Color) {
    val colors = BipssTheme.colors
    val fraction = ((pace + 1.0) / 4.0).toFloat().coerceIn(0f, 1f)
    BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp)) {
        val w = maxWidth
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.field))
        Box(Modifier.align(Alignment.CenterStart).offset(x = w * 0.5f - 1.dp).width(2.dp).height(14.dp).background(colors.textTertiary))
        Box(Modifier.align(Alignment.CenterStart).offset(x = w * fraction - 8.dp).size(16.dp).clip(RoundedCornerShape(8.dp)).background(tint))
    }
}

@Composable
internal fun ContributorRow(c: ContributorResult, onAddLeanMass: () -> Unit) {
    val colors = BipssTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(contributorName(c.contributor), style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary, modifier = Modifier.weight(1f))
            val y = c.years
            if (y != null) {
                val tint = when { y < -0.05 -> colors.bandNormal; y > 0.05 -> colors.bandRisk; else -> colors.textSecondary }
                StatusPill(signedYears(y), tint)
            }
        }
        Spacer(Modifier.height(Space.xs))
        val value = c.value
        val target = c.target
        when {
            value != null && target != null -> Text(
                AppText.text(R.string.copy_a4ca3061a856, "%1\$s · target %2\$s", contributorAmount(c.contributor, value),
                    contributorAmount(c.contributor, target)),
                style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            c.skipped == Skipped.NOT_ENTERED && c.contributor == Contributor.LEAN_MASS ->
                TextButton(onClick = onAddLeanMass, contentPadding = PaddingValues(0.dp)) {
                    Text(AppText.text(R.string.copy_35ebc446e74b, "Add from your scale"))
                }
            c.skipped == Skipped.NOT_ENTERED -> Text(
                AppText.text(R.string.copy_86de1dedd7a3, "Counts once you log strength sessions"),
                style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
            else -> Text(AppText.text(R.string.copy_9dce017a6b6b, "Not enough data yet"),
                style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
        }
    }
}

@Composable
internal fun TrendCard(s: BioAgeSnapshot) {
    if (s.history.size < 2) return
    val colors = BipssTheme.colors
    BipssCard {
        Text(AppText.text(R.string.copy_c287ac938c17, "Recent trend"), style = MaterialTheme.typography.titleMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Space.m))
        Sparkline(s.history.map { it.bioAge.toFloat() }, colors.activity, Modifier.fillMaxWidth().height(72.dp))
    }
}

@Composable
internal fun LeanMassDialog(current: Double?, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current?.let { "%.0f".format(it) } ?: "") }
    val value = LocalNumbers.floatOrNull(text)?.toDouble()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppText.text(R.string.copy_5750b5e8d7c4, "Lean body mass")) },
        text = {
            OutlinedTextField(
                text, { text = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(5) },
                label = { Text(AppText.text(R.string.copy_5251fb4b571f, "Percent of body weight")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = { Text(AppText.text(R.string.copy_cda42f38916b, "From a body-composition scale, 30 to 100")) }
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }, enabled = value != null && value in 30.0..100.0) {
                Text(AppText.text(R.string.copy_1509f561f241, "Save"))
            }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text(AppText.text(R.string.copy_c3812fc4acb8, "Remove")) }
                TextButton(onClick = onDismiss) { Text(AppText.text(R.string.copy_19766ed6ccb2, "Cancel")) }
            }
        }
    )
}
