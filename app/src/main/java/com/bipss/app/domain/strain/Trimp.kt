package com.bipss.app.domain.strain

import kotlin.math.exp
import kotlin.math.ln

/** Sex-specific weighting of Banister's TRIMP. */
enum class TrimpSex(val a: Double, val b: Double) {
    /** Banister (1991): a = 0.64, b = 1.92. */
    MALE(0.64, 1.92),
    /** Banister (1991): a = 0.86, b = 1.67. */
    FEMALE(0.86, 1.67),
    /**
     * Non-binary, prefer not to say, or not given: the mean of the two
     * published curves. A documented choice, not a published coefficient.
     */
    UNSPECIFIED(0.75, 1.795)
}

/** One minute of the day as the strain calculation sees it. */
data class StrainMinute(
    val minuteStartMs: Long,
    /** Mean measured heart rate over the minute; null when none was measured. */
    val hr: Double?,
    /** Total band activity over the minute, mg*s; null when the band sent none. */
    val activityMgS: Double?
)

data class TrimpBout(val startMs: Long, val minutes: Int, val meanHrReserve: Double, val trimp: Double)

data class StrainResult(
    /** Banister TRIMP summed over exertion bouts. */
    val trimp: Double,
    /** 0-21 display value, see [StrainScale]. */
    val display: Double,
    val bouts: List<TrimpBout>,
    /** Minutes that met the exertion (motion) gate. */
    val activeMinutes: Int,
    /** Active minutes that also had a measured heart rate - the ones that were scored. */
    val scoredMinutes: Int,
    val hrRest: Double,
    val hrMax: Double,
    val sex: TrimpSex
)

/**
 * Training impulse after Banister (1991), per contiguous exertion bout:
 *
 *     TRIMP_bout = D * HRr * a * e^(b * HRr),   HRr = (HR - HRrest) / (HRmax - HRrest)
 *
 * with D the bout's duration in minutes and HRr its mean heart-rate reserve
 * (clamped to 0..1). Only minutes that pass the motion gate count: a raised
 * heart rate while sitting still is not training load - it belongs to the
 * Autonomic Load Indicator, not here.
 *
 * HRmax is Gellish et al. (2007), 206.9 - 0.67 * age; HRrest comes from sleep
 * (see the caller). Sources: docs/research/scoring-references.md.
 */
object Trimp {

    /**
     * An exertion minute averages at least 50 mg*s of band activity per second
     * (3 000 per minute): the bottom of an easy walk. Sitting, typing and
     * sleeping stay far below it.
     */
    const val ACTIVE_MG_S_PER_MIN = 3_000.0

    fun gellishHrMax(age: Int): Double = 206.9 - 0.67 * age

    fun compute(minutes: List<StrainMinute>, hrRest: Double, hrMax: Double, sex: TrimpSex): StrainResult {
        require(hrMax > hrRest) { "HRmax must exceed HRrest" }
        val sorted = minutes.sortedBy { it.minuteStartMs }
        val bouts = ArrayList<TrimpBout>()
        var active = 0
        var scored = 0

        var boutStart = -1L
        var lastMinute = Long.MIN_VALUE
        val boutHr = ArrayList<Double>()
        var boutMinutes = 0

        fun closeBout() {
            if (boutMinutes > 0 && boutHr.isNotEmpty()) {
                val hrr = ((boutHr.average() - hrRest) / (hrMax - hrRest)).coerceIn(0.0, 1.0)
                val d = boutHr.size.toDouble()   // scored minutes only: unmeasured minutes add nothing
                bouts += TrimpBout(boutStart, boutMinutes, hrr, d * hrr * sex.a * exp(sex.b * hrr))
            }
            boutStart = -1L; boutMinutes = 0; boutHr.clear()
        }

        for (m in sorted) {
            val isActive = (m.activityMgS ?: 0.0) >= ACTIVE_MG_S_PER_MIN
            val contiguous = m.minuteStartMs - lastMinute == 60_000L
            if (!isActive || !contiguous) closeBout()
            if (isActive) {
                active++
                if (boutStart < 0) boutStart = m.minuteStartMs
                boutMinutes++
                m.hr?.let { boutHr += it; scored++ }
            }
            lastMinute = m.minuteStartMs
        }
        closeBout()

        val total = bouts.sumOf { it.trimp }
        return StrainResult(
            trimp = total, display = StrainScale.toDisplay(total), bouts = bouts,
            activeMinutes = active, scoredMinutes = scored, hrRest = hrRest, hrMax = hrMax, sex = sex
        )
    }
}

/**
 * Maps TRIMP onto the 0-21 range the app displays.
 *
 * There is no published 0-21 strain scale; this is a documented design
 * choice. It is logarithmic because training load compounds (an hour's hard
 * run is not twice a half-hour's in effect) and so that everyday activity is
 * not squeezed against zero. A TRIMP of 400 - roughly two hours at 90 % of
 * heart-rate reserve - maps to the top of the scale.
 */
object StrainScale {
    const val MAX_DISPLAY = 21.0
    const val TRIMP_AT_MAX = 400.0

    fun toDisplay(trimp: Double): Double =
        (MAX_DISPLAY * ln(1 + trimp.coerceAtLeast(0.0)) / ln(1 + TRIMP_AT_MAX)).coerceIn(0.0, MAX_DISPLAY)
}
