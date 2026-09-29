package com.bipss.app.domain.signal

/**
 * Every tunable of the wrist-PPG pipeline in one place, with the reason for
 * each value. Nothing here is fitted to a population: these are signal-
 * processing parameters, and the pipeline reports its own confidence
 * ([SignalQuality]) rather than hiding uncertainty behind them.
 */
object SignalConfig {

    /** Nominal PPG rate; the real period comes from BandClock and is passed in per chunk. */
    const val FS_HZ = 50.0

    /**
     * 0.5-4 Hz keeps 30-240 bpm and its first harmonic's lower part while
     * removing respiration/baseline wander below and sensor noise above.
     */
    const val BAND_LOW_HZ = 0.5
    const val BAND_HIGH_HZ = 4.0

    // -- NLMS motion-artefact cancellation --------------------------------------

    /** 12 taps at 50 Hz = 240 ms of reference history per axis: enough to model the delay between wrist motion and its PPG artefact. */
    const val NLMS_TAPS = 12

    /**
     * Step size. Normalised LMS is stable for 0 < mu < 2; 0.08 converges in a
     * few seconds of walking while keeping the pulse (which is "noise" to the
     * adaptation) from dragging the weights around.
     */
    const val NLMS_MU = 0.08

    /** Regulariser, as a fraction of the typical reference power over the filter window. */
    const val NLMS_EPS_REL = 0.01

    /**
     * Band-passed accelerometer power (g^2, summed over axes) below which the
     * wrist is treated as still and adaptation freezes. At rest the MPU6050's
     * noise is ~8 mg per axis; 0.01 g RMS per axis sits well above that and
     * well below walking (~0.2 g).
     */
    const val MOTION_FREEZE_G2 = 3e-4

    /**
     * Adaptation needs clearly more motion than the freeze threshold. Between
     * the two, the canceller's output is still used but its weights are left
     * alone: while motion is dying away the reference is mostly sensor noise,
     * and adapting to noise teaches the filter to "predict" the pulse.
     */
    const val MOTION_ADAPT_G2 = 1e-3

    /** Slow leak on the weights while adapting, so they cannot drift without bound. */
    const val NLMS_LEAK = 1e-4

    /** Time constant for the motion-energy average, in seconds. */
    const val MOTION_TAU_S = 1.0

    /**
     * A PPG sample more than this fraction away from the slowly tracked DC
     * level is not a pulse: the sensor has left the skin, or the strap was
     * knocked. It is treated as lost contact and the filters restart after it.
     */
    const val CONTACT_JUMP_FRACTION = 0.25

    /** DC tracking rate per sample (~2 s time constant at 50 Hz). */
    const val DC_ALPHA = 0.01

    // -- Beat detection (Pan-Tompkins-derived) ----------------------------------

    /** Moving-window integration width. 150 ms spans one systolic upstroke. */
    const val MWI_MS = 150.0

    /** No two beats closer than this, whatever the rate: 240 bpm. */
    const val MIN_REFRACTORY_MS = 250.0

    /**
     * Refractory also scales with the running (median) interval. A beat at
     * 60 % of the interval would be a 67 % jump in rate within one beat, which
     * a heart does not do; what shows up there is the dicrotic wave, often
     * lifted over the threshold by motion.
     */
    const val REFRACTORY_RR_FRACTION = 0.6

    /** A candidate this early after the last beat must have a comparable upstroke slope ... */
    const val SLOPE_CHECK_RR_FRACTION = 0.75

    /** ... of at least this fraction of the last beat's; otherwise it is the dicrotic wave. */
    const val SLOPE_RATIO_MIN = 0.5

    /** Search back for a missed beat when none has come for this multiple of the interval (Pan-Tompkins). */
    const val SEARCH_BACK_RR = 1.66

    /** Seconds of signal used to initialise thresholds. */
    const val LEARNING_S = 2.0

    /**
     * With no beat for this long the detector re-learns its thresholds from
     * the current signal, so one oversized transient cannot leave them too
     * high for good.
     */
    const val RELEARN_AFTER_S = 4.0

    /** A single accepted peak may lift the signal level by at most this factor. */
    const val SPKI_MAX_STEP = 3.0

    /** Plausible interbeat interval: 30-200 bpm. */
    const val IBI_MIN_MS = 300.0
    const val IBI_MAX_MS = 2000.0

    // -- Heart-rate tracking ----------------------------------------------------

    /** Process noise of the HR random-walk-on-slope model, bpm^2/s^3. HR can change a few bpm a second at exercise onset. */
    const val KALMAN_Q = 0.5

    /**
     * Measurement variance for one beat's instantaneous HR at Good quality:
     * 4 bpm SD, which includes the normal beat-to-beat (breathing) variation,
     * so the displayed rate follows the average rather than every breath.
     */
    const val KALMAN_R_GOOD = 16.0

    /** ... and at Degraded quality (7 bpm SD). */
    const val KALMAN_R_DEGRADED = 49.0

    /** Innovations beyond this many SDs are treated as outliers. */
    const val KALMAN_GATE_SD = 4.0

    /** While coasting without beats the slope decays by this factor per second. */
    const val KALMAN_COAST_DAMPING = 0.7

    /** After this long without a beat there is no heart-rate estimate at all. */
    const val KALMAN_MAX_COAST_S = 8.0

    // -- Quality, PRV, epochs ---------------------------------------------------

    const val QUALITY_WINDOW_S = 5.0
    const val EPOCH_S = 30

    /** PRV needs a minute: RMSSD over fewer beats is dominated by a handful of intervals. */
    const val PRV_WINDOW_S = 60

    /** An interval more than 20 % off its local median is an artefact or ectopic, not variability. */
    const val IBI_MEDIAN_TOLERANCE = 0.20

    /** Above this share of replaced intervals, a PRV value is flagged low-confidence. */
    const val MAX_INTERPOLATED_FRACTION = 0.30

    /** RMSSD needs at least this many real successive pairs. */
    const val MIN_RMSSD_PAIRS = 20

    /** Step frequency within this fraction of the pulse frequency makes motion and pulse inseparable. */
    const val CADENCE_HR_TOLERANCE = 0.08
}
