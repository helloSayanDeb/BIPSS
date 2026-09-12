package com.bipss.app.ui.metric

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bipss.app.ui.components.EmptyState
import com.bipss.app.ui.components.StatusPill
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.MetricUnit
import com.bipss.app.ui.theme.SectionLabelStyle
import com.bipss.app.ui.theme.Space
import kotlin.math.abs
import kotlin.math.round

/**
 * Detail-view trend chart, drawn by hand on a Canvas.
 *
 * Hand-rolled rather than pulled from a chart library because the one property
 * that matters here - never drawing a line across time the band did not
 * measure - is exactly the property charting libraries take away from you.
 */

/** A single plotted sample. A [value] of [Float.NaN] is a deliberate gap marker, not a reading. */
data class GraphPoint(
    val timestampMs: Long,
    val value: Float,
    val isReal: Boolean = true,
)

/**
 * Window the detail view can show. [gapThresholdMs] widens with the window
 * because a five-minute hole is a real hole in a day view but invisible noise
 * across a month.
 */
enum class TimeRange(val label: String, val durationMs: Long, val gapThresholdMs: Long) {
    DAY("Day", 24 * 3600_000L, 5 * 60_000L),
    WEEK("Week", 7 * 24 * 3600_000L, 30 * 60_000L),
    MONTH("Month", 30 * 24 * 3600_000L, 3 * 3600_000L),
}

private const val EmDash = "—"

/**
 * Injects NaN marker points wherever consecutive samples sit further apart than
 * [thresholdMs], so the rendered stroke breaks instead of running straight
 * across the hole.
 *
 * This is the whole point of the chart. If the band was off the wrist between
 * 02:00 and 07:00 and we join the last sample to the next one, the chart shows
 * a smooth five-hour trend that nobody measured - the interpolation invents
 * data, and a reader has no way to tell it apart from a real reading. Two
 * markers are injected rather than one so both edges of the gap are addressable
 * later (shading, "not worn" callouts) without re-deriving them here.
 *
 * Assumes [points] is already ordered by timestamp; ordering is the caller's job.
 */
fun insertGaps(points: List<GraphPoint>, thresholdMs: Long): List<GraphPoint> {
    if (points.size < 2) return points
    val out = ArrayList<GraphPoint>(points.size + 8)
    points.forEachIndexed { i, point ->
        if (i > 0) {
            val previous = points[i - 1]
            val delta = point.timestampMs - previous.timestampMs
            // The 2ms floor keeps the injected timestamps strictly increasing.
            if (delta > thresholdMs && delta > 2L) {
                out += GraphPoint(previous.timestampMs + 1L, Float.NaN, isReal = false)
                out += GraphPoint(point.timestampMs - 1L, Float.NaN, isReal = false)
            }
        }
        out += point
    }
    return out
}

/**
 * Line chart over [points], with an optional dashed [baseline] reference.
 *
 * Value labels are the observed min and max, never the padded axis bounds, so
 * nothing on screen is a number the sensor did not produce.
 */
@Composable
fun MetricLineChart(
    points: List<GraphPoint>,
    baseline: Double?,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 220.dp,
) {
    val colors = BipssTheme.colors

    // Segments are runs of consecutive real samples. Splitting here keeps the
    // draw pass free of allocation and gives each run its own gradient skirt.
    val segments = remember(points) {
        val out = mutableListOf<List<GraphPoint>>()
        var run = mutableListOf<GraphPoint>()
        points.forEach { point ->
            if (point.value.isNaN()) {
                if (run.isNotEmpty()) {
                    out += run
                    run = mutableListOf()
                }
            } else {
                run += point
            }
        }
        if (run.isNotEmpty()) out += run
        out.toList()
    }

    if (segments.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().height(height),
            contentAlignment = Alignment.Center
        ) {
            EmptyState(
                icon = Icons.Rounded.ShowChart,
                title = "No readings in this range",
                body = "Nothing was recorded here. Wear the band to fill the window."
            )
        }
        return
    }

    val observedMin = segments.minOf { seg -> seg.minOf { it.value } }
    val observedMax = segments.maxOf { seg -> seg.maxOf { it.value } }
    // A flat series has zero range, which would divide by zero and pin the line
    // to an edge. Pad it so the reading sits mid-chart and both labels agree.
    val pad = if (observedMax - observedMin < 0.0001f) {
        maxOf(1f, abs(observedMax) * 0.02f)
    } else {
        0f
    }
    val axisLow = observedMin - pad
    val axisSpan = ((observedMax + pad) - axisLow).coerceAtLeast(0.0001f)

    val firstMs = points.minOf { it.timestampMs }
    val timeSpan = (points.maxOf { it.timestampMs } - firstMs).coerceAtLeast(1L).toFloat()

    val labelGutter = Space.huge
    val verticalInset = Space.s

    Box(modifier = modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.fillMaxSize()) {
            val left = labelGutter.toPx()
            val top = verticalInset.toPx()
            val plotWidth = (size.width - left).coerceAtLeast(1f)
            val plotHeight = (size.height - top * 2f).coerceAtLeast(1f)

            fun xFor(timestampMs: Long) =
                left + ((timestampMs - firstMs).toFloat() / timeSpan) * plotWidth

            fun yFor(value: Float) =
                top + (1f - (value - axisLow) / axisSpan) * plotHeight

            // Neutral rather than the metric colour: this is a reference mark,
            // not another reading. Drawn only when it falls inside the window,
            // since clamping it to an edge would misplace it.
            if (baseline != null) {
                val baselineValue = baseline.toFloat()
                if (baselineValue >= axisLow && baselineValue <= axisLow + axisSpan) {
                    val dash = 5.dp.toPx()
                    drawLine(
                        color = colors.textSecondary.copy(alpha = 0.3f),
                        start = Offset(left, yFor(baselineValue)),
                        end = Offset(size.width, yFor(baselineValue)),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), 0f)
                    )
                }
            }

            segments.forEach { segment ->
                // A run of one has no line to draw; a dot still says a reading
                // exists there, which a bare gap would not.
                if (segment.size == 1) {
                    val only = segment.first()
                    drawCircle(
                        color = lineColor,
                        radius = 2.5.dp.toPx(),
                        center = Offset(xFor(only.timestampMs), yFor(only.value))
                    )
                    return@forEach
                }
                val line = Path().apply {
                    moveTo(xFor(segment.first().timestampMs), yFor(segment.first().value))
                    segment.drop(1).forEach { lineTo(xFor(it.timestampMs), yFor(it.value)) }
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo(xFor(segment.last().timestampMs), size.height)
                    lineTo(xFor(segment.first().timestampMs), size.height)
                    close()
                }
                drawPath(
                    area,
                    Brush.verticalGradient(
                        listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0f))
                    )
                )
                drawPath(line, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }

        Text(
            formatFigure(observedMax),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
            modifier = Modifier
                .align(Alignment.TopStart)
                .width(labelGutter)
                .padding(end = Space.xs)
        )
        Text(
            formatFigure(observedMin),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .width(labelGutter)
                .padding(end = Space.xs)
        )
    }
}

/** Segmented pill row for switching the visible window. */
@Composable
fun TimeRangeSelector(
    selected: TimeRange,
    onSelect: (TimeRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BipssTheme.colors
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TimeRange.entries.forEach { range ->
            val isSelected = range == selected
            StatusPill(
                text = range.label,
                color = if (isSelected) accent else colors.textTertiary,
                filled = isSelected,
                // Clipped before clickable so the ripple follows the pill.
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onSelect(range) }
            )
        }
    }
}

/** Summary figures under the chart. A missing figure reads as an em dash, never a zero. */
@Composable
fun MetricStatsRow(
    minValue: Float?,
    avgValue: Float?,
    maxValue: Float?,
    restingValue: Float?,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s)
    ) {
        StatFigure("MIN", minValue, unit, Modifier.weight(1f))
        StatFigure("AVG", avgValue, unit, Modifier.weight(1f))
        StatFigure("MAX", maxValue, unit, Modifier.weight(1f))
        StatFigure("RESTING", restingValue, unit, Modifier.weight(1f))
    }
}

@Composable
private fun StatFigure(
    label: String,
    value: Float?,
    unit: String,
    modifier: Modifier = Modifier,
) {
    val colors = BipssTheme.colors
    val text = formatFigure(value)
    val hasValue = text != EmDash
    Column(modifier = modifier) {
        Text(label, style = SectionLabelStyle, color = colors.textTertiary)
        Spacer(Modifier.height(Space.xs))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                color = if (hasValue) colors.textPrimary else colors.textTertiary
            )
            // No unit beside an em dash: "— bpm" implies a bpm reading exists.
            if (hasValue && unit.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Text(
                    unit,
                    style = MetricUnit,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

/** One decimal place, trailing ".0" dropped. Null and NaN both render as an em dash. */
private fun formatFigure(value: Float?): String {
    if (value == null || value.isNaN() || value.isInfinite()) return EmDash
    val rounded = round(value * 10f) / 10f
    return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
}
