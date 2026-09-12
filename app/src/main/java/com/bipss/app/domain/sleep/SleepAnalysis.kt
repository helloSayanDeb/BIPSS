package com.bipss.app.domain.sleep

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Overnight sleep detection and breathing screening.
 *
 * WORDING RULE, NON-NEGOTIABLE: nothing in this file - code, comment or string -
 * may assert a condition. The words "sleep apnea detected", "you have sleep
 * apnea" and "diagnosis"/"diagnosed" appear nowhere except inside the mandatory
 * disclaimer "This is not a diagnosis". This device is unregulated; reporting
 * desaturation counts and directing the user to a physician is defensible,
 * asserting a disorder is not.
 *
 * Pure Kotlin - no Android, no Room. The repository layer maps these types to
 * and from entities, which keeps the arithmetic unit-testable on the JVM.
 */

data class Sample(val timestampMs: Long, val value: Float)

data class SleepEpoch(val startMs: Long, val activityCount: Float, val avgHr: Float?)

data class DetectedSleepSession(
    val startMs:          Long,
    val endMs:            Long,
    val totalSleepMs:     Long,
    val efficiencyPct:    Int,
    val wakeAfterOnsetMs: Long,
    val awakenings:       Int,
    val avgHr:            Float?,
    val minHr:            Float?,
    val avgSpo2:          Float?
)

data class DesaturationEvent(
    val startMs:        Long,
    val durationMs:     Long,
    val baselineSpo2:   Float,
    val nadirSpo2:      Float,
    val dropPercent:    Float,
    val motionArtefact: Boolean
)

data class NightScreening(
    val sessionStartMs:        Long,
    val sessionEndMs:          Long,
    val eventCount:            Int,
    val rejectedArtefactCount: Int,
    val odi:                   Float
)

// -- Sleep detection --------------------------------------------------------

object SleepDetector {

    const val EPOCH_MS = 30_000L

    /** 30 minutes. Shorter runs are naps or sensor noise, not a night. */
    const val MIN_EPOCHS = 60

    private val COLE_KRIPKE_WEIGHTS = listOf(0.04f, 0.2f, 1.0f, 0.2f, 0.04f, 0.04f, 0.04f)

    /**
     * Buckets both series into 30-second epochs.
     *
     * activityCount is the sum of absolute deviations from the epoch's own mean
     * rather than raw magnitude, because the accelerometer reads a constant ~1 g
     * of gravity whatever the wearer is doing. Subtracting the epoch mean
     * removes that offset and leaves only movement, so a perfectly still arm
     * scores near zero regardless of how it is oriented.
     */
    fun buildEpochs(accelMagnitude: List<Sample>, hr: List<Sample>): List<SleepEpoch> {
        if (accelMagnitude.isEmpty()) return emptyList()

        val accelByEpoch = accelMagnitude.groupBy { it.timestampMs / EPOCH_MS }
        val hrByEpoch    = hr.groupBy { it.timestampMs / EPOCH_MS }

        val firstEpoch = accelByEpoch.keys.min()
        val lastEpoch  = accelByEpoch.keys.max()

        return (firstEpoch..lastEpoch).map { epoch ->
            val accel = accelByEpoch[epoch].orEmpty()
            val activity = if (accel.isEmpty()) 0f else {
                val mean = accel.map { it.value }.average().toFloat()
                accel.sumOf { abs(it.value - mean).toDouble() }.toFloat()
            }
            val hrSamples = hrByEpoch[epoch].orEmpty()
            SleepEpoch(
                startMs       = epoch * EPOCH_MS,
                activityCount = activity,
                avgHr         = if (hrSamples.isEmpty()) null
                                else hrSamples.map { it.value }.average().toFloat()
            )
        }
    }

    /**
     * Cole-Kripke style actigraphy scoring, confirmed against heart rate.
     *
     * The accelerometer alone cannot distinguish sleep from lying still awake,
     * so an epoch only counts as sleep if heart rate also sits below the
     * wearer's resting baseline. Where no baseline exists yet the HR gate is
     * skipped rather than guessed - a first-night session is still detected,
     * just on movement alone.
     */
    fun detect(
        accelMagnitude:    List<Sample>,
        hr:                List<Sample>,
        restingHrBaseline: Double?
    ): DetectedSleepSession? {
        val epochs = buildEpochs(accelMagnitude, hr)
        if (epochs.size < MIN_EPOCHS) return null

        val asleep = epochs.mapIndexed { i, epoch ->
            val window = (-4..2).map { epochs.getOrNull(i + it)?.activityCount ?: 0f }
            val score = window.zip(COLE_KRIPKE_WEIGHTS).fold(0f) { acc, (a, w) -> acc + a * w }
            val byMovement = score < 1.0f

            val byHeartRate = if (epoch.avgHr != null && restingHrBaseline != null && restingHrBaseline > 0.0)
                epoch.avgHr < restingHrBaseline * 0.95
            else true

            byMovement && byHeartRate
        }

        val sleepIndices = asleep.withIndex().filter { it.value }.map { it.index }
        if (sleepIndices.size < MIN_EPOCHS) return null

        val firstIdx = sleepIndices.first()
        val lastIdx  = sleepIndices.last()
        val onset = epochs[firstIdx].startMs
        val wake  = epochs[lastIdx].startMs + EPOCH_MS

        val totalSleepMs = sleepIndices.size * EPOCH_MS
        val timeInBed = wake - onset
        if (timeInBed <= 0L) return null

        val efficiency = ((totalSleepMs.toDouble() / timeInBed) * 100.0)
            .roundToInt().coerceIn(0, 100)
        val wakeAfterOnset = (timeInBed - totalSleepMs).coerceAtLeast(0L)

        // Count only runs of two or more awake epochs, so a single noisy epoch
        // in the middle of the night is not reported as an awakening.
        var awakenings = 0
        var runLength = 0
        for (i in firstIdx..lastIdx) {
            if (!asleep[i]) runLength++
            else {
                if (runLength >= 2) awakenings++
                runLength = 0
            }
        }

        val sleepWindowHr = hr.filter { it.timestampMs in onset..wake }

        return DetectedSleepSession(
            startMs          = onset,
            endMs            = wake,
            totalSleepMs     = totalSleepMs,
            efficiencyPct    = efficiency,
            wakeAfterOnsetMs = wakeAfterOnset,
            awakenings       = awakenings,
            avgHr            = sleepWindowHr.takeIf { it.isNotEmpty() }
                                   ?.map { it.value }?.average()?.toFloat(),
            minHr            = sleepWindowHr.minByOrNull { it.value }?.value,
            avgSpo2          = null   // filled in by the caller, which holds the SpO2 series
        )
    }
}

// -- Breathing screening ----------------------------------------------------

object ApneaScreening {

    const val MIN_DROP_PERCENT   = 3f
    const val MIN_EVENT_MS       = 10_000L
    const val BASELINE_WINDOW_MS = 120_000L
    const val MOTION_THRESHOLD   = 0.15f
    const val MOTION_GUARD_MS    = 15_000L
    const val NIGHTS_REQUIRED    = 14

    /**
     * Finds oxygen desaturation dips against a two-minute moving baseline.
     *
     * Two details carry the whole method. Overlapping candidates are merged, so
     * one long dip is one event rather than one event per sample - an unmerged
     * implementation reports hundreds of fake events and makes the index
     * meaningless. And events coinciding with movement are flagged rather than
     * dropped, so the summary can report how many candidates were rejected
     * instead of quietly discarding them; artefact rejection is the difference
     * between a screening tool and a random number generator.
     */
    fun detectDesaturations(
        spo2:           List<Sample>,
        accelMagnitude: List<Sample>
    ): List<DesaturationEvent> {
        if (spo2.size < 10) return emptyList()
        val ordered = spo2.sortedBy { it.timestampMs }
        val events = mutableListOf<DesaturationEvent>()

        var i = 0
        while (i < ordered.size) {
            val sample = ordered[i]
            val windowStart = sample.timestampMs - BASELINE_WINDOW_MS
            val window = ordered.filter { it.timestampMs in windowStart until sample.timestampMs }
            if (window.size < 10) { i++; continue }

            val baseline = window.map { it.value }.average().toFloat()
            val drop = baseline - sample.value

            if (drop < MIN_DROP_PERCENT) { i++; continue }

            // Walk forward to recovery, tracking the nadir. This consumes the
            // whole dip so the outer loop cannot re-report it.
            var j = i
            var nadir = sample.value
            while (j < ordered.size && ordered[j].value < baseline - 1f) {
                if (ordered[j].value < nadir) nadir = ordered[j].value
                j++
            }
            val endMs = ordered[(j - 1).coerceAtMost(ordered.size - 1)].timestampMs
            val durationMs = (endMs - sample.timestampMs).coerceAtLeast(0L)

            val motion = accelMagnitude.any {
                abs(it.timestampMs - sample.timestampMs) < MOTION_GUARD_MS &&
                    it.value > MOTION_THRESHOLD
            }

            events += DesaturationEvent(
                startMs        = sample.timestampMs,
                durationMs     = durationMs,
                baselineSpo2   = baseline,
                nadirSpo2      = nadir,
                dropPercent    = baseline - nadir,
                motionArtefact = motion
            )

            i = if (j > i) j else i + 1
        }
        return events
    }

    fun validEvents(all: List<DesaturationEvent>): List<DesaturationEvent> =
        all.filter { !it.motionArtefact && it.durationMs >= MIN_EVENT_MS }

    /** Oxygen desaturation index: qualifying events per hour of sleep. */
    fun computeOdi(validEventCount: Int, sleepDurationMs: Long): Float =
        if (sleepDurationMs <= 0L) 0f
        else validEventCount / (sleepDurationMs / 3_600_000f)

    fun screen(
        spo2:    List<Sample>,
        accel:   List<Sample>,
        session: DetectedSleepSession
    ): NightScreening {
        val inSession = spo2.filter { it.timestampMs in session.startMs..session.endMs }
        val all = detectDesaturations(inSession, accel)
        val valid = validEvents(all)
        return NightScreening(
            sessionStartMs        = session.startMs,
            sessionEndMs          = session.endMs,
            eventCount            = valid.size,
            rejectedArtefactCount = all.size - valid.size,
            odi                   = computeOdi(valid.size, session.totalSleepMs)
        )
    }

    /**
     * Descriptive band for an index value.
     *
     * Deliberately phrased as an observation about the recording, never as a
     * severity grade for a condition the app is in no position to assert.
     */
    fun odiCategory(odi: Float): String = when {
        odi < 5f  -> "few overnight desaturations"
        odi < 15f -> "a mild pattern of overnight desaturations"
        odi < 30f -> "a moderate pattern of overnight desaturations"
        else      -> "a frequent pattern of overnight desaturations"
    }

    /**
     * The screening summary, or null while there are too few nights.
     *
     * Returning null before [nightsRequired] is the honest behaviour: the
     * caller then shows collection progress and the individual nights, which is
     * both truthful and more interesting than a verdict the data cannot
     * support. A single night of consumer pulse oximetry is not a basis for
     * telling someone anything about their breathing.
     */
    fun screeningSummary(
        nights:         List<NightScreening>,
        nightsRequired: Int = NIGHTS_REQUIRED
    ): String? {
        if (nights.size < nightsRequired) return null
        val avg = nights.map { it.odi }.average()
        return "Your sleep data over the past ${nights.size} nights shows " +
            "${odiCategory(avg.toFloat())} " +
            "(average ${String.format("%.1f", avg)} events per hour). " +
            "This is not a diagnosis. Please discuss with a physician."
    }

    /** Progress toward a summary, for the collection indicator. */
    fun progress(nightsCollected: Int, nightsRequired: Int = NIGHTS_REQUIRED): Float =
        (nightsCollected.toFloat() / nightsRequired).coerceIn(0f, 1f)
}
