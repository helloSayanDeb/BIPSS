package com.bipss.app.domain.report

import java.time.DayOfWeek
import java.time.LocalDate

/** One day of the week as the report reads it. */
data class WeekDay(
    val epochDay: Long,
    val sleepMin: Int? = null,
    val sleepScore: Int? = null,
    val recovery: Int? = null,
    val strain: Double? = null,
    val steps: Int? = null,
    val restingHr: Double? = null,
    /** Minutes at 40 percent of heart-rate reserve and above, with movement. */
    val activeMin: Int = 0,
    val workouts: Int = 0,
    val waterMl: Int = 0
)

/** An average or total for the week, and the same for the week before. */
data class Stat(val value: Double?, val previous: Double?) {
    val change: Double? get() = if (value != null && previous != null) value - previous else null
}

enum class HighlightKind { BEST_RECOVERY, LONGEST_SLEEP, MOST_STEPS, HARDEST_DAY }

data class Highlight(val kind: HighlightKind, val epochDay: Long, val value: Double)

data class WeekSummary(
    val startDay: Long,
    val endDay: Long,
    /** Days with anything recorded. */
    val daysWithData: Int,
    val nights: Int,
    val sleepMin: Stat,
    val sleepScore: Stat,
    val recovery: Stat,
    val strain: Stat,
    val steps: Stat,
    val restingHr: Stat,
    val activeMin: Stat,
    val workouts: Stat,
    val waterMl: Stat,
    val highlights: List<Highlight>
)

object WeeklyReport {
    /** Monday of the week containing the day. */
    fun weekStart(epochDay: Long): Long =
        LocalDate.ofEpochDay(epochDay).with(DayOfWeek.MONDAY).toEpochDay()

    private fun mean(values: List<Double>) = values.takeIf { it.isNotEmpty() }?.average()

    private fun stat(week: List<WeekDay>, before: List<WeekDay>, f: (WeekDay) -> Double?) =
        Stat(mean(week.mapNotNull(f)), mean(before.mapNotNull(f)))

    private fun total(week: List<WeekDay>, before: List<WeekDay>, f: (WeekDay) -> Int) =
        Stat(
            if (week.isEmpty()) null else week.sumOf(f).toDouble(),
            if (before.isEmpty()) null else before.sumOf(f).toDouble()
        )

    private fun has(d: WeekDay) = d.sleepMin != null || d.steps != null || d.recovery != null || d.strain != null

    /** The seven days from startDay, compared with the seven before them. */
    fun summarize(all: List<WeekDay>, startDay: Long): WeekSummary {
        val week = all.filter { it.epochDay in startDay..startDay + 6 }
        val before = all.filter { it.epochDay in startDay - 7..startDay - 1 }
        val highlights = buildList {
            week.filter { it.recovery != null }.maxByOrNull { it.recovery ?: 0 }
                ?.let { add(Highlight(HighlightKind.BEST_RECOVERY, it.epochDay, (it.recovery ?: 0).toDouble())) }
            week.filter { it.sleepMin != null }.maxByOrNull { it.sleepMin ?: 0 }
                ?.let { add(Highlight(HighlightKind.LONGEST_SLEEP, it.epochDay, (it.sleepMin ?: 0).toDouble())) }
            week.filter { it.steps != null }.maxByOrNull { it.steps ?: 0 }
                ?.let { add(Highlight(HighlightKind.MOST_STEPS, it.epochDay, (it.steps ?: 0).toDouble())) }
            week.filter { it.strain != null }.maxByOrNull { it.strain ?: 0.0 }
                ?.let { add(Highlight(HighlightKind.HARDEST_DAY, it.epochDay, it.strain ?: 0.0)) }
        }
        return WeekSummary(
            startDay = startDay, endDay = startDay + 6,
            daysWithData = week.count(::has),
            nights = week.count { it.sleepMin != null },
            sleepMin = stat(week, before) { it.sleepMin?.toDouble() },
            sleepScore = stat(week, before) { it.sleepScore?.toDouble() },
            recovery = stat(week, before) { it.recovery?.toDouble() },
            strain = stat(week, before) { it.strain },
            steps = stat(week, before) { it.steps?.toDouble() },
            restingHr = stat(week, before) { it.restingHr },
            activeMin = total(week, before) { it.activeMin },
            workouts = total(week, before) { it.workouts },
            waterMl = stat(week, before) { it.waterMl.takeIf { ml -> ml > 0 }?.toDouble() },
            highlights = highlights
        )
    }
}
