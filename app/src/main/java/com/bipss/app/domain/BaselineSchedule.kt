package com.bipss.app.domain

/** How far a personal baseline has come. */
enum class BaselineStage {
    /** Fewer than 14 days of data: no baseline-derived verdicts at all. */
    CALIBRATING,
    /** 14-29 days: verdicts shown, marked provisional. */
    PROVISIONAL,
    /** 30 days and more: established, still refining up to day 90. */
    ESTABLISHED
}

/**
 * The personal-baseline schedule.
 *
 * Day N counts *days with data*, not days since install: a day counts once it
 * holds at least [MIN_VALID_MINUTES] minutes of measured heart rate, so a week
 * in a drawer does not advance calibration. Day 1 is the first such day.
 *
 * Milestones 1, 3, 7, 14, 30, 45, 60, 80 and 90 mark where the baseline gets
 * visibly better: before day 14 nothing is compared against it (a few days
 * cannot separate a person's normal from a bad week); from 14 comparisons are
 * shown as provisional; from 30 the baseline is established and keeps
 * tightening until day 90.
 *
 * Pure Kotlin - unit-testable.
 */
object BaselineSchedule {

    val MILESTONES = listOf(1, 3, 7, 14, 30, 45, 60, 80, 90)

    const val PROVISIONAL_FROM = 14
    const val ESTABLISHED_FROM = 30
    const val MATURE_DAYS = 90

    /** A day counts toward the baseline once it has this many minutes of measured heart rate. */
    const val MIN_VALID_MINUTES = 60

    fun stage(dayN: Int): BaselineStage = when {
        dayN < PROVISIONAL_FROM -> BaselineStage.CALIBRATING
        dayN < ESTABLISHED_FROM -> BaselineStage.PROVISIONAL
        else                    -> BaselineStage.ESTABLISHED
    }

    /** True once baseline-derived comparisons may be shown at all. */
    fun isUsable(dayN: Int): Boolean = dayN >= PROVISIONAL_FROM

    /** The next milestone ahead, or null at maturity. */
    fun nextMilestone(dayN: Int): Int? = MILESTONES.firstOrNull { it > dayN }

    /**
     * Confidence in a baseline-derived value: none while calibrating, then
     * rising linearly between milestones from 0.5 at day 14 to 1.0 at day 90.
     */
    fun confidenceFor(dayN: Int): Float {
        if (dayN < PROVISIONAL_FROM) return 0f
        if (dayN >= MATURE_DAYS) return 1f
        val points = listOf(14 to 0.5f, 30 to 0.75f, 45 to 0.82f, 60 to 0.88f, 80 to 0.95f, 90 to 1.0f)
        val (d0, c0) = points.last { it.first <= dayN }
        val (d1, c1) = points.first { it.first > dayN }
        return c0 + (c1 - c0) * (dayN - d0) / (d1 - d0).toFloat()
    }

    /** Progress toward the next stage boundary (14, then 30, then 90), for the chip. */
    fun progressFor(dayN: Int): Float = when {
        dayN < PROVISIONAL_FROM -> dayN / PROVISIONAL_FROM.toFloat()
        dayN < ESTABLISHED_FROM -> (dayN - PROVISIONAL_FROM) / (ESTABLISHED_FROM - PROVISIONAL_FROM).toFloat()
        else -> ((dayN - ESTABLISHED_FROM) / (MATURE_DAYS - ESTABLISHED_FROM).toFloat()).coerceAtMost(1f)
    }

    fun stageLabel(dayN: Int): String = when {
        dayN <= 0               -> "Still calibrating · no full day of data yet"
        dayN < PROVISIONAL_FROM -> "Still calibrating · Day $dayN of $PROVISIONAL_FROM"
        dayN < ESTABLISHED_FROM -> "Provisional baseline · Day $dayN of $ESTABLISHED_FROM"
        dayN < MATURE_DAYS      -> "Baseline established · refining until day $MATURE_DAYS"
        else                    -> "Baseline mature"
    }

    /** Shown in place of a verdict while calibrating. */
    fun missingLabel(dayN: Int): String =
        if (dayN <= 0) "Still calibrating · no full day of data yet"
        else "Still calibrating · Day $dayN of $PROVISIONAL_FROM"
}
