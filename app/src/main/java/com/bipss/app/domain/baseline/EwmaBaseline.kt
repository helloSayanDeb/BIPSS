package com.bipss.app.domain.baseline

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** Exponentially weighted mean and variance of one nightly input. */
data class EwmaState(val mean: Double, val variance: Double, val count: Int) {
    val sd: Double get() = sqrt(variance.coerceAtLeast(0.0))
}

/**
 * Exponentially weighted baseline: the incremental EWMA mean and variance of
 * Finch T, "Incremental calculation of weighted mean and variance" (2009), sec. 9.
 *
 * alpha = 0.1 gives recent nights most of the weight - an effective memory of
 * roughly two to three weeks - so the baseline follows slow change (fitness,
 * season) while a single bad night moves it by a tenth of its deviation.
 *
 * A z-score is only reported once [MIN_NIGHTS] values have been seen, and the
 * standard deviation is floored per input, because a baseline of a handful of
 * near-identical nights would otherwise turn a trivial change into a huge z.
 */
object Ewma {
    const val ALPHA = 0.1
    const val MIN_NIGHTS = 3

    fun update(state: EwmaState?, x: Double, alpha: Double = ALPHA): EwmaState {
        if (state == null || state.count == 0) return EwmaState(mean = x, variance = 0.0, count = 1)
        val diff = x - state.mean
        val incr = alpha * diff
        return EwmaState(
            mean = state.mean + incr,
            variance = (1 - alpha) * (state.variance + diff * incr),
            count = state.count + 1
        )
    }

    /** Standard score of [x] against [state], or null while the baseline is too young. */
    fun z(state: EwmaState?, x: Double, minSd: Double): Double? {
        if (state == null || state.count < MIN_NIGHTS) return null
        return (x - state.mean) / max(state.sd, minSd)
    }
}

/** Standard normal CDF (Abramowitz & Stegun 7.1.26, |error| < 1.5e-7). */
fun normalCdf(z: Double): Double {
    val t = 1.0 / (1.0 + 0.3275911 * abs(z) / sqrt(2.0))
    val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
    val erf = 1.0 - poly * exp(-(z * z) / 2.0)
    return if (z >= 0) 0.5 * (1.0 + erf) else 0.5 * (1.0 - erf)
}
