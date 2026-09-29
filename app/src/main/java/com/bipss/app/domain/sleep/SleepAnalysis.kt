package com.bipss.app.domain.sleep

import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Overnight sleep detection and oxygen-dip counting.
 *
 * WORDING RULE: nothing here - code, comment or string - may assert or grade a
 * condition. It detects sleep from movement, and it counts drops in blood
 * oxygen. What those counts mean is a question for a clinician. The only place
 * the idea of a diagnosis appears is the disclaimer that this is not one.
 *
 * Pure Kotlin - no Android, no Room - so the arithmetic is unit-testable.
 */

data class Sample(val timestampMs: Long, val value: Float)

/** One minute of the night, as the sleep detector sees it. */
data class MinuteEpoch(
    val startMs:  Long,
    /** Band activity summed over the minute, mg*s; null when the band sent none. */
    val activity: Double?,
    /** Mean measured heart rate over the minute, if any. */
    val hr:       Double?,
    /**
     * The band was on the wrist and its signal usable. A minute without data,
     * without skin contact or with Poor signal is off-wrist: it is never
     * scored as sleep, and it ends a session.
     */
    val onWrist:  Boolean
)

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
    /** Within 15 s of wrist movement: probable motion artefact, excluded from the count. */
    val motionArtefact: Boolean
)

data class NightScreening(
    val sessionStartMs:        Long,
    val sessionEndMs:          Long,
    val eventCount:            Int,
    val rejectedArtefactCount: Int,
    val odi:                   Float
)

/** Sleep stages, for a future model. Nothing in the app produces them yet. */
enum class SleepStage { WAKE, LIGHT, DEEP, REM }

/**
 * Integration point for a sleep-staging model. Deliberately unimplemented: no
 * staging is shown until a model validated for this band exists. The app's
 * sleep output is sleep versus wake only.
 */
interface SleepStagingModel {
    /** One stage per epoch, or null when the model cannot score this night. */
    fun stage(epochs: List<MinuteEpoch>): List<SleepStage>?
}

// -- Sleep detection -----------------------------------------------------------------

/**
 * Sleep/wake from wrist movement, after Cole RJ et al., *Automatic sleep/wake
 * identification from wrist activity*, Sleep 1992;15(5):461-469.
 *
 * For one-minute epochs the published score is
 *
 *     D = 0.001 * (106 A-4 + 54 A-3 + 58 A-2 + 76 A-1 + 230 A0 + 74 A+1 + 67 A+2)
 *
 * with the current minute weighted most, and D < 1 scored as sleep. A is the
 * activity count of the actigraph the weights were fitted on; this band's
 * counts are converted with [MG_S_PER_COUNT], a constant that has not been
 * calibrated against that device - which is why sleep detection is an Estimate.
 *
 * Movement cannot tell sleep from lying still awake. From calibration day 3
 * a minute whose heart rate sits more than [HR_GATE_BPM] above the wearer's
 * usual sleeping rate is scored awake; before that there is no sleeping rate
 * to compare with, and the gate is off.
 */
object SleepDetector {

    const val EPOCH_MS = 60_000L

    /** 30 minutes of sleep: shorter runs are naps or noise, not a night. */
    const val MIN_SLEEP_EPOCHS = 30

    private val WEIGHTS = doubleArrayOf(106.0, 54.0, 58.0, 76.0, 230.0, 74.0, 67.0)   // A-4 .. A+2
    private const val P = 0.001

    /** Band mg*s per actigraph count. Uncalibrated; see the class note. */
    const val MG_S_PER_COUNT = 300.0

    const val HR_GATE_BPM = 15.0
    const val HR_GATE_FROM_DAY = 3

    /** Per-minute sleep (true) or wake (false). Off-wrist minutes are always false. */
    fun score(epochs: List<MinuteEpoch>, sleepingHr: Double?, dayN: Int): BooleanArray {
        val counts = DoubleArray(epochs.size) { i ->
            if (epochs[i].onWrist) (epochs[i].activity ?: 0.0) / MG_S_PER_COUNT else 0.0
        }
        val hrGate = sleepingHr?.takeIf { dayN >= HR_GATE_FROM_DAY }
        return BooleanArray(epochs.size) { i ->
            val e = epochs[i]
            if (!e.onWrist) return@BooleanArray false
            var d = 0.0
            for (k in -4..2) {
                val j = i + k
                if (j in epochs.indices) d += WEIGHTS[k + 4] * counts[j]
            }
            val stillEnough = P * d < 1.0
            val calmHeart = hrGate == null || e.hr == null || e.hr <= hrGate + HR_GATE_BPM
            stillEnough && calmHeart
        }
    }

    /**
     * The main sleep period in [epochs] (consecutive minutes, oldest first):
     * within each unbroken on-wrist stretch, from the first to the last sleep
     * minute; the stretch with the most sleep wins.
     */
    fun detect(epochs: List<MinuteEpoch>, sleepingHr: Double?, dayN: Int): DetectedSleepSession? {
        if (epochs.size < MIN_SLEEP_EPOCHS) return null
        val asleep = score(epochs, sleepingHr, dayN)

        var best: IntRange? = null
        var bestSleep = 0
        var i = 0
        while (i < epochs.size) {
            if (!epochs[i].onWrist) { i++; continue }
            var j = i
            while (j + 1 < epochs.size && epochs[j + 1].onWrist &&
                epochs[j + 1].startMs - epochs[j].startMs == EPOCH_MS) j++
            val sleepIdx = (i..j).filter { asleep[it] }
            if (sleepIdx.size >= MIN_SLEEP_EPOCHS && sleepIdx.size > bestSleep) {
                best = sleepIdx.first()..sleepIdx.last()
                bestSleep = sleepIdx.size
            }
            i = j + 1
        }
        val range = best ?: return null

        val onset = epochs[range.first].startMs
        val wake = epochs[range.last].startMs + EPOCH_MS
        val timeInBed = wake - onset
        val totalSleepMs = bestSleep * EPOCH_MS

        // An awakening is at least two consecutive wake minutes, so a single
        // restless minute is not reported as waking up.
        var awakenings = 0
        var run = 0
        for (k in range) {
            if (!asleep[k]) run++ else { if (run >= 2) awakenings++; run = 0 }
        }

        val sleepHr = range.filter { asleep[it] }.mapNotNull { epochs[it].hr }
        return DetectedSleepSession(
            startMs          = onset,
            endMs            = wake,
            totalSleepMs     = totalSleepMs,
            efficiencyPct    = ((totalSleepMs.toDouble() / timeInBed) * 100).roundToInt().coerceIn(0, 100),
            wakeAfterOnsetMs = (timeInBed - totalSleepMs).coerceAtLeast(0L),
            awakenings       = awakenings,
            avgHr            = sleepHr.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            minHr            = sleepHr.minOrNull()?.toFloat(),
            avgSpo2          = null   // filled in by the caller, which holds the SpO2 series
        )
    }
}

// -- Oxygen dips -----------------------------------------------------------------------

/**
 * Counts drops in blood oxygen during sleep: the oxygen desaturation index
 * (ODI), qualifying drops per hour of sleep.
 *
 * A drop qualifies when SpO2 falls at least 3 percentage points below its own
 * trailing two-minute mean and stays down at least 10 seconds. Drops within
 * 15 seconds of wrist movement are kept, tagged as probable movement artefact,
 * shown and exported, but not counted: motion corrupts wrist oximetry.
 */
object DesaturationScreening {

    const val MIN_DROP_PERCENT   = 3f
    const val MIN_EVENT_MS       = 10_000L
    const val BASELINE_WINDOW_MS = 120_000L
    /** Band activity in one second above this (mg*s) counts as wrist movement. */
    const val MOTION_MG_S        = 30f
    const val MOTION_GUARD_MS    = 15_000L
    const val NIGHTS_REQUIRED    = 14

    /**
     * Finds the drops. The trailing mean is kept as a sliding sum (the old
     * version re-filtered the whole night per sample), overlapping candidates
     * are merged into one event, and each is checked against movement.
     */
    fun detectDesaturations(spo2: List<Sample>, activityPerSecond: List<Sample>): List<DesaturationEvent> {
        if (spo2.size < 10) return emptyList()
        val ordered = spo2.sortedBy { it.timestampMs }
        val moves = activityPerSecond.filter { it.value > MOTION_MG_S }.map { it.timestampMs }.sorted()
        val events = mutableListOf<DesaturationEvent>()

        var lo = 0              // first sample inside the trailing window
        var sum = 0.0           // sum of samples in [lo, i)
        var i = 0
        while (i < ordered.size) {
            val t = ordered[i].timestampMs
            while (lo < i && ordered[lo].timestampMs < t - BASELINE_WINDOW_MS) { sum -= ordered[lo].value; lo++ }
            val n = i - lo
            if (n < 10) { sum += ordered[i].value; i++; continue }
            val baseline = (sum / n).toFloat()
            if (baseline - ordered[i].value < MIN_DROP_PERCENT) { sum += ordered[i].value; i++; continue }

            // Walk to recovery, tracking the nadir; the whole dip is consumed.
            var j = i
            var nadir = ordered[i].value
            while (j < ordered.size && ordered[j].value < baseline - 1f) {
                if (ordered[j].value < nadir) nadir = ordered[j].value
                j++
            }
            val endMs = ordered[(j - 1).coerceAtLeast(i)].timestampMs
            events += DesaturationEvent(
                startMs        = t,
                durationMs     = (endMs - t).coerceAtLeast(0L),
                baselineSpo2   = baseline,
                nadirSpo2      = nadir,
                dropPercent    = baseline - nadir,
                motionArtefact = movedNear(moves, t, endMs)
            )
            val next = if (j > i) j else i + 1
            for (k in i until next) sum += ordered[k].value
            i = next
        }
        return events
    }

    /** Any movement from 15 s before the drop starts to 15 s after it ends. */
    private fun movedNear(sortedMoves: List<Long>, startMs: Long, endMs: Long): Boolean {
        if (sortedMoves.isEmpty()) return false
        val from = startMs - MOTION_GUARD_MS
        var lo = 0; var hi = sortedMoves.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (sortedMoves[mid] < from) lo = mid + 1 else hi = mid }
        return lo < sortedMoves.size && sortedMoves[lo] <= endMs + MOTION_GUARD_MS
    }

    fun validEvents(all: List<DesaturationEvent>): List<DesaturationEvent> =
        all.filter { !it.motionArtefact && it.durationMs >= MIN_EVENT_MS }

    /** Qualifying drops per hour of sleep. */
    fun computeOdi(validEventCount: Int, sleepDurationMs: Long): Float =
        if (sleepDurationMs <= 0L) 0f else validEventCount / (sleepDurationMs / 3_600_000f)

    fun screen(spo2: List<Sample>, activityPerSecond: List<Sample>, session: DetectedSleepSession): NightScreening {
        val inSession = spo2.filter { it.timestampMs in session.startMs..session.endMs }
        val all = detectDesaturations(inSession, activityPerSecond)
        val valid = validEvents(all)
        return NightScreening(
            sessionStartMs        = session.startMs,
            sessionEndMs          = session.endMs,
            eventCount            = valid.size,
            rejectedArtefactCount = all.size - valid.size,
            odi                   = computeOdi(valid.size, session.totalSleepMs)
        )
    }

    /** The per-night figure in plain words - a count, never a grade. */
    fun odiDescription(odi: Float): String =
        "${String.format("%.1f", odi)} oxygen dips per hour of sleep"

    /**
     * A plain average over the nights, or null while there are too few to be
     * stable. No category or severity is attached: the numbers are reported,
     * not interpreted.
     */
    fun screeningSummary(nights: List<NightScreening>, nightsRequired: Int = NIGHTS_REQUIRED): String? {
        if (nights.size < nightsRequired) return null
        val avg = nights.map { it.odi }.average()
        return "Over the past ${nights.size} nights the band counted an average of " +
            "${String.format("%.1f", avg)} oxygen dips per hour of sleep " +
            "(a fall of ${MIN_DROP_PERCENT.toInt()} points or more lasting ${MIN_EVENT_MS / 1000} s or longer). " +
            "This is not a diagnosis. If you are concerned about your breathing during sleep, talk to a doctor."
    }

    fun progress(nightsCollected: Int, nightsRequired: Int = NIGHTS_REQUIRED): Float =
        (nightsCollected.toFloat() / nightsRequired).coerceIn(0f, 1f)
}
