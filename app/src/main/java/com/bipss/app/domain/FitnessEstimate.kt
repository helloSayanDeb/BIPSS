package com.bipss.app.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Jackson 1990 non-exercise BMI equation, as reproduced in NHANES CV manual Appendix F.
 * Activity is the user's PA-R self-report (0..7), not inferred from steps or SpO2.
 * Reference sex is explicitly selected for this equation, separately from gender identity.
 */
@Serializable enum class FitnessReference { FEMALE, MALE }
@Serializable data class FitnessInput(val age: Int, val heightCm: Double, val weightKg: Double,
    val activityRating: Int, val reference: FitnessReference)
@Serializable data class FitnessRecord(val atMs: Long, val input: FitnessInput, val vo2: Double,
    val ageBand: String, val method: String = "Jackson-BMI-1990/HUNT3-means-v1")

object FitnessEstimate {
    val activityLabels = listOf(
        "Little activity; usually avoid walking or exertion",
        "Some walking, stairs or occasional exercise",
        "Moderate activity, 10–60 minutes a week",
        "Moderate activity, over 60 minutes a week",
        "Vigorous activity, under 30 minutes a week",
        "Vigorous activity, 30–60 minutes a week",
        "Vigorous activity, 1–3 hours a week",
        "Vigorous activity, over 3 hours a week")

    fun calculate(input: FitnessInput, atMs: Long): FitnessRecord? {
        if (input.age !in 20..70 || !input.heightCm.isFinite() || !input.weightKg.isFinite() ||
            input.heightCm !in 120.0..230.0 || input.weightKg !in 30.0..250.0 ||
            input.activityRating !in 0..7) return null
        val bmi = input.weightKg / (input.heightCm / 100).let { it * it }
        if (bmi !in 15.0..45.0) return null
        val value = 56.363 + 1.921 * input.activityRating - 0.381 * input.age -
            0.754 * bmi + if (input.reference == FitnessReference.MALE) 10.987 else 0.0
        // No clamping: an implausible extrapolation must not masquerade as an estimate.
        if (value !in 10.0..80.0) return null
        return FitnessRecord(atMs, input, value, ageBand(value, input.reference))
    }

    /** Nearest published decade mean, not an invented continuous biological-age equation.
     * NTNU HUNT3 healthy Norwegian reference cohort. Not the NTNU fitness calculator.
     */
    fun ageBand(vo2: Double, reference: FitnessReference): String {
        require(vo2.isFinite() && vo2 > 0)
        val means = if (reference == FitnessReference.MALE) listOf(54, 49, 47, 42, 39, 34)
                    else listOf(43, 40, 38, 34, 31, 27)
        if (vo2 > means.first()) return "20–29 or younger"
        if (vo2 < means.last()) return "70+"
        val index = means.indices.minBy { abs(means[it] - vo2) }
        return listOf("20–29", "30–39", "40–49", "50–59", "60–69", "70+")[index]
    }
}
