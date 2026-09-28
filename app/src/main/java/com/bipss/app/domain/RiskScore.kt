package com.bipss.app.domain

/** Domain models for risk and wellness scoring. */

/**
 * A single score.
 *
 * [score] is only meaningful when [availability] is AVAILABLE or PARTIAL.
 * When it is NEEDS_BAND or NEEDS_MORE_HISTORY the score field is 0 as a
 * placeholder and the UI must render an em dash plus [reasonLine] instead.
 * Never print [score] without consulting [availability] first.
 */
data class RiskScore(
    val type:          RiskScoreType,
    val score:         Int,
    val level:         RiskLevel,
    val factors:       List<RiskFactor>,
    val availability:  ScoreAvailability = ScoreAvailability.AVAILABLE,
    /** 0f..1f. Drops when inputs are missing or the baseline is still young. */
    val confidence:    Float             = 1.0f,
    val dataMode:      DataMode          = DataMode.FULL,
    /** Human-readable names of the inputs that were absent, e.g. "heart rate". */
    val missingInputs: List<String>      = emptyList(),
    /** Of the points this domain can award, how many were actually measurable. */
    val measuredOutOf: Int               = 100,
    /** The domain's full point budget (Heat Stress awards 125, most others 100). */
    val fullBudget:    Int               = 100,
    val computedAt:    Long              = System.currentTimeMillis()
) {
    /** True when a number may be displayed for this score. */
    val hasNumber: Boolean get() = availability.hasNumber

    /** One-line explanation shown wherever the number cannot be. */
    val reasonLine: String get() = when (availability) {
        ScoreAvailability.AVAILABLE          -> ""
        ScoreAvailability.PARTIAL            -> "Environment only - wear band for full picture"
        ScoreAvailability.NEEDS_BAND         -> "Needs band data" +
            missingInputs.takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")") .orEmpty()
        ScoreAvailability.NEEDS_MORE_HISTORY -> missingInputs.firstOrNull() ?: "Building baseline"
    }
}

/** Whether a domain describes a hazard (higher = worse) or wellness (higher = better). */
enum class ScoreCategory { WELLNESS, HAZARD }

enum class RiskScoreType(
    val displayName:    String,
    val category:       ScoreCategory,
    /** True when a high number is a good thing (Readiness, Recovery, Sleep). */
    val higherIsBetter: Boolean,
    val question:       String = ""
) {
    // Wellness pillars - the market-standard daily headline numbers.
    READINESS("Readiness",  ScoreCategory.WELLNESS, true,  "How ready is your body today?"),
    RECOVERY ("Recovery",   ScoreCategory.WELLNESS, true,  "How well did you recover?"),
    SLEEP    ("Sleep",      ScoreCategory.WELLNESS, true,  "How well did you sleep?"),
    STRAIN   ("Strain",     ScoreCategory.WELLNESS, false, "How much load have you taken on?"),

    // Hazard domains - required by the problem statement.
    HEAT_STRESS   ("Heat Stress",       ScoreCategory.HAZARD, false),
    RESPIRATORY   ("Respiratory",       ScoreCategory.HAZARD, false),
    CARDIOVASCULAR("Cardiovascular",    ScoreCategory.HAZARD, false),
    FLOOD         ("Flood & Disaster",  ScoreCategory.HAZARD, false),
    SLEEP_QUALITY ("Sleep Quality",     ScoreCategory.HAZARD, false);

    val isWellness: Boolean get() = category == ScoreCategory.WELLNESS
}

/**
 * Level of concern - NOT a raw band on the number.
 *
 * For a hazard domain a high score means high concern; for Readiness a high
 * score means low concern. [levelFor] performs that inversion so every UI
 * surface can colour off [RiskLevel] alone without knowing the direction.
 */
enum class RiskLevel { LOW, MEDIUM, HIGH }

fun levelFor(score: Int, type: RiskScoreType): RiskLevel {
    val concern = if (type.higherIsBetter) 100 - score else score
    return when {
        concern < 20 -> RiskLevel.LOW
        concern < 50 -> RiskLevel.MEDIUM
        else         -> RiskLevel.HIGH
    }
}

data class RiskFactor(
    val label:      String,
    val isElevated: Boolean   // true -> amber chip; false -> teal chip
)

data class ChatMessage(
    val id:        String  = java.util.UUID.randomUUID().toString(),
    val content:   String,
    val sender:    Sender,
    val timestamp: Long    = System.currentTimeMillis(),
    val isError:   Boolean = false
)

enum class Sender { USER, AI }
