package com.bipss.app.domain

/**
 * Widening baseline schedule.
 *
 * "Track changes in baseline health patterns" means the window has to grow as
 * evidence accumulates: three days is enough to notice something, ninety days
 * is enough to trust it. A fixed window either reacts too slowly at the start
 * or stays too jumpy at the end, so the window widens 3 -> 7 -> 15 -> 30 -> 90
 * and confidence rises with it.
 *
 * Pure Kotlin, no Android dependencies - unit-testable.
 */
object BaselineSchedule {

    /** Minimum days of data before any baseline-derived score is meaningful. */
    const val MIN_DAYS_FOR_BASELINE = 3

    /** Days at which the schedule is considered mature. */
    const val MATURE_DAYS = 30

    /** (days of data required, window size to use). */
    private val STAGES = listOf(
        0   to 0,
        3   to 3,
        7   to 7,
        15  to 15,
        30  to 30,
        60  to 30,   // hold the 30-day window through day 60
        90  to 90    // then widen to 90
    )

    /** The averaging window to apply given how many days of data exist. */
    fun windowDaysFor(daysOfData: Int): Int =
        STAGES.lastOrNull { daysOfData >= it.first }?.second ?: 0

    /** True once baseline-derived domains may report a number at all. */
    fun isUsable(daysOfData: Int): Boolean = daysOfData >= MIN_DAYS_FOR_BASELINE

    fun stageLabel(daysOfData: Int): String = when {
        daysOfData < 3  -> "Learning your baseline - day $daysOfData of 3"
        daysOfData < 7  -> "Baseline: 3-day - refining to 7-day"
        daysOfData < 15 -> "Baseline: 7-day - refining to 15-day"
        daysOfData < 30 -> "Baseline: 15-day - refining to 30-day"
        daysOfData < 90 -> "Baseline: 30-day"
        else            -> "Baseline: 90-day"
    }

    /**
     * How much to trust a baseline-derived score. Feeds RiskScore.confidence,
     * so a three-day baseline visibly carries less weight than a thirty-day one
     * instead of both being presented as equally certain.
     */
    fun confidenceFor(daysOfData: Int): Float = when {
        daysOfData < 3  -> 0.0f
        daysOfData < 7  -> 0.5f
        daysOfData < 15 -> 0.7f
        daysOfData < 30 -> 0.85f
        else            -> 1.0f
    }

    /** Progress toward a mature baseline, for the onboarding chip. */
    fun progressFor(daysOfData: Int): Float =
        (daysOfData.toFloat() / MATURE_DAYS).coerceIn(0f, 1f)

    /** Shown in place of a score while the baseline is still building. */
    fun missingLabel(daysOfData: Int): String =
        "$daysOfData of $MIN_DAYS_FOR_BASELINE days baseline"
}
