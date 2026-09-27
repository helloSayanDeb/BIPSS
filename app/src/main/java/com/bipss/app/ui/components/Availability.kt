package com.bipss.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.platform.LocalDensity
import com.bipss.app.domain.BaselineSchedule
import com.bipss.app.domain.DataMode
import com.bipss.app.domain.RiskScore
import com.bipss.app.domain.ScoreAvailability
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.MetricHero
import com.bipss.app.ui.theme.MetricValue
import com.bipss.app.ui.theme.ModeLocalOnly
import com.bipss.app.ui.theme.ModeMinimal
import com.bipss.app.ui.theme.ModeOptimal
import com.bipss.app.ui.theme.ModePhoneOnly
import com.bipss.app.ui.theme.Space
import kotlin.math.roundToInt

/**
 * Rendering of the data-availability model.
 *
 * Every composable here exists so that the absence of data looks different
 * from a measurement of zero. Scoring already tracks that distinction in
 * ScoreAvailability; these are the surfaces that have to honour it.
 */

/** The em dash stands in wherever a number would be a lie. */
private const val NO_VALUE = "—"

/**
 * Chip explaining why a score is incomplete, or nothing at all when it isn't.
 *
 * Amber for PARTIAL because the user can act on it - putting the band on
 * changes the answer. Grey for the two blocked states, which are statements
 * of fact rather than warnings about the body.
 */
@Composable
fun AvailabilityChip(availability: ScoreAvailability, modifier: Modifier = Modifier) {
    val colors = BipssTheme.colors
    val spec = when (availability) {
        ScoreAvailability.AVAILABLE          -> null
        ScoreAvailability.PARTIAL            ->
            "Environment only - wear band for full picture" to colors.bandMonitor
        ScoreAvailability.NEEDS_BAND         -> "Needs band data" to colors.textTertiary
        ScoreAvailability.NEEDS_MORE_HISTORY -> "Building baseline" to colors.textTertiary
    } ?: return

    val (label, tint) = spec
    StatusPill(text = label, color = tint, modifier = modifier)
}

/**
 * Score gauge that refuses to invent a reading.
 *
 * When a domain cannot be computed the score field is a placeholder zero, and
 * drawing that zero would put an empty ring and the numeral "0" in front of
 * the user. On a hazard domain that reads as "you are fine"; on a wellness
 * domain it reads as "you are in trouble". Neither is what happened - what
 * happened is that we have no idea. "No idea" and "measured zero" are
 * different statements and only one of them is ever true here, so an
 * uncomputable score gets a grey ring at zero progress and an em dash, with
 * no "of 100" caption to imply the number was scored and simply came out low.
 *
 * Pair it with [ScoreReasonLine] so the blank says why it is blank.
 */
@Composable
fun ScoreRing(
    score: RiskScore,
    size: Dp = 116.dp,
    strokeWidth: Dp = 11.dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val colors = BipssTheme.colors
    val known = score.hasNumber
    val label = if (known) "${score.score}" else NO_VALUE

    // The number is sized from the gauge, in dp so a large system font cannot
    // push it into the stroke. A fixed 34sp fitted the hero gauge but ran over
    // the stroke in the 64dp and 46dp gauges, most visibly for "100".
    val digitSize = with(LocalDensity.current) {
        (size * if (label.length >= 3) 0.26f else 0.30f).toSp()
    }

    RingGauge(
        progress = if (known) score.score / 100f else 0f,
        color = if (known) color else colors.textTertiary,
        modifier = modifier,
        size = size,
        strokeWidth = strokeWidth
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = MetricHero.copy(
                    fontSize = digitSize,
                    lineHeight = digitSize,
                    letterSpacing = (-0.02).em
                ),
                maxLines = 1,
                color = if (known) colors.textPrimary else colors.textTertiary
            )
            // The caption only fits in a hero-sized ring. In the 46dp row
            // rings it wrapped and clipped the number itself, so "100" read
            // as "10" - a rendering bug that silently misreports the score.
            if (known && size >= 80.dp) {
                Text(
                    "of 100",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary
                )
            }
        }
    }
}

/** The one-line "why" that accompanies a missing or partial score. */
@Composable
fun ScoreReasonLine(score: RiskScore, modifier: Modifier = Modifier) {
    val reason = score.reasonLine
    if (reason.isBlank()) return

    Text(
        reason,
        style = MaterialTheme.typography.bodySmall,
        color = BipssTheme.colors.textTertiary,
        modifier = modifier
    )
}

/**
 * How much the number should be trusted.
 *
 * Hidden at full confidence: a bar pinned at 100% on every card is noise, and
 * noise is what stops people reading the bar on the card where it matters.
 * Amber below 0.7 for the same reason [AvailabilityChip] uses it - the score
 * is shown, but it is thin evidence.
 */
@Composable
fun ConfidenceBar(confidence: Float, modifier: Modifier = Modifier) {
    if (confidence >= 1f) return

    val colors = BipssTheme.colors
    val clamped = confidence.coerceIn(0f, 1f)
    val tint = if (clamped < 0.7f) colors.bandMonitor else colors.textSecondary

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LinearProgressIndicator(
            progress = { clamped },
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = tint,
            trackColor = colors.hairline,
            gapSize = 0.dp,
            drawStopIndicator = {}
        )
        Spacer(Modifier.width(Space.m))
        Text(
            "Confidence ${(clamped * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary
        )
    }
}

/**
 * Progress towards a baseline the app can stand behind.
 *
 * Disappears once the schedule is mature - by then the user has nothing left
 * to wait for, and a permanent full bar would just be another thing to ignore.
 */
@Composable
fun BaselineProgressChip(daysOfData: Int, modifier: Modifier = Modifier) {
    if (daysOfData >= BaselineSchedule.MATURE_DAYS) return

    val colors = BipssTheme.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LinearProgressIndicator(
            progress = { BaselineSchedule.progressFor(daysOfData) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = colors.bandMonitor,
            trackColor = colors.hairline,
            gapSize = 0.dp,
            drawStopIndicator = {}
        )
        Text(
            BaselineSchedule.stageLabel(daysOfData),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary
        )
    }
}

/** Which inputs the score was computed from, in the degraded-mode colours. */
@Composable
fun DataModeChip(mode: DataMode, modifier: Modifier = Modifier) {
    val tint = when (mode) {
        DataMode.FULL             -> ModeOptimal
        DataMode.BAND_ONLY        -> ModeLocalOnly
        DataMode.ENVIRONMENT_ONLY -> ModePhoneOnly
        DataMode.CACHED           -> ModeMinimal
    }
    ModeChip(label = mode.label, color = tint, modifier = modifier)
}

/**
 * The single rendering of "no reading".
 *
 * Every screen routes a missing measurement through here so that absence
 * always looks the same, and so no screen can quietly start substituting a
 * plausible-looking default instead.
 */
@Composable
fun MissingValueText(modifier: Modifier = Modifier) {
    Text(
        NO_VALUE,
        style = MetricValue,
        color = BipssTheme.colors.textTertiary,
        modifier = modifier
    )
}
