package com.bipss.app.ui.report

import com.bipss.app.R
import com.bipss.app.domain.report.HighlightKind
import com.bipss.app.domain.report.Stat
import com.bipss.app.localization.AppText
import com.bipss.app.ui.planner.durationText
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dayName = DateTimeFormatter.ofPattern("EEEE")
private val shortDate = DateTimeFormatter.ofPattern("d MMM")

fun weekRangeText(startDay: Long): String =
    AppText.text(R.string.copy_a08f3b8b0cde, "%1\$s – %2\$s", LocalDate.ofEpochDay(startDay).format(shortDate),
        LocalDate.ofEpochDay(startDay + 6).format(shortDate))

fun dayNameText(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dayName)

/** "↑ 12 min" style change against the week before; null when either week had nothing. */
fun changeText(stat: Stat, format: (Double) -> String): String? {
    val c = stat.change ?: return null
    if (kotlin.math.abs(c) < 1e-9) return AppText.text(R.string.copy_0ea76de9c5f7, "Same as the week before")
    val arrow = if (c > 0) "↑ " else "↓ "
    return arrow + AppText.text(R.string.copy_1f5532575098, "%1\$s vs the week before", format(kotlin.math.abs(c)))
}

fun minutesText(m: Double) = durationText(m.toInt())

fun highlightText(kind: HighlightKind, day: Long, value: Double): String {
    val name = dayNameText(day)
    return when (kind) {
        HighlightKind.BEST_RECOVERY -> AppText.text(R.string.copy_47dcdf9837ee, "Best recovery: %1\$s, %2\$s", name, value.toInt())
        HighlightKind.LONGEST_SLEEP -> AppText.text(R.string.copy_c01f132861f8, "Longest sleep: %1\$s, %2\$s", name, durationText(value.toInt()))
        HighlightKind.MOST_STEPS -> AppText.text(R.string.copy_022f55b027e1, "Most steps: %1\$s, %2\$s", name, "%,d".format(value.toInt()))
        HighlightKind.HARDEST_DAY -> AppText.text(R.string.copy_fc2399393d70, "Hardest day: %1\$s, strain %2\$s", name, "%.1f".format(value))
    }
}
