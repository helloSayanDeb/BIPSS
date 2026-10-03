package com.bipss.app.domain.bioage

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * "Effective age" from daily habits and fitness, after the approach in
 * WHOOP's Healthspan white paper (Lee et al., 2025), which rests on a
 * Gompertz mortality curve: all-cause mortality rises by about 10 percent for
 * each year of age, so a contributor whose mortality hazard ratio against a
 * healthy reference is HR is worth ln(HR) / 0.1 years.
 *
 * What is borrowed: the nine contributors, their neutral points (7 h sleep,
 * 70 percent sleep consistency, 8 000 steps, 100 min a week in zones 1-3, 10
 * min in zones 4-5, 40 min of strength, the VO2 max table, resting heart rate
 * near 60 / 64 bpm, lean-mass thresholds), the six-month and 30-day windows,
 * and Pace of Aging. What is not: WHOOP's hazard curves and its correlation
 * correction are unpublished, so the dose-response shapes below are
 * documented design choices anchored on published effect sizes, and SHRINK
 * stands in for the correction (see docs/research/bioage-references.md).
 *
 * This is a lifestyle estimate, not a medical biological age.
 */
object BioAge {
    /** ln of the yearly mortality hazard multiplier (about +10 percent a year). */
    const val LOG_HAZARD_PER_YEAR = 0.1

    /**
     * Scales every contributor's years. The contributors overlap (resting
     * heart rate, VO2 max and activity all move together), so adding their
     * full effects would count the same fitness several times.
     */
    const val SHRINK = 0.5

    /** No single contributor moves the age by more than this. */
    const val CONTRIBUTOR_CAP_YEARS = 4.0
    const val VO2_CAP_YEARS = 5.0

    const val LONG_WINDOW_DAYS = 180
    const val SHORT_WINDOW_DAYS = 30

    /** A day counts for steps and zone minutes only when the band measured at least ten hours of heart rate. */
    const val MIN_VALID_MINUTES = 600
    const val MIN_DAYS_PROVISIONAL = 14
    const val MIN_DAYS_ESTABLISHED = 30
    const val MIN_NIGHTS = 5
    const val MIN_CONTRIBUTORS = 3

    private fun years(hazardRatio: Double, cap: Double = CONTRIBUTOR_CAP_YEARS): Double =
        (SHRINK * ln(hazardRatio) / LOG_HAZARD_PER_YEAR).coerceIn(-cap, cap)

    /** Seven hours is neutral; each hour short (to three) is +12 percent; seven to nine hours a little under 1; beyond nine nothing. */
    fun sleepDurationYears(hours: Double): Double = when {
        hours < 7.0 -> years(exp(ln(1.12) * min(7.0 - hours, 3.0)))
        hours <= 9.0 -> years(0.97)
        else -> 0.0
    }

    /** Neutral at 70 percent; 100 percent is a 20 percent lower hazard, 40 percent a 25 percent higher one. */
    fun sleepConsistencyYears(score: Double): Double =
        years(exp(ln(0.8) * (score.coerceIn(0.0, 100.0) - 70.0) / 30.0))

    fun restingHrNeutral(sex: BioSex) = when (sex) { BioSex.MALE -> 60.0; BioSex.FEMALE -> 64.0; BioSex.OTHER -> 62.0 }

    /** About 9 percent more mortality for each 10 bpm. */
    fun restingHrYears(bpm: Double, sex: BioSex): Double =
        years(exp(ln(1.09) * (bpm - restingHrNeutral(sex)) / 10.0))

    /** Fewer steps are needed from sixty. */
    fun stepsNeutral(age: Int) = if (age >= 60) 6_000.0 else 8_000.0

    /** About 14 percent per 1 000 steps below the neutral point (down to 4 000 below); a small credit for up to 2 000 above. */
    fun stepsYears(steps: Double, age: Int): Double {
        val neutral = stepsNeutral(age)
        return if (steps < neutral) years(exp(0.15 * min((neutral - steps) / 1000.0, 4.0)))
        else years(exp(-0.05 * min((steps - neutral) / 1000.0, 2.0)))
    }

    /** 100 minutes a week to age 40, easing to 70 (guidelines favour a little less with age). */
    fun zone13Neutral(age: Int) = (100.0 - 0.5 * (age - 40).coerceAtLeast(0)).coerceAtLeast(70.0)

    fun zone13Years(weekly: Double, age: Int): Double {
        val n = zone13Neutral(age)
        val w = weekly.coerceIn(0.0, 600.0)
        val hr = if (w < n) 1.0 + 0.30 * (n - w) / n else 1.0 - 0.28 * (w - n) / (600.0 - n)
        return years(hr)
    }

    /** Neutral at 10 minutes a week; the benefit flattens at 25. */
    fun zone45Years(weekly: Double): Double =
        years(exp(0.01823 * (10.0 - weekly.coerceIn(0.0, 25.0))))

    /** Neutral at 40 minutes; the benefit flattens at 120 (more is not better). */
    fun strengthYears(weekly: Double): Double {
        val w = weekly.coerceIn(0.0, 120.0)
        val hr = if (w < 40.0) 1.0 + 0.25 * (40.0 - w) / 40.0 else 1.0 - 0.10 * (w - 40.0) / 80.0
        return years(hr)
    }

    private val VO2_AGES = (20..100 step 5).toList()
    private val VO2_FEMALE = listOf(40, 40, 38, 36, 34, 32, 30, 29, 27, 26, 25, 23, 22, 21, 20, 19, 18)
    private val VO2_MALE = listOf(46, 46, 44, 42, 40, 37, 36, 34, 32, 30, 29, 27, 26, 25, 23, 22, 21)

    /** WHOOP's minimum-recommended VO2 max by age and sex, interpolated between its five-year steps. */
    fun vo2Target(age: Int, sex: BioSex): Double {
        fun at(table: List<Int>): Double {
            val a = age.coerceIn(20, 100)
            val i = ((a - 20) / 5).coerceAtMost(VO2_AGES.size - 2)
            val f = (a - VO2_AGES[i]) / 5.0
            return table[i] + (table[i + 1] - table[i]) * f
        }
        return when (sex) {
            BioSex.MALE -> at(VO2_MALE)
            BioSex.FEMALE -> at(VO2_FEMALE)
            BioSex.OTHER -> (at(VO2_MALE) + at(VO2_FEMALE)) / 2
        }
    }

    /** 13 percent lower mortality for each MET (3.5 mL/kg/min) above the target. */
    fun vo2Years(vo2: Double, age: Int, sex: BioSex): Double =
        years(exp(ln(1.13) * (vo2Target(age, sex) - vo2) / 3.5), VO2_CAP_YEARS)

    /** WHOOP: at least 67 percent lean for a 30-year-old woman, 80 for a man; here 0.15 points less per year of age. */
    fun leanMassThreshold(age: Int, sex: BioSex): Double {
        val base = when (sex) { BioSex.MALE -> 80.0; BioSex.FEMALE -> 67.0; BioSex.OTHER -> 73.5 }
        return base - 0.15 * (age - 30)
    }

    /** 11 percent more mortality for each 10 points of body fat (10 points less lean); half that credit above the threshold. */
    fun leanMassYears(pct: Double, age: Int, sex: BioSex): Double {
        val gap = leanMassThreshold(age, sex) - pct
        val full = years(exp(ln(1.11) * gap / 10.0))
        return if (gap > 0) full else (full * 0.5).coerceAtLeast(-1.0)
    }

    // -- sleep consistency ---------------------------------------------------------------

    private fun circularMeanMin(values: List<Int>): Double {
        val angles = values.map { it / 1440.0 * 2 * Math.PI }
        val a = Math.atan2(angles.sumOf { Math.sin(it) }, angles.sumOf { Math.cos(it) })
        return ((a / (2 * Math.PI) * 1440.0) + 1440.0) % 1440.0
    }

    private fun circularGap(a: Double, b: Double): Double {
        val d = abs(a - b) % 1440.0
        return min(d, 1440.0 - d)
    }

    /**
     * 0-100: how close each night's bed and wake times are to the average of
     * the four nights before it (WHOOP compares the last night with the
     * previous four). No gap is 100; a gap of two and a half hours or more is
     * 0. Null with fewer than MIN_NIGHTS scored nights.
     */
    fun sleepConsistency(nights: List<DayStats>): Double? {
        val sorted = nights.filter { it.sleepStartMin != null && it.sleepEndMin != null }.sortedBy { it.epochDay }
        val scores = ArrayList<Double>()
        for (i in 1 until sorted.size) {
            val prior = sorted.subList(maxOf(0, i - 4), i)
            val bed = circularMeanMin(prior.map { it.sleepStartMin ?: 0 })
            val wake = circularMeanMin(prior.map { it.sleepEndMin ?: 0 })
            val gap = (circularGap((sorted[i].sleepStartMin ?: 0).toDouble(), bed) +
                circularGap((sorted[i].sleepEndMin ?: 0).toDouble(), wake)) / 2
            scores += 100.0 * (1.0 - (gap / 150.0).coerceIn(0.0, 1.0))
        }
        return scores.takeIf { it.size >= MIN_NIGHTS }?.average()
    }

    // -- aggregation ---------------------------------------------------------------------

    fun aggregate(days: List<DayStats>): Aggregates {
        val worn = days.filter { it.validMinutes >= MIN_VALID_MINUTES }
        val nights = days.filter { (it.sleepMinutes ?: 0) >= 120 }
        val rhr = days.mapNotNull { it.restingHr }
        val steps = worn.mapNotNull { it.steps }
        fun weekly(f: (DayStats) -> Int) =
            worn.takeIf { it.size >= MIN_DAYS_PROVISIONAL }?.let { w -> w.sumOf(f).toDouble() / w.size * 7 }
        return Aggregates(
            sleepHours = nights.takeIf { it.size >= MIN_NIGHTS }?.let { n -> n.sumOf { it.sleepMinutes ?: 0 } / 60.0 / n.size },
            consistency = sleepConsistency(nights),
            restingHr = rhr.takeIf { it.size >= MIN_NIGHTS }?.average(),
            steps = steps.takeIf { it.size >= 7 }?.average(),
            zone13Weekly = weekly { it.zone13 },
            zone45Weekly = weekly { it.zoneMin45 },
            strengthWeekly = weekly { it.strengthMin },
            strengthEver = days.any { it.strengthMin > 0 },
            validDays = worn.size
        )
    }

    private fun contributors(a: Aggregates, age: Int, sex: BioSex, vo2: Double?, lean: Double?): List<ContributorResult> {
        fun used(c: Contributor, value: Double?, target: Double, years: (Double) -> Double) =
            if (value == null) ContributorResult(c, null, null, target, Skipped.NO_DATA)
            else ContributorResult(c, years(value), value, target)
        return listOf(
            used(Contributor.SLEEP_DURATION, a.sleepHours, 7.0, ::sleepDurationYears),
            used(Contributor.SLEEP_CONSISTENCY, a.consistency, 70.0, ::sleepConsistencyYears),
            used(Contributor.RESTING_HR, a.restingHr, restingHrNeutral(sex)) { restingHrYears(it, sex) },
            used(Contributor.STEPS, a.steps, stepsNeutral(age)) { stepsYears(it, age) },
            used(Contributor.ZONES_1_3, a.zone13Weekly, zone13Neutral(age)) { zone13Years(it, age) },
            used(Contributor.ZONES_4_5, a.zone45Weekly, 10.0, ::zone45Years),
            // Strength only counts for someone who logs it: never logging it is not the same as never doing it.
            if (!a.strengthEver) ContributorResult(Contributor.STRENGTH, null, null, 40.0, Skipped.NOT_ENTERED)
            else used(Contributor.STRENGTH, a.strengthWeekly, 40.0, ::strengthYears),
            used(Contributor.VO2_MAX, vo2, vo2Target(age, sex)) { vo2Years(it, age, sex) },
            if (lean == null) ContributorResult(Contributor.LEAN_MASS, null, null, leanMassThreshold(age, sex), Skipped.NOT_ENTERED)
            else ContributorResult(Contributor.LEAN_MASS, leanMassYears(lean, age, sex), lean, leanMassThreshold(age, sex))
        )
    }

    private fun window(days: List<DayStats>, endDay: Long, length: Int) =
        days.filter { it.epochDay in (endDay - length + 1)..endDay }

    fun compute(input: BioAgeInput): BioAgeResult {
        val days = window(input.days, input.windowEndDay, LONG_WINDOW_DAYS)
        val agg = aggregate(days)
        val list = contributors(agg, input.age, input.sex, input.vo2Max, input.leanMassPct)
        val usable = list.count { it.years != null }
        val stage = when {
            agg.validDays < MIN_DAYS_PROVISIONAL || usable < MIN_CONTRIBUTORS -> BioAgeStage.CALIBRATING
            agg.validDays < MIN_DAYS_ESTABLISHED -> BioAgeStage.PROVISIONAL
            else -> BioAgeStage.ESTABLISHED
        }
        val delta = if (stage == BioAgeStage.CALIBRATING) null else list.sumOf { it.years ?: 0.0 }.coerceIn(-15.0, 15.0)
        return BioAgeResult(
            chronologicalAge = input.age,
            bioAge = delta?.let { input.age + it },
            yearsDelta = delta,
            contributors = list,
            stage = stage,
            validDays = agg.validDays,
            windowDays = days.size
        )
    }

    /**
     * Pace of Aging: if the last 30 days' habits carried on for six months,
     * how fast would the age move compared with the calendar? The six-month
     * window would then hold only those habits, so the projected age is
     * age + 0.5 + years(last 30 days), against today's age + years(six
     * months); half a year more is a pace of 1. Clamped to -1..3 like
     * WHOOP's.
     */
    fun pace(input: BioAgeInput): PaceResult? {
        val longAgg = aggregate(window(input.days, input.windowEndDay, LONG_WINDOW_DAYS))
        val shortAgg = aggregate(window(input.days, input.windowEndDay, SHORT_WINDOW_DAYS))
        if (shortAgg.validDays < MIN_DAYS_PROVISIONAL || longAgg.validDays < MIN_DAYS_ESTABLISHED) return null
        val long = contributors(longAgg, input.age, input.sex, input.vo2Max, input.leanMassPct)
            .associateBy { it.contributor }
        val short = contributors(shortAgg, input.age, input.sex, input.vo2Max, input.leanMassPct)
            .associateBy { it.contributor }
        // Only contributors both windows could score, so a thin 30-day window does not read as a change.
        val both = Contributor.entries.filter { long[it]?.years != null && short[it]?.years != null }
        if (both.size < MIN_CONTRIBUTORS) return null
        val yearsLong = both.sumOf { long[it]?.years ?: 0.0 }
        val yearsShort = both.sumOf { short[it]?.years ?: 0.0 }
        val pace = (1.0 + (yearsShort - yearsLong) / 0.5).coerceIn(-1.0, 3.0)
        val label = when {
            pace < 0.0 -> PaceLabel.REVERSING
            pace < 0.9 -> PaceLabel.SLOWING
            pace <= 1.1 -> PaceLabel.STEADY
            else -> PaceLabel.ACCELERATED
        }
        return PaceResult(pace, label)
    }
}
