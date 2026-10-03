package com.bipss.app.ui.planner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import com.bipss.app.R
import com.bipss.app.data.SleepPlanState
import com.bipss.app.localization.AppText
import com.bipss.app.ui.components.BipssCard
import com.bipss.app.ui.theme.BipssTheme
import com.bipss.app.ui.theme.Space

/** The last two weeks of nights as bars against the line of what you usually need. */
@Composable
fun NightsCard(s: SleepPlanState) {
    val colors = BipssTheme.colors
    val base = s.plan.baselineNeedMin
    val top = maxOf(base, s.nights.maxOf { it.sleepMinutes }, 9 * 60).toFloat()
    BipssCard {
        Text(AppText.text(R.string.copy_bff54d6c272f, "Recent nights"), style = MaterialTheme.typography.titleMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Space.m))
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val slot = size.width / 14f
            val bar = slot * 0.62f
            val offset = 14 - s.nights.size
            s.nights.forEachIndexed { i, n ->
                val h = size.height * (n.sleepMinutes / top)
                drawRoundRect(
                    color = if (n.sleepMinutes < base) colors.bandMonitor else colors.sleep,
                    topLeft = Offset((offset + i) * slot + (slot - bar) / 2f, size.height - h),
                    size = Size(bar, h), cornerRadius = CornerRadius(4.dp.toPx())
                )
            }
            val y = size.height * (1f - base / top)
            drawLine(colors.textTertiary, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        Spacer(Modifier.height(Space.s))
        Text(AppText.text(R.string.copy_7f729fc10db8, "Dashed line: your usual need, %1\$s", durationText(base)),
            style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
    }
}
