package com.bipss.app.ui.bioage

import com.bipss.app.R
import com.bipss.app.domain.bioage.Contributor
import com.bipss.app.domain.bioage.PaceLabel
import com.bipss.app.localization.AppText

/** Names and units for the nine contributors, in the user's language. */
fun contributorName(c: Contributor): String = when (c) {
    Contributor.SLEEP_DURATION -> AppText.text(R.string.copy_e504ed76c744, "Sleep duration")
    Contributor.SLEEP_CONSISTENCY -> AppText.text(R.string.copy_b80b9aa43f54, "Sleep consistency")
    Contributor.RESTING_HR -> AppText.text(R.string.copy_3fc0fe1697ca, "Resting heart rate")
    Contributor.STEPS -> AppText.text(R.string.copy_1ad54ace2b86, "Daily steps")
    Contributor.ZONES_1_3 -> AppText.text(R.string.copy_036ec2491d3d, "Moderate activity")
    Contributor.ZONES_4_5 -> AppText.text(R.string.copy_da45a192fc9c, "Hard activity")
    Contributor.STRENGTH -> AppText.text(R.string.copy_63ee3a1b965a, "Strength")
    Contributor.VO2_MAX -> AppText.text(R.string.copy_541897773a44, "VO₂ max")
    Contributor.LEAN_MASS -> AppText.text(R.string.copy_5750b5e8d7c4, "Lean body mass")
}

/** A value or target in the contributor's own unit. */
fun contributorAmount(c: Contributor, v: Double): String = when (c) {
    Contributor.SLEEP_DURATION -> AppText.text(R.string.copy_634a9617cb49, "%1\$s h", "%.1f".format(v))
    Contributor.SLEEP_CONSISTENCY -> "%.0f%%".format(v)
    Contributor.RESTING_HR -> AppText.text(R.string.copy_b8300f6c066e, "%1\$s bpm", "%.0f".format(v))
    Contributor.STEPS -> AppText.text(R.string.copy_cfa7a881ea57, "%1\$s steps", "%,.0f".format(v))
    Contributor.ZONES_1_3, Contributor.ZONES_4_5, Contributor.STRENGTH ->
        AppText.text(R.string.copy_a809ce7ff2ae, "%1\$s min/week", "%.0f".format(v))
    Contributor.VO2_MAX -> "%.1f".format(v)
    Contributor.LEAN_MASS -> "%.0f%%".format(v)
}

fun paceLabelText(p: PaceLabel): String = when (p) {
    PaceLabel.REVERSING -> AppText.text(R.string.copy_b03963812f43, "Reversing")
    PaceLabel.SLOWING -> AppText.text(R.string.copy_a555544916b7, "Slowing")
    PaceLabel.STEADY -> AppText.text(R.string.copy_b3ee96c93ce5, "Steady")
    PaceLabel.ACCELERATED -> AppText.text(R.string.copy_e17d5f05f9d9, "Accelerated")
}

/** "2.6 years younger", "0.4 years older", or "On par". */
fun yearsDeltaText(years: Double): String = when {
    kotlin.math.abs(years) < 0.05 -> AppText.text(R.string.copy_6bd238eff53f, "Right on your age")
    years < 0 -> AppText.text(R.string.copy_c64002243a83, "%1\$s years younger than your age", "%.1f".format(-years))
    else -> AppText.text(R.string.copy_2fcd932964d1, "%1\$s years older than your age", "%.1f".format(years))
}

/** Signed years for a contributor chip, e.g. "-0.4 y" or "+0.6 y". */
fun signedYears(y: Double): String {
    val v = Math.round(y * 10) / 10.0
    return when {
        v == 0.0 -> "0 y"
        v < 0 -> "−%.1f y".format(-v)
        else -> "+%.1f y".format(v)
    }
}
