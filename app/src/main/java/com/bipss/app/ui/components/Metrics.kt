package com.bipss.app.ui.components

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.MetricHero
import com.bipss.app.ui.theme.MetricUnit
import com.bipss.app.ui.theme.MetricValue
import com.bipss.app.ui.theme.Space

/**
 * Animated score gauge: a rounded square whose outline fills clockwise from
 * top centre, like a clock hand. Used at hero size for the headline score and
 * at small size in the per-domain rows. The square leaves more room inside
 * than a circle of the same size, which is what lets a three-digit score sit
 * clear of the stroke.
 */
@Composable
fun RingGauge(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
    strokeWidth: Dp = 12.dp,
    content: @Composable () -> Unit = {},
) {
    val colors = BipssTheme.colors
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(900, easing = EaseOutCubic),
        label = "gauge"
    )
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val outline = roundedSquareOutline(this.size, inset = stroke / 2f)
            val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(outline, colors.hairline, style = style)
            if (animated > 0f) {
                val measure = PathMeasure().apply { setPath(outline, false) }
                val filled = Path()
                measure.getSegment(0f, measure.length * animated, filled, true)
                drawPath(filled, color, style = style)
            }
        }
        content()
    }
}

/** Rounded-square outline that starts at top centre and runs clockwise. */
private fun roundedSquareOutline(area: Size, inset: Float, cornerFraction: Float = 0.3f): Path {
    val l = inset
    val t = inset
    val r = area.width - inset
    val b = area.height - inset
    val d = (r - l) * cornerFraction * 2f
    return Path().apply {
        moveTo((l + r) / 2f, t)
        lineTo(r - d / 2f, t)
        arcTo(Rect(r - d, t, r, t + d), -90f, 90f, false)
        lineTo(r, b - d / 2f)
        arcTo(Rect(r - d, b - d, r, b), 0f, 90f, false)
        lineTo(l + d / 2f, b)
        arcTo(Rect(l, b - d, l + d, b), 90f, 90f, false)
        lineTo(l, t + d / 2f)
        arcTo(Rect(l, t, l + d, t + d), 180f, 90f, false)
        close()
    }
}

/** Minimal polyline trend, no axes — a shape cue, not a chart. */
@Composable
fun Sparkline(
    points: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
) {
    if (points.size < 2) {
        Box(modifier)
        return
    }
    Canvas(modifier) {
        val min = points.min()
        val max = points.max()
        val range = (max - min).takeIf { it > 0.0001f } ?: 1f
        val dx = size.width / (points.size - 1)

        fun yFor(v: Float) = size.height - ((v - min) / range) * size.height * 0.86f - size.height * 0.07f

        val line = Path().apply {
            moveTo(0f, yFor(points.first()))
            points.forEachIndexed { i, v -> if (i > 0) lineTo(i * dx, yFor(v)) }
        }
        if (filled) {
            val area = Path().apply {
                addPath(line)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(
                area,
                Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f)))
            )
        }
        drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * A single vital in the 2×2 grid: identity icon, label, big value, unit, and
 * a sparkline of recent readings. Shows an explicit em-dash when there is no
 * reading yet rather than a misleading zero.
 */
@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String,
    icon: ImageVector,
    tint: Color,
    history: List<Float>,
    modifier: Modifier = Modifier,
    caption: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = BipssTheme.colors
    // Every tile has the same parts in the same places - label at the top;
    // value, trend and caption at the bottom - so tiles side by side line up
    // whether or not they have readings. A tile that lost its trend line or
    // gained a second label line used to come out a different height.
    BipssCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(Space.l), fillHeight = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint, size = 30.dp)
            Spacer(Modifier.width(Space.s))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
        }
        Column {
            Spacer(Modifier.height(Space.l))
            val hasValue = value != "—"
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = MetricValue,
                    color = if (hasValue) colors.textPrimary else colors.textTertiary
                )
                if (unit.isNotEmpty() && hasValue) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        unit,
                        style = MetricUnit,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(bottom = 5.dp)
                    )
                }
            }
            Spacer(Modifier.height(Space.s))
            if (history.size >= 2) {
                Sparkline(
                    points = history,
                    color = tint,
                    modifier = Modifier.fillMaxWidth().height(30.dp)
                )
            } else {
                // The trend's place, kept: a faint rule reads as "no trend
                // yet", where a gap read as a broken tile.
                Box(Modifier.fillMaxWidth().height(30.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
                }
            }
            Spacer(Modifier.height(Space.s))
            Text(
                caption ?: "Waiting for readings",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
                maxLines = 1
            )
        }
    }
}

/**
 * Hero risk card — the leading domain with a big gauge, band label and the
 * contributing factors as pills. The contributor breakdown is the whole
 * point: a score with no explanation isn't actionable.
 */
@Composable
fun RiskHeroCard(
    domain: String,
    score: Int,
    bandLabel: String,
    color: Color,
    factors: List<Pair<String, Boolean>>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = BipssTheme.colors
    BipssCard(modifier = modifier, onClick = onClick, accent = color, contentPadding = PaddingValues(Space.xl)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingGauge(
                progress = score / 100f,
                color = color,
                size = 116.dp,
                strokeWidth = 11.dp
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$score", style = MetricHero.copy(fontSize = 34.sp), color = colors.textPrimary)
                    Text("of 100", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
                }
            }
            Spacer(Modifier.width(Space.xl))
            Column(Modifier.weight(1f)) {
                Text(
                    domain,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary
                )
                Spacer(Modifier.height(Space.s))
                StatusPill(bandLabel, color)
            }
        }
        if (factors.isNotEmpty()) {
            Spacer(Modifier.height(Space.l))
            HairlineDivider()
            Spacer(Modifier.height(Space.m))
            Text(
                "WHAT'S DRIVING IT",
                style = com.bipss.app.ui.theme.SectionLabelStyle,
                color = colors.textTertiary
            )
            Spacer(Modifier.height(Space.s))
            FlowPills(factors)
        }
    }
}

/** Simple wrapping row of factor pills. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FlowPills(factors: List<Pair<String, Boolean>>, modifier: Modifier = Modifier) {
    val colors = BipssTheme.colors
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        factors.forEach { (label, elevated) ->
            StatusPill(
                text = label,
                color = if (elevated) colors.bandMonitor else colors.bandNormal
            )
        }
    }
}

/** Compact per-domain row for the domains below the hero. */
@Composable
fun RiskRow(
    domain: String,
    score: Int,
    bandLabel: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = BipssTheme.colors
    BipssCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(Space.l)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingGauge(progress = score / 100f, color = color, size = 46.dp, strokeWidth = 5.dp) {
                Text(
                    "$score",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textPrimary
                )
            }
            Spacer(Modifier.width(Space.l))
            Column(Modifier.weight(1f)) {
                Text(domain, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Spacer(Modifier.height(Space.xs))
                Text(bandLabel, style = MaterialTheme.typography.bodySmall, color = color)
            }
        }
    }
}

/** Degraded-mode chip — colour is the signal (software plan §8). */
@Composable
fun ModeChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = Space.m, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
