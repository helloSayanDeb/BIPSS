package com.bipss.app.domain.signal

import kotlin.math.abs
import kotlin.math.sqrt

/** An interbeat interval between two consecutive detected beats of one segment. */
data class Ibi(
    /** Time of the second beat, wall ms. */
    val endMs: Long,
    val ms: Double,
    val segment: Int
)

/** Pulse rate variability over one window. */
data class PrvResult(
    /** RMSSD over real, successive interval pairs only. */
    val rmssdMs: Double?,
    val sdnnMs: Double?,
    /** Poincare SD1/SD2, computed on the gap-filled series. */
    val sd1Ms: Double?,
    val sd2Ms: Double?,
    val intervalCount: Int,
    val rmssdPairs: Int,
    /** Share of intervals replaced by their local median before SDNN/SD1/SD2. */
    val interpolatedFraction: Double,
    val lowConfidence: Boolean
)

/**
 * Time-domain PRV from wrist pulse intervals.
 *
 * Intervals more than 20 % off the median of their five neighbours are
 * artefacts or ectopic beats, not variability. They are treated differently
 * per statistic, because the two kinds of statistic fail differently:
 *  - RMSSD is a statistic of *successive differences*. It uses only pairs of
 *    adjacent real intervals; a replaced interval would contribute two
 *    invented differences.
 *  - SDNN and SD1/SD2 describe the whole series and need it continuous, so a
 *    rejected interval is replaced by its local median, and the replaced share
 *    is reported. Above 30 % the values are flagged low-confidence.
 */
object PrvCalculator {

    fun compute(ibis: List<Ibi>): PrvResult {
        val n = ibis.size
        if (n < 3) return PrvResult(null, null, null, null, n, 0, 0.0, true)

        val accepted = BooleanArray(n)
        val filled = DoubleArray(n)
        for (i in 0 until n) {
            val med = localMedian(ibis, i)
            accepted[i] = abs(ibis[i].ms - med) <= SignalConfig.IBI_MEDIAN_TOLERANCE * med
            filled[i] = if (accepted[i]) ibis[i].ms else med
        }

        var sumSq = 0.0
        var pairs = 0
        for (i in 1 until n) {
            if (accepted[i] && accepted[i - 1] && ibis[i].segment == ibis[i - 1].segment) {
                val d = ibis[i].ms - ibis[i - 1].ms
                sumSq += d * d
                pairs++
            }
        }
        val rmssd = if (pairs >= SignalConfig.MIN_RMSSD_PAIRS) sqrt(sumSq / pairs) else null

        val mean = filled.average()
        val sdnn = sqrt(filled.sumOf { (it - mean) * (it - mean) } / (n - 1))
        val diffs = (1 until n).map { filled[it] - filled[it - 1] }
        val dMean = diffs.average()
        val sdsd = sqrt(diffs.sumOf { (it - dMean) * (it - dMean) } / (diffs.size - 1).coerceAtLeast(1))
        val sd1 = sqrt(0.5) * sdsd
        val sd2 = sqrt((2 * sdnn * sdnn - 0.5 * sdsd * sdsd).coerceAtLeast(0.0))

        val interpolated = accepted.count { !it }.toDouble() / n
        return PrvResult(
            rmssdMs = rmssd,
            sdnnMs = sdnn,
            sd1Ms = sd1,
            sd2Ms = sd2,
            intervalCount = n,
            rmssdPairs = pairs,
            interpolatedFraction = interpolated,
            lowConfidence = interpolated > SignalConfig.MAX_INTERPOLATED_FRACTION || rmssd == null
        )
    }

    /** Median of up to five intervals centred on [i], within the same segment. */
    private fun localMedian(ibis: List<Ibi>, i: Int): Double {
        val seg = ibis[i].segment
        val window = (i - 2..i + 2)
            .filter { it in ibis.indices && ibis[it].segment == seg }
            .map { ibis[it].ms }
            .sorted()
        return window[window.size / 2]
    }
}
