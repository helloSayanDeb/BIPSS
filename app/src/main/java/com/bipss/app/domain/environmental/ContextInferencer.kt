package com.bipss.app.domain.environmental

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Infers whether the wearer is indoors or outdoors from the two air quality
 * readings the app already holds.
 *
 * The band's ENS160 measures the air immediately around the wearer; the CPCB
 * station measures air kilometres away. Indoors those two diverge - enclosed,
 * filtered or conditioned air is usually cleaner than the street and sits at a
 * different temperature. A large divergence is therefore evidence that the
 * wearer is in a different air mass from the outdoor station. That is the whole
 * trick: no extra hardware, no extra API call, no permission beyond what the
 * regional AQI lookup already needs.
 *
 * This is a heuristic and is reported as one. Every result carries a
 * confidence, and [ContextInference.isConclusive] is false whenever the two
 * readings fail to separate, so the UI can stay quiet rather than guess out
 * loud. Nothing here substitutes a reading: a missing input yields UNCERTAIN at
 * zero confidence, never an assumed context.
 *
 * Pure Kotlin, no Android dependencies - unit-testable.
 */
object ContextInferencer {

    /** Band AQI below this fraction of the regional reading reads as indoors. */
    const val DEFAULT_INDOOR_RATIO = 0.7f

    /**
     * How far below the indoor ratio the band must sit before a temperature gap
     * is allowed to raise confidence. Held as an offset rather than an absolute
     * so that a tuned indoor ratio moves both bands together.
     */
    private const val STRICT_MARGIN = 0.1f

    /** Temperature gap in C that, alongside a strict ratio, makes indoors the clear reading. */
    private const val TEMP_DELTA_C = 3f

    /** Band AQI above this fraction of the regional reading reads as outdoors. */
    private const val OUTDOOR_RATIO = 0.9f

    /**
     * Infer context using the fixed [DEFAULT_INDOOR_RATIO].
     *
     * All four inputs are nullable because all four genuinely can be absent -
     * no band, no network, or a station that publishes AQI but not temperature.
     */
    fun infer(
        sensorAqi:   Int?,
        apiAqi:      Int?,
        sensorTempC: Float?,
        apiTempC:    Float?
    ): ContextInference = inferWithTunedThreshold(
        sensorAqi            = sensorAqi,
        apiAqi               = apiAqi,
        sensorTempC          = sensorTempC,
        apiTempC             = apiTempC,
        indoorRatioThreshold = DEFAULT_INDOOR_RATIO
    )

    /**
     * As [infer], but with the indoor ratio supplied by the caller.
     *
     * This is the extension point for a future feedback loop. What exists today
     * is the collection half of it: ContextFeedbackEntity records each
     * inference beside whether the user said it was right, so the data needed
     * to fit a per-user threshold is accumulating from now on. The fitting
     * itself is not implemented - nothing reads those rows back, and no
     * threshold anywhere in the app is currently derived from them. Until that
     * is written, every caller passes the same fixed default.
     *
     * @param indoorRatioThreshold band-to-regional AQI ratio below which the
     *   wearer is treated as indoors. The stricter band sits [STRICT_MARGIN]
     *   below it. The outdoor threshold is deliberately left untuned: it tests
     *   whether the two readings agree, which is a different question from
     *   where the indoor cut-off belongs for a particular person.
     */
    fun inferWithTunedThreshold(
        sensorAqi:            Int?,
        apiAqi:               Int?,
        sensorTempC:          Float?,
        apiTempC:             Float?,
        indoorRatioThreshold: Float
    ): ContextInference {
        if (sensorAqi == null || apiAqi == null) {
            return ContextInference(
                context    = EnvironmentContext.UNCERTAIN,
                confidence = 0f,
                reason     = "Need both band and regional readings",
                sensorAqi  = sensorAqi,
                apiAqi     = apiAqi
            )
        }

        // A regional AQI of zero or less is not clean air, it is a broken feed.
        // Dividing by it would manufacture a ratio out of a missing measurement.
        if (apiAqi <= 0) {
            return ContextInference(
                context    = EnvironmentContext.UNCERTAIN,
                confidence = 0f,
                reason     = "Regional reading is unusable - nothing to compare against",
                sensorAqi  = sensorAqi,
                apiAqi     = apiAqi
            )
        }

        val ratio       = sensorAqi.toFloat() / apiAqi.toFloat()
        val strictRatio = (indoorRatioThreshold - STRICT_MARGIN).coerceAtLeast(0f)

        // Null when either temperature is absent, which only costs the stricter
        // rule its extra evidence - the ratio alone still decides.
        val tempDelta = if (sensorTempC != null && apiTempC != null) {
            abs(sensorTempC - apiTempC)
        } else {
            null
        }

        val (context, confidence, reason) = when {
            ratio < strictRatio && tempDelta != null && tempDelta > TEMP_DELTA_C ->
                Triple(
                    EnvironmentContext.INDOOR,
                    0.85f,
                    "Your air is much cleaner than outside and temperature differs - likely indoors"
                )

            ratio < indoorRatioThreshold ->
                Triple(
                    EnvironmentContext.INDOOR,
                    0.65f,
                    "Band air quality is notably better than the regional station"
                )

            ratio > OUTDOOR_RATIO ->
                Triple(
                    EnvironmentContext.OUTDOOR,
                    0.7f,
                    "Your air matches outdoor conditions"
                )

            else ->
                Triple(
                    EnvironmentContext.UNCERTAIN,
                    0.3f,
                    "Readings are inconclusive"
                )
        }

        return ContextInference(
            context    = context,
            confidence = confidence,
            reason     = reason,
            sensorAqi  = sensorAqi,
            apiAqi     = apiAqi
        )
    }

    /**
     * One line describing the thresholds, for the settings and debug screens.
     *
     * The heuristic shapes what the app tells the user about the air they are
     * breathing, so it should be inspectable rather than magic. Built from the
     * constants so it cannot drift away from the behaviour it describes.
     * Describes the defaults; a caller passing its own threshold to
     * [inferWithTunedThreshold] is not reflected here.
     */
    fun thresholdSummary(): String {
        val indoor  = (DEFAULT_INDOOR_RATIO * 100f).roundToInt()
        val strict  = ((DEFAULT_INDOOR_RATIO - STRICT_MARGIN) * 100f).roundToInt()
        val outdoor = (OUTDOOR_RATIO * 100f).roundToInt()
        val gap     = TEMP_DELTA_C.roundToInt()
        return "Indoors when band AQI is under $indoor% of the regional station " +
               "(under $strict% when temperatures also differ by more than $gap C); " +
               "outdoors above $outdoor%; otherwise uncertain."
    }
}
