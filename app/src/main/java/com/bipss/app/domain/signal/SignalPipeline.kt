package com.bipss.app.domain.signal

import com.bipss.app.ble.WaveformChunk
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Heart rate for one wall-clock second, as the pipeline produced it. */
data class PulseReading(
    val timeMs: Long,
    val bpm: Double,
    /** Predicted, not measured - never stored as a reading. */
    val coasted: Boolean,
    val quality: SignalQuality
) {
    /** Whether this may be recorded as a measured heart rate. */
    val isMeasured: Boolean get() = !coasted && quality != SignalQuality.POOR
}

/** 30 seconds of pipeline output, the unit stored in signal_epochs. */
data class SignalEpoch(
    val startMs: Long,
    val durationMs: Int,
    /** Seconds of the epoch for which the pipeline evaluated heart rate (measured or not). */
    val coveredSeconds: Int,
    val meanHr: Double?,
    val minHr: Double?,
    val maxHr: Double?,
    val beatCount: Int,
    val quality: SignalQuality,
    /** PRV over the minute ending at the end of this epoch; only when that whole minute was Good. */
    val rmssdMs: Double?,
    val sdnnMs: Double?,
    val prvInterpolatedFraction: Double,
    val prvLowConfidence: Boolean,
    /** Mean band-passed accelerometer power, g^2. */
    val motionEnergy: Double,
    val coastFraction: Double
)

class PipelineOutput(
    val pulse: List<PulseReading>,
    val epochs: List<SignalEpoch>
) {
    companion object { val EMPTY = PipelineOutput(emptyList(), emptyList()) }
}

/**
 * Wrist PPG + accelerometer in, heart rate, quality and 30-second epochs out.
 *
 *     PPG ─ band-pass ─┐
 *                      ├─ NLMS (accel as reference) ─ beat detector ─ intervals ─┬─ Kalman HR (1 Hz)
 *     accel ─ band-pass┘                                                          └─ PRV (60 s)
 *
 * Intervals are measured only between consecutive beats of one segment and
 * from sample indices times the fitted sample period - never across a gap
 * and never from arrival times. One pipeline per band connection; pure Kotlin.
 */
class SignalPipeline(
    /** Off only in tests, to measure what motion cancellation is worth. */
    private val cancelMotion: Boolean = true
) {

    private val ppgFilter = BandPass()
    private val accelFilters = Array(3) { BandPass() }
    private val nlms = NlmsFilter()
    private val detector = PpgPeakDetector()
    private val kalman = HrKalman()

    private var motionEnergy = 0.0
    // Latest wall times of a sample with and without skin contact: a second
    // counts as measured only if it had contact throughout.
    private var lastContactMs = Long.MIN_VALUE
    private var lastNoContactMs = Long.MIN_VALUE
    // Consecutive intervals share a run id; anything that breaks the chain
    // of successive beats (a gap, an implausible interval) starts a new run,
    // so PRV never pairs two intervals that were not adjacent.
    private var ibiRun = 0
    /** Slowly tracked PPG DC level; null until set from a contact chunk. */
    private var dcLevel: Double? = null
    /** Contact was lost (or a sample was implausible): restart the filters on the next good sample. */
    private var restartPending = false
    /** Last valid accelerometer sample, held while the band reports none. */
    private val lastAccel = DoubleArray(3)
    private var haveAccel = false
    private var previousEpoch: SignalEpoch? = null
    private var expectedIndex: Long? = null
    private var lastBeat: Beat? = null
    private val recentIbis = ArrayDeque<Ibi>()
    private var lastBpm: Double? = null

    private var currentQuality = SignalQuality.DEGRADED
    private var window = QualityWindow()

    private var clockMs: Long? = null
    private var nextSecondMs = 0L
    private var windowEndMs = 0L
    private var epochStartMs = 0L
    private val epoch = EpochAccumulator()

    /** Wall times of recent beats (bounded), for debugging and tests. */
    val beatLog = ArrayDeque<Long>()

    /** Most recent quality verdict and its evidence, for the UI and tests. */
    var lastEvidence: QualityEvidence? = null
        private set

    fun accept(chunk: WaveformChunk): PipelineOutput {
        val firstWall = chunk.firstSampleWallMs ?: run {
            // No wall-clock anchor yet (no status packet this connection): the
            // samples cannot be placed in time, so they are skipped and the
            // next timed chunk starts a fresh segment.
            expectedIndex = null
            return PipelineOutput.EMPTY
        }
        val period = chunk.samplePeriodMs
        val pulse = ArrayList<PulseReading>(1)
        val epochs = ArrayList<SignalEpoch>(0)

        if (chunk.gapBefore || expectedIndex != chunk.firstSampleIndex) onGap()
        expectedIndex = chunk.firstSampleIndex + chunk.ppgIr.size

        for (i in chunk.ppgIr.indices) {
            val index = chunk.firstSampleIndex + i
            val tMs = firstWall + (i * period).toLong()
            advanceClock(tMs, pulse, epochs)

            val raw = chunk.ppgIr[i].toDouble()
            if (chunk.skinContact && dcLevel == null) {
                // First contact chunk after a reset: anchor on its median, so
                // an off-skin sample at its start cannot become the reference.
                dcLevel = chunk.ppgIr.sorted().let { (it[1] + it[2]) / 2.0 }
            }
            val dc = dcLevel
            val plausible = chunk.skinContact && dc != null &&
                kotlin.math.abs(raw - dc) <= SignalConfig.CONTACT_JUMP_FRACTION * dc
            if (!plausible) {
                // Off the skin, or a jump no pulse can make: never fed to the
                // filters, and the next good sample starts a new segment.
                lastNoContactMs = tMs
                restartPending = true
                if (!chunk.skinContact) dcLevel = null
                window.addSample(false, chunk.saturated, 0.0, 0.0, ZERO3, motionEnergy)
                epoch.samples++
                continue
            }
            if (restartPending) { onGap(); restartPending = false }
            dcLevel = dc!! + SignalConfig.DC_ALPHA * (raw - dc)

            lastContactMs = tMs
            val bp = ppgFilter.process(raw)
            val accel = chunk.accelG
            val j = (i / 2) * 3   // sample-and-hold: accel sample j sits against PPG sample 2j
            if (accel != null) {
                for (ax in 0..2) lastAccel[ax] = accel[j + ax].toDouble()
                haveAccel = true
            }
            // Missing accelerometer data holds the last reading - feeding 0 g
            // would be a 1 g step into the reference filters.
            val bpAccel = DoubleArray(3) { ax -> accelFilters[ax].process(lastAccel[ax]) }
            val power = bpAccel.sumOf { it * it }
            val alpha = min(1.0, period / (SignalConfig.MOTION_TAU_S * 1000))
            motionEnergy += alpha * (power - motionEnergy)

            // The canceller's output is used only while the wrist is moving:
            // at rest its reference is sensor noise, and subtracting weights
            // times noise would add noise to a clean pulse.
            val accelKnown = accel != null && haveAccel
            val moving = accelKnown && motionEnergy > SignalConfig.MOTION_FREEZE_G2
            val cleaned = if (cancelMotion) {
                val e = nlms.process(bp, bpAccel, adapt = accelKnown && motionEnergy > SignalConfig.MOTION_ADAPT_G2)
                if (moving) e else bp
            } else bp
            window.addSample(true, chunk.saturated, bp, if (cancelMotion && moving) nlms.lastArtefact else 0.0, bpAccel, motionEnergy)
            epoch.samples++
            epoch.motionSum += motionEnergy

            for (beat in detector.process(index, cleaned, period)) {
                onBeat(beat, firstWall + ((beat.position - chunk.firstSampleIndex) * period).toLong(), period)
            }
        }
        return PipelineOutput(pulse, epochs)
    }

    private fun onBeat(beat: Beat, beatWallMs: Long, periodMs: Double) {
        epoch.beats++
        beatLog.addLast(beatWallMs)
        while (beatLog.size > BEAT_LOG_SIZE) beatLog.removeFirst()
        val prev = lastBeat
        lastBeat = beat
        if (prev == null || prev.segment != beat.segment) { ibiRun++; return }
        val ibiMs = (beat.position - prev.position) * periodMs
        if (ibiMs < SignalConfig.IBI_MIN_MS || ibiMs > SignalConfig.IBI_MAX_MS) { ibiRun++; return }

        val sameSeg = recentIbis.filter { it.segment == ibiRun }.takeLast(5).map { it.ms }.sorted()
        val rejected = sameSeg.size >= 3 &&
            abs(ibiMs - sameSeg[sameSeg.size / 2]) > SignalConfig.IBI_MEDIAN_TOLERANCE * sameSeg[sameSeg.size / 2]
        window.addInterval(rejected)
        recentIbis.addLast(Ibi(beatWallMs, ibiMs, ibiRun))
        while (recentIbis.isNotEmpty() && recentIbis.first().endMs < beatWallMs - IBI_KEEP_MS) recentIbis.removeFirst()

        if (!rejected) {
            val r = if (currentQuality == SignalQuality.GOOD) SignalConfig.KALMAN_R_GOOD else SignalConfig.KALMAN_R_DEGRADED
            kalman.measure(beatWallMs / 1000.0, 60_000.0 / ibiMs, r)
        }
    }

    /** Emits everything whose time has passed: per-second HR, 5 s quality, 30 s epochs. */
    private fun advanceClock(tMs: Long, pulse: MutableList<PulseReading>, epochs: MutableList<SignalEpoch>) {
        val prev = clockMs
        if (prev == null || tMs - prev > MAX_CLOCK_JUMP_MS || tMs < prev - MAX_CLOCK_JUMP_MS) {
            // First sample, or the stream jumped (reconnect, long dropout):
            // close what was open and re-anchor rather than emit empty seconds.
            if (prev != null) finishEpoch(epochs)
            clockMs = tMs
            nextSecondMs = ceilTo(tMs, 1000)
            windowEndMs = ceilTo(tMs + 1, QUALITY_MS)
            epochStartMs = tMs / EPOCH_MS * EPOCH_MS
            window = QualityWindow()
            return
        }
        clockMs = max(prev, tMs)

        while (tMs >= nextSecondMs + OUTPUT_LAG_MS) {
            val est = kalman.estimate(nextSecondMs / 1000.0)
            window.addSecond(coasted = est == null || est.coasted)
            epoch.seconds++
            if (est != null) {
                // The tracker keeps predicting for a moment after the band
                // leaves the skin or packets stop; such a second is coasted.
                val hadContactThroughout = lastContactMs >= nextSecondMs - 1000 && lastNoContactMs < nextSecondMs - 1000
                val reading = PulseReading(nextSecondMs, est.bpm, est.coasted || !hadContactThroughout, currentQuality)
                pulse += reading
                lastBpm = est.bpm
                if (reading.isMeasured) epoch.hr += est.bpm
                if (reading.coasted) epoch.coastSeconds++
            } else {
                epoch.coastSeconds++
            }
            nextSecondMs += 1000
        }
        while (tMs >= windowEndMs) {
            val (q, evidence) = window.rate(QUALITY_MS / 1000.0, lastBpm)
            currentQuality = q
            lastEvidence = evidence
            epoch.qualities += q
            window = QualityWindow()
            windowEndMs += QUALITY_MS
        }
        while (tMs >= epochStartMs + EPOCH_MS) finishEpoch(epochs)
    }

    private fun finishEpoch(out: MutableList<SignalEpoch>) {
        val end = epochStartMs + EPOCH_MS
        if (epoch.samples > 0) {
            val quality = SignalQuality.combine(epoch.qualities)
            val motion = if (epoch.samples == 0) 0.0 else epoch.motionSum / epoch.samples
            // PRV covers the last minute, i.e. this epoch and the one before.
            // It is only computed when both halves were Good, and it is only
            // trusted (not low-confidence) when both halves were also still.
            val prev = previousEpoch?.takeIf { it.startMs == epochStartMs - EPOCH_MS }
            val prv = if (quality == SignalQuality.GOOD && prev?.quality == SignalQuality.GOOD) {
                PrvCalculator.compute(recentIbis.filter { it.endMs in (end - PRV_MS) until end })
            } else null
            val windowStill = motion < SignalConfig.MOTION_FREEZE_G2 &&
                (prev?.motionEnergy ?: Double.MAX_VALUE) < SignalConfig.MOTION_FREEZE_G2
            val built = SignalEpoch(
                startMs = epochStartMs,
                durationMs = EPOCH_MS.toInt(),
                coveredSeconds = epoch.seconds,
                meanHr = epoch.hr.takeIf { it.isNotEmpty() }?.average(),
                minHr = epoch.hr.minOrNull(),
                maxHr = epoch.hr.maxOrNull(),
                beatCount = epoch.beats,
                quality = quality,
                rmssdMs = prv?.rmssdMs,
                sdnnMs = prv?.sdnnMs,
                prvInterpolatedFraction = prv?.interpolatedFraction ?: 0.0,
                prvLowConfidence = prv == null || prv.lowConfidence || !windowStill,
                motionEnergy = motion,
                coastFraction = if (epoch.seconds == 0) 1.0 else epoch.coastSeconds.toDouble() / epoch.seconds
            )
            out += built
            previousEpoch = built
        }
        epoch.clear()
        epochStartMs = end
    }

    private fun onGap() {
        ibiRun++
        detector.resetForGap()
        lastBeat = null
        ppgFilter.reset()
        accelFilters.forEach { it.reset() }
        nlms.clearHistory()
    }

    private class EpochAccumulator {
        var samples = 0
        var seconds = 0
        var beats = 0
        var coastSeconds = 0
        var motionSum = 0.0
        val hr = ArrayList<Double>()
        val qualities = ArrayList<SignalQuality>()
        fun clear() {
            samples = 0; seconds = 0; beats = 0; coastSeconds = 0; motionSum = 0.0
            hr.clear(); qualities.clear()
        }
    }

    private companion object {
        val ZERO3 = DoubleArray(3)
        const val EPOCH_MS = SignalConfig.EPOCH_S * 1000L
        val QUALITY_MS = (SignalConfig.QUALITY_WINDOW_S * 1000).toLong()
        const val BEAT_LOG_SIZE = 2_000
        const val PRV_MS = SignalConfig.PRV_WINDOW_S * 1000L
        const val IBI_KEEP_MS = 2 * PRV_MS
        /** Per-second HR is produced this long after the second, so late beats still count. */
        const val OUTPUT_LAG_MS = 500L
        const val MAX_CLOCK_JUMP_MS = 10_000L

        fun ceilTo(v: Long, step: Long): Long = ceil(v.toDouble() / step).toLong() * step
    }
}
