package com.bipss.app.domain.environmental

import com.bipss.app.localization.AppText
import com.bipss.app.R

import java.util.Locale

/**
 * One short look at the satellites (about 12 s), plus how much the band moved
 * meanwhile. Everything the indoor/outdoor call is allowed to use - and there
 * is deliberately no air-quality field: deciding "indoors" because the band's
 * air reading differs from the station's, and then trusting the band because
 * the user is indoors, was the circular logic this replaces.
 */
data class GnssSnapshot(
    /** Satellites tracked at all (C/N0 above zero) at the best moment of the window. */
    val satellitesVisible: Int,
    /** Satellites used in the position fix. */
    val satellitesUsed: Int,
    /** Mean C/N0 of the four strongest satellites, dB-Hz; null when none was heard. */
    val topCn0DbHz: Double?,
    /** Best horizontal accuracy of a GPS fix in the window; null when there was no fix. */
    val accuracyM: Double?,
    /** Median GPS speed over the window. */
    val speedMps: Double?,
    /** Distance from the previous sample's position, when that was minutes ago. */
    val displacementM: Double?,
    /** Band movement, mg·s per minute, averaged over the last few minutes. */
    val activityMgSPerMin: Double?
)

enum class EnvContextKind { INDOOR, OUTDOOR, UNKNOWN }

data class EnvContext(
    val kind: EnvContextKind,
    /** 0..1. Below [EnvContextClassifier.MIN_CONFIDENCE] the call is not acted on. */
    val confidence: Float,
    /** Plain-language reasons, in the order they were weighed. */
    val evidence: List<String>
) {
    val isConfident: Boolean get() = kind != EnvContextKind.UNKNOWN && confidence >= EnvContextClassifier.MIN_CONFIDENCE
}

/**
 * Indoors or outdoors from GNSS signal quality and movement.
 *
 * Satellite signals lose roughly 10-30 dB passing through a building's roof
 * and walls, so indoors fewer satellites are tracked, they are weaker, fewer
 * make it into the fix and the fix is less accurate. Open sky gives the
 * strongest satellites around 40-50 dB-Hz; the cut-offs below sit inside the
 * published ranges (see docs/research/scoring-references.md, "Indoor/outdoor
 * from GNSS") and are design choices to be tuned against the user's own
 * corrections (context_feedback), not measured constants.
 *
 * Movement is second-hand evidence: walking with a strong signal adds
 * confidence to "outdoors"; fast travel with a weak signal (a metro, a car
 * with a metal roof) is left as UNKNOWN rather than guessed.
 *
 * Pure Kotlin: unit-tested without a device.
 */
object EnvContextClassifier {

    /** Top-4 mean C/N0 at or above this reads as open sky. */
    const val OPEN_SKY_CN0_DBHZ = 35.0
    /** Top-4 mean C/N0 below this reads as attenuated by a building. */
    const val INDOOR_CN0_DBHZ = 28.0
    /** At least this many satellites in the fix supports "outdoors". */
    const val OPEN_SKY_SATELLITES = 6
    /** At most this many satellites in the fix supports "indoors". */
    const val INDOOR_MAX_SATELLITES = 3
    const val GOOD_ACCURACY_M = 15.0
    const val POOR_ACCURACY_M = 40.0
    /** Faster than a brisk walk: travelling, not standing inside. */
    const val TRAVEL_SPEED_MPS = 2.5
    /** Moved this far since the previous sample (about 10 minutes): travelling. */
    const val TRAVEL_DISPLACEMENT_M = 150.0
    /** Band movement below this is "still" (sitting or standing). Well under the strain exertion threshold of 3,000. */
    const val STILL_MG_S_PER_MIN = 600.0

    const val MIN_CONFIDENCE = 0.6f

    fun classify(s: GnssSnapshot): EnvContext {
        val evidence = mutableListOf<String>()
        val cn0 = s.topCn0DbHz
        val strong = cn0 != null && cn0 >= OPEN_SKY_CN0_DBHZ
        val weak = s.satellitesVisible == 0 || (cn0 != null && cn0 < INDOOR_CN0_DBHZ)
        val manyUsed = s.satellitesUsed >= OPEN_SKY_SATELLITES
        val fewUsed = s.satellitesUsed <= INDOOR_MAX_SATELLITES
        val goodFix = s.accuracyM != null && s.accuracyM <= GOOD_ACCURACY_M
        val poorFix = s.accuracyM == null || s.accuracyM > POOR_ACCURACY_M
        val travelling = (s.speedMps ?: 0.0) >= TRAVEL_SPEED_MPS || (s.displacementM ?: 0.0) >= TRAVEL_DISPLACEMENT_M
        val still = !travelling && s.activityMgSPerMin != null && s.activityMgSPerMin < STILL_MG_S_PER_MIN
        val walking = !travelling && s.activityMgSPerMin != null && s.activityMgSPerMin >= STILL_MG_S_PER_MIN

        // What was observed, stated once, whichever way it points.
        when {
            s.satellitesVisible == 0 -> evidence += AppText.text(R.string.copy_e463083ec725, "No satellites heard during the check")
            cn0 != null -> evidence += AppText.text(R.string.copy_f300b70b7d25, "Strongest satellites at %1\$s dB-Hz ", fmt(cn0)) +
                AppText.text(R.string.copy_ed779f9c5b05, "(open sky is usually above %1\$s, indoors below %2\$s)", fmt(OPEN_SKY_CN0_DBHZ), fmt(INDOOR_CN0_DBHZ))
        }
        if (s.satellitesVisible > 0) evidence += AppText.text(R.string.copy_33b77f67b2b0, "%1\$s of %2\$s satellites used in the position fix", s.satellitesUsed, s.satellitesVisible)
        s.accuracyM?.let { evidence += AppText.text(R.string.copy_5933d12eedb4, "GPS accurate to about %1\$s m", fmt(it)) } ?: run {
            if (s.satellitesVisible > 0) evidence += AppText.text(R.string.copy_536188a1fee3, "No GPS position fix")
        }
        if (travelling) evidence += AppText.text(R.string.copy_0d05eabc25f4, "Travelling") + (s.speedMps?.takeIf { it >= TRAVEL_SPEED_MPS }?.let { AppText.text(R.string.copy_f70773fc7640, " at %1\$s km/h", fmt(it * 3.6)) } ?: "")
        else if (still) evidence += AppText.text(R.string.copy_abcd63daaa11, "Band shows you mostly still")
        else if (walking) evidence += AppText.text(R.string.copy_a97e86f40c8e, "Band shows you moving about")

        return when {
            strong && (manyUsed || goodFix) -> {
                var c = 0.6f
                if (manyUsed) c += 0.1f
                if (goodFix) c += 0.1f
                if (travelling || walking) c += 0.1f
                EnvContext(EnvContextKind.OUTDOOR, c.coerceAtMost(0.9f), evidence)
            }
            // A weak signal on the move could be a metro or a car: not "indoors".
            weak && travelling -> EnvContext(EnvContextKind.UNKNOWN, 0.3f, evidence)
            weak && fewUsed && poorFix -> {
                var c = if (s.satellitesVisible == 0) 0.6f else 0.65f
                if (still) c += 0.15f
                EnvContext(EnvContextKind.INDOOR, c.coerceAtMost(0.9f), evidence)
            }
            // Near a window, under a tree, between tall buildings: say so.
            else -> EnvContext(EnvContextKind.UNKNOWN, 0.3f, evidence)
        }
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.0f", v)
}
