package com.bipss.app.domain.signal

import kotlin.math.abs

/**
 * How far the pulse signal can be trusted over a 5-second window. Every
 * consumer of pipeline output must look at it: on [POOR] a value is suppressed
 * or visibly flagged, never shown as if it were measured cleanly.
 */
enum class SignalQuality {
    GOOD, DEGRADED, POOR;

    companion object {
        /** An epoch is as good as most of its windows, and never better than DEGRADED if any window was worse. */
        fun combine(windows: List<SignalQuality>): SignalQuality = when {
            windows.isEmpty() -> POOR
            windows.count { it == POOR } * 2 >= windows.size -> POOR
            windows.any { it != GOOD } -> DEGRADED
            else -> GOOD
        }
    }
}

/** What went into one quality verdict - kept so the rating can be explained, not just asserted. */
data class QualityEvidence(
    val contactFraction: Double,
    val saturatedFraction: Double,
    /** Share of the PPG band energy the motion canceller attributed to movement. */
    val artefactFraction: Double,
    /** Share of the window's seconds with no measured beat. */
    val coastFraction: Double,
    /** Share of intervals rejected as >20 % off their local median. */
    val irregularFraction: Double,
    /** Dominant movement frequency within 8 % of the pulse rate: motion and pulse cannot be told apart. */
    val cadenceNearPulse: Boolean
)

/**
 * Accumulates one window's evidence and rates it.
 *
 * The thresholds are deliberately plain: they grade the signal, they do not
 * try to be clever about the physiology.
 */
class QualityWindow {
    private var samples = 0
    private var contact = 0
    private var saturated = 0
    private var inputEnergy = 0.0
    private var artefactEnergy = 0.0
    private var seconds = 0
    private var coastSeconds = 0
    private var intervals = 0
    private var irregular = 0

    // Zero crossings of the dominant accelerometer axis, for cadence.
    private val axisEnergy = DoubleArray(3)
    private val axisCrossings = IntArray(3)
    private val lastSign = IntArray(3)
    private var motionEnergySum = 0.0

    fun addSample(contactOk: Boolean, isSaturated: Boolean, bandPpg: Double, artefact: Double,
                  bandAccel: DoubleArray, motionEnergy: Double) {
        samples++
        if (contactOk) contact++
        if (isSaturated) saturated++
        inputEnergy += bandPpg * bandPpg
        artefactEnergy += artefact * artefact
        motionEnergySum += motionEnergy
        for (a in 0..2) {
            val v = bandAccel[a]
            axisEnergy[a] += v * v
            val sign = if (v > 0) 1 else if (v < 0) -1 else 0
            if (sign > 0 && lastSign[a] < 0) axisCrossings[a]++
            if (sign != 0) lastSign[a] = sign
        }
    }

    fun addSecond(coasted: Boolean) { seconds++; if (coasted) coastSeconds++ }

    fun addInterval(rejected: Boolean) { intervals++; if (rejected) irregular++ }

    val meanMotionEnergy: Double get() = if (samples == 0) 0.0 else motionEnergySum / samples

    fun rate(windowS: Double, hrBpm: Double?): Pair<SignalQuality, QualityEvidence> {
        val dominant = (0..2).maxBy { axisEnergy[it] }
        val cadenceHz = axisCrossings[dominant] / windowS
        val moving = meanMotionEnergy > SignalConfig.MOTION_FREEZE_G2
        val pulseHz = hrBpm?.div(60.0)
        val cadenceNearPulse = moving && pulseHz != null && cadenceHz > 0 &&
            abs(cadenceHz - pulseHz) / pulseHz < SignalConfig.CADENCE_HR_TOLERANCE

        val e = QualityEvidence(
            contactFraction   = if (samples == 0) 0.0 else contact.toDouble() / samples,
            saturatedFraction = if (samples == 0) 0.0 else saturated.toDouble() / samples,
            artefactFraction  = if (inputEnergy <= 0) 0.0 else (artefactEnergy / inputEnergy).coerceAtMost(1.0),
            coastFraction     = if (seconds == 0) 1.0 else coastSeconds.toDouble() / seconds,
            irregularFraction = if (intervals == 0) 0.0 else irregular.toDouble() / intervals,
            cadenceNearPulse  = cadenceNearPulse
        )
        val q = when {
            e.contactFraction < 0.8 || e.saturatedFraction > 0.2 ||
                e.coastFraction > 0.5 || e.artefactFraction > 0.9 -> SignalQuality.POOR
            e.coastFraction > 0.2 || e.artefactFraction > 0.5 ||
                e.irregularFraction > 0.3 || e.cadenceNearPulse -> SignalQuality.DEGRADED
            else -> SignalQuality.GOOD
        }
        return q to e
    }
}
