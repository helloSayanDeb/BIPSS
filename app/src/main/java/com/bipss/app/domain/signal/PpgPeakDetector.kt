package com.bipss.app.domain.signal

import kotlin.math.max
import kotlin.math.roundToInt

/** One detected heartbeat. */
data class Beat(
    /** Fractional sample index of the fiducial point: the steepest part of the systolic upstroke. */
    val position: Double,
    /** Upstroke slope at the fiducial, in filtered units per sample. */
    val slope: Double,
    /** True when found by search-back below the primary threshold. */
    val searchBack: Boolean,
    /** Beats in different segments are separated by lost signal; no interval may join them. */
    val segment: Int
)

/**
 * Beat detection on a cleaned PPG, after Pan & Tompkins (1985).
 *
 * The original was designed for a different cardiac signal; here the same
 * machinery finds the systolic upstrokes of a wrist PPG:
 *  1. The signal is inverted: MAX30102 raw IR falls as blood volume rises, so
 *     a pulse is a dip in the raw trace and a peak after inversion.
 *  2. Five-point derivative, rectified to positive slopes (the upstroke), squared.
 *  3. 150 ms moving-window integration: one bump per upstroke.
 *  4. Adaptive signal/noise peak levels SPKI/NPKI and the two thresholds
 *     derived from them; a missed beat is searched back for at 166 % of the
 *     running interval against the lower threshold.
 *  5. A refractory period proportional to the interval (never under 250 ms),
 *     plus a slope-ratio test: a candidate early in the cycle whose upstroke is
 *     less than half as steep as the last beat's is the dicrotic wave, not a beat.
 *
 * The fiducial is the maximum-slope sample, refined by a parabola through it
 * and its neighbours - sub-sample timing matters at 50 Hz, where one sample is
 * 20 ms and RMSSD values are tens of ms.
 */
class PpgPeakDetector {

    private var mwiSamples = 8        // recomputed from the sample period on every call
    private val sHist = ArrayDeque<Double>()
    private val sqRing = ArrayDeque<Double>()
    private var sqSum = 0.0

    /** Recent derivative values by sample index, for fiducial and search-back lookups. */
    private val dRing = ArrayDeque<Pair<Long, Double>>()

    private var mwiPrev2 = 0.0
    private var mwiPrev1 = 0.0
    private var mwiCount = 0

    private var spki = 0.0
    private var npki = 0.0
    private var learnMax = 0.0
    private var learnSum = 0.0
    private var learning = true
    private var learnSeen = 0

    private var lastBeat: Beat? = null
    /** Samples since a beat was last accepted (or since learning ended). */
    private var samplesSinceBeat = 0
    private val rrSamples = ArrayDeque<Double>()
    private val candidates = mutableListOf<Candidate>()

    var segment = 0
        private set

    private companion object { const val MAX_CANDIDATES = 64 }

    private data class Candidate(val position: Double, val slope: Double, val amplitude: Double)

    /**
     * Feeds one cleaned sample. Returns the beats confirmed by this sample - usually
     * none, sometimes one, occasionally a search-back beat followed by a new one.
     */
    fun process(index: Long, value: Double, periodMs: Double): List<Beat> {
        mwiSamples = (SignalConfig.MWI_MS / periodMs).roundToInt().coerceAtLeast(3)

        sHist.addFirst(-value)
        if (sHist.size > 5) sHist.removeLast()
        if (sHist.size < 5) return emptyList()

        // Pan-Tompkins five-point derivative; it describes the sample two back.
        val d = (2 * sHist[0] + sHist[1] - sHist[3] - 2 * sHist[4]) / 8.0
        val dIndex = index - 2
        dRing.addLast(dIndex to d)
        while (dRing.size > 200) dRing.removeFirst()

        val sq = if (d > 0) d * d else 0.0
        sqRing.addLast(sq); sqSum += sq
        while (sqRing.size > mwiSamples) sqSum -= sqRing.removeFirst()
        val mwi = sqSum / mwiSamples
        mwiCount++

        val out = ArrayList<Beat>(2)
        if (!learning && ++samplesSinceBeat * periodMs >= SignalConfig.RELEARN_AFTER_S * 1000) relearn()
        if (learning) {
            learnSeen++
            learnMax = max(learnMax, mwi)
            learnSum += mwi
            if (learnSeen * periodMs >= SignalConfig.LEARNING_S * 1000) {
                spki = learnMax / 3
                npki = learnSum / learnSeen / 2
                learning = false
                samplesSinceBeat = 0
            }
        } else if (mwiCount >= 3 && mwiPrev1 > mwiPrev2 && mwiPrev1 >= mwi) {
            // The window ending one sample ago was a local maximum.
            evaluateCandidate(dIndex - 1, mwiPrev1, periodMs)?.let(out::add)
        }
        mwiPrev2 = mwiPrev1
        mwiPrev1 = mwi

        searchBack(index, periodMs)?.let { out.add(0, it) }
        return out
    }

    private fun thresholdOne() = npki + 0.25 * (spki - npki)

    private fun evaluateCandidate(endDIndex: Long, amplitude: Double, periodMs: Double): Beat? {
        val fid = fiducial(endDIndex - mwiSamples + 1, endDIndex) ?: return null
        val cand = Candidate(fid.first, fid.second, amplitude)
        if (!passesTiming(cand, periodMs)) {
            npki = 0.125 * amplitude + 0.875 * npki
            return null
        }
        return if (amplitude > thresholdOne()) {
            spki = 0.125 * boundedAmplitude(amplitude) + 0.875 * spki
            accept(cand, searchBack = false)
        } else {
            npki = 0.125 * amplitude + 0.875 * npki
            candidates += cand
            if (candidates.size > MAX_CANDIDATES) candidates.removeAt(0)
            null
        }
    }

    /** Refractory period and the dicrotic slope-ratio test. */
    private fun passesTiming(c: Candidate, periodMs: Double): Boolean {
        val last = lastBeat ?: return true
        val sinceMs = (c.position - last.position) * periodMs
        val rrMs = rrAverageMs(periodMs)
        val refractory = if (rrMs != null) max(SignalConfig.MIN_REFRACTORY_MS, SignalConfig.REFRACTORY_RR_FRACTION * rrMs)
                         else SignalConfig.MIN_REFRACTORY_MS
        if (sinceMs < refractory) return false
        if (rrMs != null && sinceMs < SignalConfig.SLOPE_CHECK_RR_FRACTION * rrMs &&
            c.slope < SignalConfig.SLOPE_RATIO_MIN * last.slope
        ) return false
        return true
    }

    private fun searchBack(index: Long, periodMs: Double): Beat? {
        val last = lastBeat ?: return null
        val rrMs = rrAverageMs(periodMs) ?: return null
        if ((index - last.position) * periodMs < SignalConfig.SEARCH_BACK_RR * rrMs) return null
        val threshold2 = 0.5 * thresholdOne()
        val best = candidates
            .filter { it.amplitude > threshold2 && passesTiming(it, periodMs) }
            .maxByOrNull { it.amplitude } ?: return null
        spki = 0.25 * boundedAmplitude(best.amplitude) + 0.75 * spki
        return accept(best, searchBack = true)
    }

    /** One oversized peak (a knock, a re-seat) may not lift the signal level more than a few-fold. */
    private fun boundedAmplitude(a: Double) = if (spki > 0) minOf(a, SignalConfig.SPKI_MAX_STEP * spki) else a

    /**
     * No beat for a few seconds: the thresholds no longer describe this
     * signal (a transient raised them, or the pulse got weaker). Learn them
     * again from the next two seconds, as at start-up.
     */
    private fun relearn() {
        learning = true
        learnSeen = 0
        learnMax = 0.0
        learnSum = 0.0
        samplesSinceBeat = 0
        candidates.clear()
    }

    private fun accept(c: Candidate, searchBack: Boolean): Beat {
        samplesSinceBeat = 0
        val beat = Beat(c.position, c.slope, searchBack, segment)
        lastBeat?.let { prev ->
            rrSamples.addLast(c.position - prev.position)
            while (rrSamples.size > 8) rrSamples.removeFirst()
        }
        lastBeat = beat
        candidates.removeAll { it.position <= c.position }
        return beat
    }

    private fun rrAverageMs(periodMs: Double): Double? {
        if (rrSamples.size < 3) return null
        val sorted = rrSamples.sorted()
        return sorted[sorted.size / 2] * periodMs
    }

    /** Maximum of the derivative in [from, to], parabola-refined. */
    private fun fiducial(from: Long, to: Long): Pair<Double, Double>? {
        var bestI = -1
        var bestD = 0.0
        for (i in dRing.indices) {
            val (idx, d) = dRing[i]
            if (idx in from..to && (bestI < 0 || d > bestD)) { bestI = i; bestD = d }
        }
        if (bestI < 0 || bestD <= 0) return null
        val idx = dRing[bestI].first
        if (bestI == 0 || bestI == dRing.lastIndex) return idx.toDouble() to bestD
        val y0 = dRing[bestI - 1].second
        val y2 = dRing[bestI + 1].second
        val denom = y0 - 2 * bestD + y2
        val offset = if (denom != 0.0) (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5) else 0.0
        return idx + offset to bestD
    }

    /**
     * Samples were lost: forget the waveform history and the last beat, so no
     * interval is measured across the gap. Thresholds and the running
     * interval are kept - the wearer's pulse did not change during the gap.
     */
    fun resetForGap() {
        sHist.clear(); sqRing.clear(); sqSum = 0.0; dRing.clear()
        mwiPrev1 = 0.0; mwiPrev2 = 0.0; mwiCount = 0
        lastBeat = null
        candidates.clear()
        segment++
    }
}
