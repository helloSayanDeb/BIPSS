package com.bipss.app.domain.sleep

/** One recorded night: the day it ended on, and how long was slept. */
data class PlannerNight(val epochDay: Long, val sleepMinutes: Int)

/**
 * Tonight's sleep, planned from your own nights.
 * need = baseline + extra for a hard day + a share of recent debt.
 */
data class SleepPlan(
    val baselineNeedMin: Int,
    val strainExtraMin: Int,
    val debtExtraMin: Int,
    val needMin: Int,
    /** Sleep owed over the last seven recorded nights, against the baseline. */
    val debtMin: Int,
    /** Minute of the day to be in bed (0..1439). */
    val bedtimeMin: Int,
    val wakeMin: Int,
    /** True when the baseline is the default because there are too few nights. */
    val baselineIsDefault: Boolean
)

object SleepPlanner {
    const val DEFAULT_NEED_MIN = 8 * 60
    const val MIN_NEED_MIN = 7 * 60
    const val MAX_NEED_MIN = 9 * 60
    const val NIGHTS_FOR_BASELINE = 7
    /** Time allowed to fall asleep after getting into bed. */
    const val LATENCY_MIN = 15
    const val MAX_STRAIN_EXTRA_MIN = 45
    const val MAX_DEBT_EXTRA_MIN = 60

    private fun roundTo5(v: Double) = (Math.round(v / 5.0) * 5).toInt()

    /**
     * The baseline is the 75th percentile of up to the last 14 nights (what
     * you manage when you can, not what a bad week dragged you to), held
     * between seven and nine hours. With fewer than seven nights it is eight.
     */
    fun baseline(nights: List<PlannerNight>): Pair<Int, Boolean> {
        val recent = nights.sortedBy { it.epochDay }.takeLast(14).map { it.sleepMinutes }.sorted()
        if (recent.size < NIGHTS_FOR_BASELINE) return DEFAULT_NEED_MIN to true
        val p75 = recent[((recent.size - 1) * 0.75).toInt()]
        return roundTo5(p75.toDouble()).coerceIn(MIN_NEED_MIN, MAX_NEED_MIN) to false
    }

    /** Strain below 10 asks for nothing extra; 18 and above asks for 45 minutes. */
    fun strainExtra(strain: Double?): Int =
        if (strain == null || strain <= 10.0) 0
        else roundTo5(((strain - 10.0) / 8.0).coerceIn(0.0, 1.0) * MAX_STRAIN_EXTRA_MIN)

    fun plan(nights: List<PlannerNight>, yesterdayStrain: Double?, wakeMin: Int): SleepPlan {
        val (base, isDefault) = baseline(nights)
        val debt = nights.sortedBy { it.epochDay }.takeLast(7).sumOf { (base - it.sleepMinutes).coerceAtLeast(0) }
        val debtExtra = roundTo5((debt * 0.25).coerceAtMost(MAX_DEBT_EXTRA_MIN.toDouble()))
        val strain = strainExtra(yesterdayStrain)
        val need = (base + strain + debtExtra).coerceAtMost(10 * 60)
        val bed = ((wakeMin - need - LATENCY_MIN) % 1440 + 1440) % 1440
        return SleepPlan(base, strain, debtExtra, need, debt, bed, wakeMin, isDefault)
    }
}
