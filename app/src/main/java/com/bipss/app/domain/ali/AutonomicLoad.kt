package com.bipss.app.domain.ali

import com.bipss.app.domain.BaselineSchedule
import kotlin.math.abs
import kotlin.math.max

enum class AliLevel(val label: String) { LOW("Low"), ELEVATED("Elevated"), HIGH("High") }

/** A clean, still 30-second window: the only kind the indicator looks at. */
data class StillEpoch(val startMs: Long, val meanHr: Double?, val rmssdMs: Double?)

data class AliResult(
    /** Null while calibrating or without enough recent still signal. */
    val level: AliLevel?,
    /** Positive = PRV below this person's usual still-state PRV. */
    val prvSuppressionZ: Double?,
    /** Positive = heart rate above this person's usual still-state rate. */
    val stillHrElevationZ: Double?,
    val recentEpochs: Int,
    val reason: String?
)

/**
 * Autonomic Load Indicator - EXPERIMENTAL.
 *
 * Compares the last half hour of still, clean signal with the same kind of
 * signal over the previous two weeks: PRV below its usual range and heart rate
 * above its usual range while not moving. Both can rise after poor sleep, in
 * heat, with illness or after exertion; the indicator cannot tell which. Only
 * still windows count, so exercise itself never registers here - that is Strain.
 *
 * Robust statistics (median and MAD) are used instead of mean and SD, because
 * the reference windows include whatever odd days the person had.
 *
 * It never drives an alert, the headline score or the health status, and it
 * does not detect heart-rhythm problems or measure stress.
 */
object AutonomicLoad {

    const val TOOLTIP =
        "A newer measure, still being evaluated. It combines pulse rate variability and heart rate while you're still. " +
            "It does not detect heart-rhythm problems and does not measure stress — this band " +
            "has no skin-conductance sensor."

    /** Recent window, and how much still signal it needs (5 minutes). */
    const val RECENT_MS = 30 * 60_000L
    const val MIN_RECENT_EPOCHS = 10
    /** Reference distribution needs at least 2 hours of still signal. */
    const val MIN_REFERENCE_EPOCHS = 240

    private const val ELEVATED_Z = 1.0
    private const val HIGH_Z = 2.0

    /** MAD floors, so a very steady reference cannot turn a trivial change into a large z. */
    private const val MIN_MAD_PRV_MS = 3.0
    private const val MIN_MAD_HR_BPM = 2.0

    fun evaluate(recent: List<StillEpoch>, reference: List<StillEpoch>, dayN: Int): AliResult {
        if (!BaselineSchedule.isUsable(dayN)) {
            return AliResult(null, null, null, recent.size, BaselineSchedule.missingLabel(dayN))
        }
        if (recent.size < MIN_RECENT_EPOCHS) {
            return AliResult(null, null, null, recent.size, "Needs a few minutes of sitting or lying still")
        }
        if (reference.size < MIN_REFERENCE_EPOCHS) {
            return AliResult(null, null, null, recent.size, "Needs more still time over the past two weeks")
        }

        val prvZ = robustZ(recent.mapNotNull { it.rmssdMs }, reference.mapNotNull { it.rmssdMs }, MIN_MAD_PRV_MS)
            ?.let { -it }      // suppression: lower PRV is positive
        val hrZ = robustZ(recent.mapNotNull { it.meanHr }, reference.mapNotNull { it.meanHr }, MIN_MAD_HR_BPM)

        val load = listOfNotNull(prvZ, hrZ).takeIf { it.isNotEmpty() }?.average()
            ?: return AliResult(null, prvZ, hrZ, recent.size, "Not enough clean still signal")
        val level = when {
            load >= HIGH_Z     -> AliLevel.HIGH
            load >= ELEVATED_Z -> AliLevel.ELEVATED
            else               -> AliLevel.LOW
        }
        return AliResult(level, prvZ, hrZ, recent.size, null)
    }

    /** (median(recent) - median(reference)) / (1.4826 * MAD(reference)). */
    private fun robustZ(recent: List<Double>, reference: List<Double>, minMad: Double): Double? {
        if (recent.size < 3 || reference.size < 20) return null
        val refMed = median(reference)
        val mad = max(median(reference.map { abs(it - refMed) }), minMad)
        return (median(recent) - refMed) / (1.4826 * mad)
    }

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
