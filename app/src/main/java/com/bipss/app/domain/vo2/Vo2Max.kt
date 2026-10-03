package com.bipss.app.domain.vo2

import com.bipss.app.domain.bioage.BioSex
import kotlin.math.sqrt

/**
 * VO2 max from what the band measures, in two parts that are then combined.
 *
 * Passive: the Jackson et al. (1990) non-exercise equation, with its activity
 * rating taken from measured minutes in the heart-rate zones and measured
 * steps instead of a questionnaire answer, blended (under 50) with the
 * heart-rate-ratio estimate of Uth et al. (2004) from sleeping resting heart
 * rate. Typical error of the equation: about 5.6 mL/kg/min.
 *
 * Active: when a sustained, steady walk or run is found, the ACSM metabolic
 * equations turn its speed into oxygen cost, and the heart-rate reserve it
 * took (percent HRR is about percent VO2 reserve, Swain 1997) scales that up
 * to a maximum. Speed comes from phone GPS if the workout was recorded with
 * it, otherwise from step cadence and height (walking only; cadence cannot
 * give running speed).
 *
 * See docs/research/bioage-references.md.
 */
object Vo2Max {
    const val JACKSON_SEE = 5.6
    const val VALID_MIN = 10.0
    const val VALID_MAX = 80.0

    data class Passive(val value: Double, val sigma: Double, val blended: Boolean)

    /** A steady stretch of walking or running. */
    data class Segment(
        val startMs: Long,
        val minutes: Int,
        val meanHr: Double,
        /** Metres per second. */
        val speedMps: Double,
        val fromGps: Boolean
    )

    data class Estimate(
        val value: Double,
        val sigma: Double,
        val passive: Double?,
        val active: Double?,
        val activeSegments: Int
    )

    /**
     * The 0-7 activity rating of Jackson's equation, from measured weekly
     * minutes. Vigorous is 60 percent of heart-rate reserve and above,
     * moderate is 40 to 60 percent.
     */
    fun activityRating(moderateWeeklyMin: Double, vigorousWeeklyMin: Double, dailySteps: Double?): Int = when {
        vigorousWeeklyMin >= 180 -> 7
        vigorousWeeklyMin >= 60 -> 6
        vigorousWeeklyMin >= 30 -> 5
        vigorousWeeklyMin >= 10 -> 4
        moderateWeeklyMin >= 60 -> 3
        moderateWeeklyMin >= 10 -> 2
        (dailySteps ?: 0.0) >= 3_000 -> 1
        else -> 0
    }

    fun jackson(age: Int, bmi: Double, rating: Int, sex: BioSex): Double {
        val male = when (sex) { BioSex.MALE -> 10.987; BioSex.FEMALE -> 0.0; BioSex.OTHER -> 10.987 / 2 }
        return 56.363 + 1.921 * rating - 0.381 * age - 0.754 * bmi + male
    }

    /** Uth et al.: VO2 max = 15.3 x HRmax / HRrest. */
    fun uth(hrMax: Double, hrRest: Double): Double = 15.3 * hrMax / hrRest

    fun passive(
        age: Int, sex: BioSex, heightCm: Double, weightKg: Double,
        rating: Int, hrMax: Double, hrRest: Double?
    ): Passive? {
        if (age !in 20..70 || heightCm !in 120.0..230.0 || weightKg !in 30.0..250.0) return null
        val bmi = weightKg / ((heightCm / 100) * (heightCm / 100))
        if (bmi !in 15.0..45.0) return null
        val base = jackson(age, bmi, rating, sex)
        if (base !in VALID_MIN..VALID_MAX) return null
        // The ratio method was built on men aged 21-51; it only tempers the equation, and only up to fifty.
        val ratio = if (age <= 50 && hrRest != null && hrRest in 35.0..100.0 && hrMax > hrRest) uth(hrMax, hrRest) else null
        if (ratio == null || ratio !in VALID_MIN..VALID_MAX) return Passive(base, JACKSON_SEE, false)
        return Passive(0.7 * base + 0.3 * ratio, 5.0, true)
    }

    // -- active --------------------------------------------------------------------------

    /** Metres per step while walking, from height (about 0.414 of it). */
    fun walkingStepLengthM(heightCm: Double) = 0.414 * heightCm / 100.0

    /** ACSM, level ground: walking (50-100 m/min) and running (above 134 m/min). Null in between. */
    fun oxygenCost(speedMps: Double): Double? {
        val mPerMin = speedMps * 60.0
        return when {
            mPerMin in 50.0..100.0 -> 0.1 * mPerMin + 3.5
            mPerMin > 134.0 -> 0.2 * mPerMin + 3.5
            else -> null
        }
    }

    /**
     * One segment's VO2 max, or null when it cannot be trusted: too easy
     * (below 40 percent of reserve for a walk, 50 for a run, where the
     * extrapolation to a maximum is too steep), too hard to be steady
     * (above 95), or outside the speeds the equations cover.
     */
    fun fromSegment(s: Segment, hrRest: Double, hrMax: Double): Double? {
        if (hrMax <= hrRest + 20 || s.minutes < 8) return null
        val cost = oxygenCost(s.speedMps) ?: return null
        val running = s.speedMps * 60.0 > 134.0
        val hrr = (s.meanHr - hrRest) / (hrMax - hrRest)
        if (hrr !in (if (running) 0.5 else 0.4)..0.95) return null
        val vo2 = 3.5 + (cost - 3.5) / hrr
        return vo2.takeIf { it in VALID_MIN..VALID_MAX }
    }

    /** Relative error of one segment: GPS speed is better than cadence-derived speed. */
    fun relativeError(s: Segment) = if (s.fromGps) 0.12 else 0.22

    /** Each usable segment as (VO2 max, relative error). */
    fun segmentEstimates(segments: List<Segment>, hrRest: Double?, hrMax: Double): List<Pair<Double, Double>> {
        if (hrRest == null) return emptyList()
        return segments.mapNotNull { s -> fromSegment(s, hrRest, hrMax)?.let { it to relativeError(s) } }
    }

    /**
     * Combines the passive estimate with the active ones from the last weeks:
     * the active ones are averaged (extremes trimmed) and the two are
     * weighted by the inverse of their variances.
     */
    fun combine(passive: Passive?, actives: List<Pair<Double, Double>>): Estimate? {
        var active: Double? = null
        var activeSigma = Double.MAX_VALUE
        if (actives.isNotEmpty()) {
            val sorted = actives.sortedBy { it.first }
            val kept = if (sorted.size >= 5) sorted.drop(1).dropLast(1) else sorted
            val mean = kept.map { it.first }.average()
            active = mean
            activeSigma = mean * kept.map { it.second }.average() / sqrt(minOf(kept.size, 4).toDouble())
        }
        if (passive == null && active == null) return null
        if (passive == null) return active?.let { Estimate(it, activeSigma, null, it, actives.size) }
        if (active == null) return Estimate(passive.value, passive.sigma, passive.value, null, 0)
        val wp = 1.0 / (passive.sigma * passive.sigma)
        val wa = 1.0 / (activeSigma * activeSigma)
        val value = (passive.value * wp + active * wa) / (wp + wa)
        return Estimate(value, sqrt(1.0 / (wp + wa)), passive.value, active, actives.size)
    }
}
