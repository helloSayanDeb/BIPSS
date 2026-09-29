package com.bipss.app.domain.signal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One second-order IIR section, transposed direct form II. */
class Biquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() { z1 = 0.0; z2 = 0.0 }

    companion object {
        private const val BUTTERWORTH_Q = 0.7071067811865476  // 1/sqrt(2)

        /** 2nd-order Butterworth low-pass (RBJ cookbook, bilinear transform). */
        fun lowPass(cutoffHz: Double, sampleHz: Double): Biquad {
            val w0 = 2 * PI * cutoffHz / sampleHz
            val alpha = sin(w0) / (2 * BUTTERWORTH_Q)
            val c = cos(w0)
            val a0 = 1 + alpha
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        /** 2nd-order Butterworth high-pass (RBJ cookbook, bilinear transform). */
        fun highPass(cutoffHz: Double, sampleHz: Double): Biquad {
            val w0 = 2 * PI * cutoffHz / sampleHz
            val alpha = sin(w0) / (2 * BUTTERWORTH_Q)
            val c = cos(w0)
            val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
}

/**
 * Band-pass as a Butterworth high-pass followed by a Butterworth low-pass.
 *
 * The first sample of each run is taken as the baseline and subtracted: a
 * PPG sits on a DC level of tens of thousands of counts, and feeding that step
 * straight into the high-pass would ring for seconds after every reconnect.
 */
class BandPass(
    sampleHz: Double = SignalConfig.FS_HZ,
    lowHz: Double = SignalConfig.BAND_LOW_HZ,
    highHz: Double = SignalConfig.BAND_HIGH_HZ
) {
    private val hp = Biquad.highPass(lowHz, sampleHz)
    private val lp = Biquad.lowPass(highHz, sampleHz)
    private var baseline: Double? = null

    fun process(x: Double): Double {
        val base = baseline ?: x.also { baseline = it }
        return lp.process(hp.process(x - base))
    }

    fun reset() {
        hp.reset(); lp.reset(); baseline = null
    }
}

/**
 * Normalised least-mean-squares adaptive noise canceller.
 *
 * The PPG ([primary]) is blood-volume pulse plus a motion artefact; the
 * accelerometer axes ([refs]) see the motion but not the pulse. The filter
 * learns the mapping from recent accelerometer history to the artefact and
 * subtracts it; what cannot be predicted from motion - the pulse - is left in
 * the error signal, which is the output.
 *
 * Adaptation is frozen while the wrist is still: with no motion to learn
 * from, the only thing the weights could track is the pulse itself, and
 * slowly learning to cancel the pulse is the classic failure of this design.
 */
class NlmsFilter(
    private val channels: Int = 3,
    private val taps: Int = SignalConfig.NLMS_TAPS,
    private val mu: Double = SignalConfig.NLMS_MU,
    private val epsRel: Double = SignalConfig.NLMS_EPS_REL,
    /**
     * Lower bound on the regulariser, in the units of ||x||^2. A reference
     * that is only sensor noise must not be able to produce large updates.
     */
    private val epsFloor: Double = SignalConfig.NLMS_TAPS * SignalConfig.MOTION_FREEZE_G2,
    private val leak: Double = SignalConfig.NLMS_LEAK
) {
    private val weights = DoubleArray(channels * taps)
    private val history = DoubleArray(channels * taps)   // [channel][tap], newest at tap 0
    private var refPowerAvg = 0.0

    /** Last prediction of the artefact, for debugging and quality. */
    var lastArtefact = 0.0
        private set

    fun process(primary: Double, refs: DoubleArray, adapt: Boolean): Double {
        require(refs.size == channels)
        for (c in 0 until channels) {
            val base = c * taps
            for (k in taps - 1 downTo 1) history[base + k] = history[base + k - 1]
            history[base] = refs[c]
        }
        var y = 0.0
        var norm = 0.0
        for (i in weights.indices) {
            y += weights[i] * history[i]
            norm += history[i] * history[i]
        }
        val e = primary - y
        lastArtefact = y

        refPowerAvg = 0.999 * refPowerAvg + 0.001 * (norm / weights.size)
        if (adapt) {
            // Regularise against the typical power, not a fixed constant: the
            // filter must behave the same whatever the accelerometer's scale.
            val eps = maxOf(epsRel * refPowerAvg * weights.size, epsFloor)
            val g = mu * e / (eps + norm)
            for (i in weights.indices) weights[i] = (1 - leak) * weights[i] + g * history[i]
        }
        return e
    }

    /** Clears the history (after a gap) but keeps what was learnt about this wearer's artefact. */
    fun clearHistory() { history.fill(0.0) }

    fun reset() { weights.fill(0.0); history.fill(0.0); refPowerAvg = 0.0 }

    fun weightNorm(): Double = sqrt(weights.sumOf { it * it })
}
