package com.bipss.app.ble

import com.bipss.app.ble.protocol.BandProtocol
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Maps the band's clocks onto phone wall time for one connection.
 *
 * The band has two clocks. Its uptime (ms since boot) timestamps the 1 Hz
 * status packet. Its PPG sample index counts MAX30102 output samples, whose
 * rate comes from the sensor's own oscillator - nominally 50 Hz, but not
 * exactly, and not the same as the MCU clock. Every status packet pairs the two
 * (uptime, index), so the real sample period is fitted from those pairs rather
 * than assumed: an interbeat interval computed with a 2 % wrong period is a 2 %
 * wrong heart rate and a wrong PRV.
 *
 * Wall time: arrival time is always the true sample time plus a transport delay
 * that is never negative, so the smallest (arrival - uptime) seen recently is
 * the best estimate of the offset. A sliding window, rather than the all-time
 * minimum, lets the estimate follow the slow drift between the two crystals.
 *
 * Pure Kotlin, not thread-safe: the BLE manager feeds it from one thread.
 */
class BandClock(
    private val offsetWindowMs: Long = OFFSET_WINDOW_MS,
    private val fitWindow: Int = FIT_WINDOW
) {
    // (arrival, candidate offset) with candidates increasing front to back:
    // a monotonic deque, so the window minimum is always at the front.
    private val offsets = ArrayDeque<Pair<Long, Long>>()

    // (unwrapped sample index, uptime ms) pairs for the period fit.
    private val fitPoints = ArrayDeque<Pair<Long, Long>>()

    private var lastUptimeRaw: Long? = null
    private var uptimeWraps = 0L
    private var lastUnwrappedIndex: Long? = null
    private var lastStatusIndex: Long? = null
    private var lastStatusUptime: Long? = null

    private var fit: Fit? = null

    /** True once the fitted period is available and within tolerance of nominal. */
    val isPeriodFitted: Boolean get() = fit != null

    /** Fitted ms per PPG sample, or the nominal period until there is enough data to fit. */
    val samplePeriodMs: Double get() = fit?.slope ?: BandProtocol.NOMINAL_PPG_PERIOD_MS

    /** Set when the last fit came out implausibly far from nominal and was rejected. */
    var lastFitRejected: Boolean = false
        private set

    /**
     * Feeds one status packet. Returns the packet's wall-clock time, or null if
     * it signals a band reboot (uptime ran backwards), in which case the clock
     * has been reset and the next packet starts a fresh mapping.
     */
    fun onStatus(uptimeMsRaw: Long, rawSampleIndex: Int, arrivalMs: Long): Long? {
        val uptime = unwrapUptime(uptimeMsRaw) ?: return null
        pushOffset(arrivalMs, arrivalMs - uptime)

        val index = unwrapStatusIndex(rawSampleIndex, uptime)
        lastStatusIndex = index
        lastStatusUptime = uptime
        fitPoints.addLast(index to uptime)
        while (fitPoints.size > fitWindow) fitPoints.removeFirst()
        refit()

        return uptime + currentOffset()
    }

    /**
     * Wall time for a packet that carries uptime but no sample index - the v1
     * legacy frame. Same offset filter and reboot handling as [onStatus].
     */
    fun onUptimeOnly(uptimeMsRaw: Long, arrivalMs: Long): Long? {
        val uptime = unwrapUptime(uptimeMsRaw) ?: return null
        pushOffset(arrivalMs, arrivalMs - uptime)
        return uptime + currentOffset()
    }

    /** Unwraps a 16-bit waveform index against the most recent index seen from either packet. */
    fun unwrapWaveformIndex(raw: Int): Long {
        val last = lastUnwrappedIndex
        if (last == null) {
            lastUnwrappedIndex = raw.toLong()
            return raw.toLong()
        }
        val result = nearestCongruent(raw, last.toDouble())
        if (result > last) lastUnwrappedIndex = result
        return result
    }

    /** Wall-clock time of an unwrapped PPG sample index, or null before the first status packet. */
    fun sampleWallTimeMs(index: Long): Long? {
        val offset = offsets.firstOrNull()?.second ?: return null
        val refIndex = lastStatusIndex ?: return null
        val refUptime = lastStatusUptime ?: return null
        val f = fit
        val uptime = if (f != null) {
            f.intercept + f.slope * (index - f.xRef)
        } else {
            refUptime + (index - refIndex) * BandProtocol.NOMINAL_PPG_PERIOD_MS
        }
        return uptime.roundToLong() + offset
    }

    /** Forget everything - a new connection may be talking to a band that rebooted. */
    fun reset() {
        offsets.clear()
        fitPoints.clear()
        lastUptimeRaw = null
        uptimeWraps = 0L
        lastUnwrappedIndex = null
        lastStatusIndex = null
        lastStatusUptime = null
        fit = null
        lastFitRejected = false
    }

    // -- internals ---------------------------------------------------------------

    private fun unwrapUptime(raw: Long): Long? {
        val last = lastUptimeRaw
        if (last != null && raw < last) {
            // A u32 wrap (after ~49.7 days) looks like a huge backwards jump from
            // near the top of the range to near zero; anything else backwards is
            // a reboot, and every mapping built so far is void.
            if (last - raw > UPTIME_MODULO / 2) {
                uptimeWraps++
            } else {
                reset()
                return null
            }
        }
        lastUptimeRaw = raw
        return raw + uptimeWraps * UPTIME_MODULO
    }

    /**
     * Status packets arrive about a second apart, but a dropped run of them
     * could hide a whole 16-bit wrap. Predicting the index from the uptime that
     * elapsed makes the unwrap correct for any gap shorter than ~10 minutes of
     * *drift*, rather than ~10 minutes of *time*.
     */
    private fun unwrapStatusIndex(raw: Int, uptime: Long): Long {
        val prevIndex = lastStatusIndex
        val prevUptime = lastStatusUptime
        val expected = if (prevIndex != null && prevUptime != null) {
            prevIndex + (uptime - prevUptime) / samplePeriodMs
        } else {
            lastUnwrappedIndex?.toDouble() ?: raw.toDouble()
        }
        val result = nearestCongruent(raw, expected)
        if (lastUnwrappedIndex == null || result > lastUnwrappedIndex!!) lastUnwrappedIndex = result
        return result
    }

    private fun nearestCongruent(raw: Int, expected: Double): Long {
        val m = BandProtocol.SAMPLE_INDEX_MODULO.toLong()
        val base = Math.floorDiv(expected.roundToLong(), m) * m + raw
        // One of base-m, base, base+m is within half a period of expected.
        return listOf(base - m, base, base + m).minBy { abs(it - expected) }
    }

    private fun pushOffset(arrivalMs: Long, candidate: Long) {
        while (offsets.isNotEmpty() && offsets.last().second >= candidate) offsets.removeLast()
        offsets.addLast(arrivalMs to candidate)
        while (offsets.isNotEmpty() && offsets.first().first < arrivalMs - offsetWindowMs) offsets.removeFirst()
    }

    private fun currentOffset(): Long = offsets.first().second

    /** Least-squares uptime-vs-index line over the window. */
    private fun refit() {
        if (fitPoints.size < MIN_FIT_POINTS) return
        val xRef = fitPoints.first().first
        val span = fitPoints.last().first - xRef
        if (span * BandProtocol.NOMINAL_PPG_PERIOD_MS < MIN_FIT_SPAN_MS) return

        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        val yRef = fitPoints.first().second
        for ((x, y) in fitPoints) {
            val dx = (x - xRef).toDouble()
            val dy = (y - yRef).toDouble()
            sx += dx; sy += dy; sxx += dx * dx; sxy += dx * dy
        }
        val n = fitPoints.size
        val denom = n * sxx - sx * sx
        if (denom == 0.0) return
        val slope = (n * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / n + yRef

        val deviation = abs(slope - BandProtocol.NOMINAL_PPG_PERIOD_MS) / BandProtocol.NOMINAL_PPG_PERIOD_MS
        if (deviation > MAX_PERIOD_DEVIATION) {
            // Far outside any oscillator tolerance: the pairs are wrong (a
            // firmware bug, or samples lost without the index advancing), and
            // fitting to them would bend every interval. Keep the previous fit.
            lastFitRejected = true
            return
        }
        lastFitRejected = false
        fit = Fit(xRef = xRef, intercept = intercept, slope = slope)
    }

    private data class Fit(val xRef: Long, val intercept: Double, val slope: Double)

    companion object {
        const val OFFSET_WINDOW_MS = 5 * 60_000L
        /** About two minutes of status packets. */
        const val FIT_WINDOW = 120
        const val MIN_FIT_POINTS = 10
        const val MIN_FIT_SPAN_MS = 10_000.0
        /**
         * The MAX30102 datasheet does not specify its sample-rate oscillator
         * tightly; 10 % admits any plausible part while rejecting nonsense pairs.
         */
        const val MAX_PERIOD_DEVIATION = 0.10
        private const val UPTIME_MODULO = 1L shl 32
    }
}
