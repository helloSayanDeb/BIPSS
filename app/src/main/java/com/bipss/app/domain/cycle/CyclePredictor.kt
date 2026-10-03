package com.bipss.app.domain.cycle

import com.bipss.app.localization.AppText
import com.bipss.app.R

import kotlin.math.roundToInt

/** One logged period, as local epoch days. [endDay] is inclusive; null while it is ongoing. */
data class Period(val id: Long, val startDay: Long, val endDay: Long?)

enum class CyclePhase(private val englishLabel: String) {
    MENSTRUAL("Menstrual"),
    FOLLICULAR("Follicular"),
    FERTILE("Fertile window"),
    LUTEAL("Luteal");
    val label: String get() = AppText.label(englishLabel)
}

data class CycleStats(
    val cycleLengthDays: Int,
    val periodLengthDays: Int,
    /** True until there is at least one usable gap between logged starts. */
    val usedDefault: Boolean,
    /** Gaps between starts that the average used. */
    val cyclesUsed: Int,
    /** Gaps left out as implausible (< 15 or > 60 days) - usually a missed log. */
    val excludedGaps: List<Int>
)

data class CycleToday(
    /** 1-based day of the current cycle. */
    val cycleDay: Int,
    val phase: CyclePhase,
    val nextStartDay: Long,
    /** Negative once the predicted start has passed without a new period logged. */
    val daysUntilNext: Int,
    val fertileStartDay: Long,
    val fertileEndDay: Long,
    val stats: CycleStats
) {
    val isLate: Boolean get() = daysUntilNext < 0
}

enum class DayKind { LOGGED_PERIOD, PREDICTED_PERIOD, FERTILE, NONE }

/**
 * Cycle-length averaging over the logged history (spec 7).
 *
 * - Cycle length: mean of the last [MAX_CYCLES] gaps between logged starts,
 *   leaving out gaps under [MIN_GAP] or over [MAX_GAP] days (reported, not
 *   silently dropped). Period length: mean of logged periods with an end.
 * - Until two periods are logged: a typical [DEFAULT_CYCLE]/[DEFAULT_PERIOD].
 * - Ovulation is taken as [LUTEAL_DAYS] days before the next predicted start
 *   (the luteal phase is the more constant half of the cycle). The fertile
 *   window is the six days ending on that day - sperm survive up to five
 *   days - plus the day after (Wilcox, Weinberg & Baird, NEJM 1995).
 *
 * Every output is an estimate, and the fertile window is never a method of
 * contraception; the screens say both. Pure Kotlin.
 */
object CyclePredictor {
    const val DEFAULT_CYCLE = 28
    const val DEFAULT_PERIOD = 5
    const val MIN_GAP = 15
    const val MAX_GAP = 60
    const val MAX_CYCLES = 6
    const val LUTEAL_DAYS = 14
    const val FERTILE_BEFORE = 5
    const val FERTILE_AFTER = 1

    fun stats(periods: List<Period>): CycleStats {
        val starts = periods.map { it.startDay }.sorted()
        val gaps = starts.zipWithNext { a, b -> (b - a).toInt() }.takeLast(MAX_CYCLES)
        val usable = gaps.filter { it in MIN_GAP..MAX_GAP }
        val lengths = periods.mapNotNull { p -> p.endDay?.let { (it - p.startDay + 1).toInt() } }.filter { it in 1..15 }
        return CycleStats(
            cycleLengthDays  = if (usable.isEmpty()) DEFAULT_CYCLE else usable.average().roundToInt(),
            periodLengthDays = if (lengths.isEmpty()) DEFAULT_PERIOD else lengths.average().roundToInt(),
            usedDefault      = usable.isEmpty(),
            cyclesUsed       = usable.size,
            excludedGaps     = gaps.filterNot { it in MIN_GAP..MAX_GAP }
        )
    }

    /** Where today falls, or null when no period has been logged. */
    fun today(periods: List<Period>, todayDay: Long): CycleToday? {
        val last = periods.filter { it.startDay <= todayDay }.maxByOrNull { it.startDay } ?: return null
        val s = stats(periods)
        val cycleDay = (todayDay - last.startDay + 1).toInt()
        val nextStart = last.startDay + s.cycleLengthDays
        val ovulation = nextStart - LUTEAL_DAYS
        val fertileStart = ovulation - FERTILE_BEFORE
        val fertileEnd = ovulation + FERTILE_AFTER
        val periodEnd = last.endDay ?: (last.startDay + s.periodLengthDays - 1)
        val phase = when {
            todayDay <= periodEnd || (last.endDay == null && cycleDay <= s.periodLengthDays) -> CyclePhase.MENSTRUAL
            todayDay in fertileStart..fertileEnd -> CyclePhase.FERTILE
            todayDay < fertileStart -> CyclePhase.FOLLICULAR
            else -> CyclePhase.LUTEAL
        }
        return CycleToday(cycleDay, phase, nextStart, (nextStart - todayDay).toInt(), fertileStart, fertileEnd, s)
    }

    /**
     * What a calendar day shows. Logged periods (an ongoing one runs to today
     * or its usual length, whichever comes first); predicted periods and
     * fertile windows for the current cycle and [cyclesAhead] after it.
     */
    fun dayKinds(periods: List<Period>, fromDay: Long, toDay: Long, todayDay: Long, cyclesAhead: Int = 3): Map<Long, DayKind> {
        val out = HashMap<Long, DayKind>()
        val s = stats(periods)
        for (p in periods) {
            val end = p.endDay ?: minOf(todayDay, p.startDay + s.periodLengthDays - 1)
            for (d in maxOf(p.startDay, fromDay)..minOf(end, toDay)) out[d] = DayKind.LOGGED_PERIOD
        }
        val last = periods.filter { it.startDay <= todayDay }.maxByOrNull { it.startDay } ?: return out
        for (k in 0..cyclesAhead) {
            val cycleStart = last.startDay + k.toLong() * s.cycleLengthDays
            val nextStart = cycleStart + s.cycleLengthDays
            val ovulation = nextStart - LUTEAL_DAYS
            for (d in (ovulation - FERTILE_BEFORE)..(ovulation + FERTILE_AFTER)) {
                if (d in fromDay..toDay && d !in out) out[d] = DayKind.FERTILE
            }
            // Predicted periods only from today on: the past is what was logged.
            for (d in nextStart until nextStart + s.periodLengthDays) {
                if (d in fromDay..toDay && d >= todayDay && out[d] != DayKind.LOGGED_PERIOD) out[d] = DayKind.PREDICTED_PERIOD
            }
        }
        return out
    }

    /** Why a start cannot be logged on [day], or null when it can. */
    fun startConflict(periods: List<Period>, day: Long, todayDay: Long): String? = when {
        day > todayDay -> AppText.text(R.string.copy_9a81fedd6398, "A period can't be logged for a day that hasn't happened yet.")
        periods.any { p -> day in p.startDay..(p.endDay ?: (p.startDay + DEFAULT_PERIOD - 1)) } ->
            AppText.text(R.string.copy_b069b7dd6cc6, "That day is already part of a logged period.")
        periods.any { p -> kotlin.math.abs(p.startDay - day) < MIN_GAP - 5 } ->
            AppText.text(R.string.copy_3e3987be91e2, "That's within %1\$s days of another logged start. Remove that one first if it was a mistake.", MIN_GAP - 5)
        else -> null
    }

    /** The ongoing period an end on [day] would close, or null (with the reason from [endConflict]). */
    fun periodToEnd(periods: List<Period>, day: Long): Period? =
        periods.filter { it.startDay <= day && (it.endDay == null || it.endDay >= day) && day - it.startDay < 15 }
            .maxByOrNull { it.startDay }

    fun endConflict(periods: List<Period>, day: Long, todayDay: Long): String? = when {
        day > todayDay -> AppText.text(R.string.copy_30ac3814cb51, "An end can't be logged for a day that hasn't happened yet.")
        periodToEnd(periods, day) == null -> AppText.text(R.string.copy_7260b01dccfb, "Log the start of this period first.")
        else -> null
    }
}
