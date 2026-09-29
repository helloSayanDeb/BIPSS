package com.bipss.app.domain.signal

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** Heart rate at one moment, with how it was obtained. */
data class HrEstimate(
    val timeMs: Long,
    val bpm: Double,
    /** Standard deviation of the estimate, bpm. */
    val sdBpm: Double,
    /**
     * No beat was measured recently: this is the tracker's prediction, not a
     * measurement. Shown as a placeholder at most, never stored as a reading.
     */
    val coasted: Boolean
)

/**
 * Two-state Kalman tracker for heart rate: [HR, dHR/dt].
 *
 * Each beat's instantaneous rate (60 000 / interval) is one noisy measurement,
 * weighted by signal quality through its variance. Between beats the tracker
 * predicts; if beats stop it coasts, damping the slope towards zero so a rate
 * that was rising does not keep rising into fiction, and after
 * [SignalConfig.KALMAN_MAX_COAST_S] it gives up and reports nothing.
 */
class HrKalman {

    private var hr = 0.0
    private var slope = 0.0
    private var p00 = 0.0
    private var p01 = 0.0
    private var p11 = 0.0
    private var stateTimeS = 0.0
    private var initialized = false
    private var lastMeasurementS: Double? = null
    private var consecutiveRejects = 0

    /** Adds one beat's instantaneous heart rate, measured at [timeS], with variance [r] (bpm^2). */
    fun measure(timeS: Double, bpm: Double, r: Double) {
        if (!initialized) { initialize(timeS, bpm); return }
        predict(timeS)
        val s = p00 + r
        val innovation = bpm - hr
        if (abs(innovation) > SignalConfig.KALMAN_GATE_SD * sqrt(s)) {
            // One wild interval is an artefact. Several in a row mean the rate
            // really changed faster than the model allows: start over there.
            if (++consecutiveRejects >= 3) initialize(timeS, bpm)
            return
        }
        consecutiveRejects = 0
        val k0 = p00 / s
        val k1 = p01 / s
        hr += k0 * innovation
        slope += k1 * innovation
        val n00 = (1 - k0) * p00
        val n01 = (1 - k0) * p01
        val n11 = p11 - k1 * p01
        p00 = n00; p01 = n01; p11 = n11
        lastMeasurementS = timeS
    }

    /** The estimate at [timeS], or null when there has been no beat for too long. */
    fun estimate(timeS: Double): HrEstimate? {
        val last = lastMeasurementS ?: return null
        val since = timeS - last
        if (since > SignalConfig.KALMAN_MAX_COAST_S) {
            initialized = false
            lastMeasurementS = null
            return null
        }
        val coasting = since > COAST_AFTER_S
        if (coasting) {
            val dt = (timeS - stateTimeS).coerceAtLeast(0.0)
            slope *= SignalConfig.KALMAN_COAST_DAMPING.pow(dt)
        }
        predict(timeS)
        return HrEstimate(
            timeMs = (timeS * 1000).toLong(),
            bpm = hr,
            sdBpm = sqrt(p00.coerceAtLeast(0.0)),
            coasted = coasting
        )
    }

    fun reset() {
        initialized = false
        lastMeasurementS = null
        consecutiveRejects = 0
    }

    private fun initialize(timeS: Double, bpm: Double) {
        hr = bpm; slope = 0.0
        p00 = SignalConfig.KALMAN_R_DEGRADED; p01 = 0.0; p11 = 4.0
        stateTimeS = timeS
        initialized = true
        consecutiveRejects = 0
        lastMeasurementS = timeS
    }

    private fun predict(timeS: Double) {
        // A beat found by search-back can be older than the current state; it
        // is applied at the state's time rather than rewinding the filter.
        val dt = (timeS - stateTimeS).coerceAtLeast(0.0)
        if (dt == 0.0) return
        hr += slope * dt
        val q = SignalConfig.KALMAN_Q
        val q00 = q * dt * dt * dt / 3
        val q01 = q * dt * dt / 2
        val q11 = q * dt
        val n00 = p00 + dt * (2 * p01 + dt * p11) + q00
        val n01 = p01 + dt * p11 + q01
        val n11 = p11 + q11
        p00 = n00; p01 = n01; p11 = n11
        stateTimeS = timeS
    }

    private companion object {
        /** Down to ~35 bpm a beat comes at least every 1.7 s; detection adds ~0.3 s. */
        const val COAST_AFTER_S = 2.0
    }
}
