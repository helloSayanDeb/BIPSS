package com.bipss.app.domain.workout

import com.bipss.app.domain.strain.StrainMinute
import com.bipss.app.domain.strain.Trimp
import com.bipss.app.domain.strain.TrimpSex
import com.bipss.app.domain.vo2.Vo2Max
import kotlin.math.abs
import kotlin.math.sqrt

/** One minute of the band's output as the detector sees it. */
data class WorkMinute(
    val startMs: Long,
    /** Mean measured heart rate over the minute; null when none was measured. */
    val hr: Double?,
    /** Band movement over the minute, mg*s. */
    val activityMgS: Double?,
    /** Steps counted in the minute; null when the band sent no step data. */
    val steps: Int? = null,
    /** Phone GPS speed, m/s, when a recorded workout had it. */
    val gpsSpeedMps: Double? = null
)

data class Effort(
    val startMs: Long,
    val endMs: Long,
    /** Wall-clock minutes, gaps included. */
    val minutes: Int,
    val meanHr: Double,
    val maxHr: Double,
    val meanHrr: Double,
    val trimp: Double,
    val cadence: Double?,
    /** WALK, RUN or WORKOUT. */
    val guessedType: String
)

/**
 * Finds sustained efforts in minute data: movement (the same gate Strain
 * uses) together with a heart rate at least a third of the way up its
 * reserve, for ten minutes or more, bridging pauses of up to two minutes.
 * It names what the data looks like; the user decides what it counts as.
 * Cycling cannot be told from other efforts on a wrist, so it is left for
 * the user to say.
 */
object WorkoutDetector {
    const val MIN_EFFORT_MINUTES = 10
    const val MAX_GAP_MINUTES = 2
    const val MIN_HRR = 0.35
    private const val MINUTE = 60_000L

    private fun hrr(hr: Double, rest: Double, max: Double) = (hr - rest) / (max - rest)

    private fun isEffort(m: WorkMinute, rest: Double, max: Double): Boolean {
        val hr = m.hr ?: return false
        return (m.activityMgS ?: 0.0) >= Trimp.ACTIVE_MG_S_PER_MIN && hrr(hr, rest, max) >= MIN_HRR
    }

    fun detect(minutes: List<WorkMinute>, hrRest: Double, hrMax: Double, sex: TrimpSex): List<Effort> {
        if (hrMax <= hrRest + 20) return emptyList()
        val sorted = minutes.sortedBy { it.startMs }
        val result = ArrayList<Effort>()
        var run = ArrayList<WorkMinute>()
        var gap = 0
        var lastStart = Long.MIN_VALUE

        fun close() {
            val effort = run.filter { isEffort(it, hrRest, hrMax) }
            if (effort.size >= MIN_EFFORT_MINUTES) result += build(run, effort, hrRest, hrMax, sex)
            run = ArrayList(); gap = 0
        }

        for (m in sorted) {
            if (lastStart != Long.MIN_VALUE && m.startMs - lastStart > MINUTE * (MAX_GAP_MINUTES + 1)) close()
            lastStart = m.startMs
            if (isEffort(m, hrRest, hrMax)) { run += m; gap = 0 }
            else if (run.isNotEmpty()) { gap++; if (gap > MAX_GAP_MINUTES) close() else run += m }
        }
        close()
        return result
    }

    private fun build(run: List<WorkMinute>, effort: List<WorkMinute>, rest: Double, max: Double, sex: TrimpSex): Effort {
        val hrs = effort.mapNotNull { it.hr }
        val meanHr = hrs.average()
        val trimp = Trimp.compute(
            effort.map { StrainMinute(it.startMs, it.hr, it.activityMgS) }, rest, max, sex
        ).trimp
        val stepMinutes = effort.mapNotNull { it.steps }
        val cadence = stepMinutes.takeIf { it.size >= effort.size * 0.6 }?.average()
        val type = when {
            cadence == null || cadence < 80 -> "WORKOUT"
            cadence >= 150 -> "RUN"
            else -> "WALK"
        }
        val first = run.first().startMs
        val last = run.last().startMs + MINUTE
        return Effort(
            startMs = first, endMs = last, minutes = ((last - first) / MINUTE).toInt(),
            meanHr = meanHr, maxHr = hrs.max(), meanHrr = hrr(meanHr, rest, max).coerceIn(0.0, 1.0),
            trimp = trimp, cadence = cadence, guessedType = type
        )
    }

    // -- steady stretches for the VO2 max estimate -----------------------------------------

    private const val SEGMENT_MINUTES = 8
    private val WALKING_CADENCE = 85.0..135.0

    private fun cv(values: List<Double>): Double {
        val mean = values.average()
        if (mean <= 0.0) return Double.MAX_VALUE
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size) / mean
    }

    private fun slopePerMinute(values: List<Double>): Double {
        val n = values.size
        val xMean = (n - 1) / 2.0
        val yMean = values.average()
        val num = values.indices.sumOf { (it - xMean) * (values[it] - yMean) }
        val den = values.indices.sumOf { (it - xMean) * (it - xMean) }
        return if (den == 0.0) 0.0 else num / den
    }

    /**
     * Steady walking or running: eight consecutive minutes, all with heart
     * rate and either GPS speed or a step count, with cadence varying by
     * under 12 percent, heart rate by under 8 percent and drifting by under
     * 2 bpm a minute. Cadence-derived speed is for walking only.
     */
    fun steadySegments(minutes: List<WorkMinute>, heightCm: Double): List<Vo2Max.Segment> {
        val sorted = minutes.sortedBy { it.startMs }
        val out = ArrayList<Vo2Max.Segment>()
        var i = 0
        while (i + SEGMENT_MINUTES <= sorted.size) {
            val w = sorted.subList(i, i + SEGMENT_MINUTES)
            val contiguous = w.last().startMs - w.first().startMs == (SEGMENT_MINUTES - 1) * MINUTE
            val moving = w.all { (it.activityMgS ?: 0.0) >= Trimp.ACTIVE_MG_S_PER_MIN && it.hr != null }
            val seg = if (contiguous && moving) segmentOf(w, heightCm) else null
            if (seg != null) { out += seg; i += SEGMENT_MINUTES } else i++
        }
        return out
    }

    private fun segmentOf(w: List<WorkMinute>, heightCm: Double): Vo2Max.Segment? {
        val hrs = w.mapNotNull { it.hr }
        if (hrs.size != w.size || cv(hrs) > 0.08 || abs(slopePerMinute(hrs)) > 2.0) return null
        val gps = w.mapNotNull { it.gpsSpeedMps }
        if (gps.size >= (w.size * 0.8).toInt() && gps.size == w.size) {
            if (cv(gps) > 0.15) return null
            return Vo2Max.Segment(w.first().startMs, w.size, hrs.average(), gps.average(), true)
        }
        val steps = w.mapNotNull { it.steps?.toDouble() }
        if (steps.size != w.size || cv(steps) > 0.12) return null
        val cadence = steps.average()
        if (cadence !in WALKING_CADENCE) return null
        val speed = cadence / 60.0 * Vo2Max.walkingStepLengthM(heightCm)
        return Vo2Max.Segment(w.first().startMs, w.size, hrs.average(), speed, false)
    }
}
