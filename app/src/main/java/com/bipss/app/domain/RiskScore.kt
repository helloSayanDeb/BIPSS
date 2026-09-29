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
    /** How much weight the number can bear; shown as a badge wherever it is. */
    val fidelity:      Fidelity          = Fidelity.ESTIMATE,
    /** Shown instead of [score] when the domain has its own scale (Strain "8.4", ALI "Low"). */
    val displayValue:  String?           = null,
    /** Top of the displayed scale: 100 for most, 21 for Strain, 0 for a label-only value. */
    val scaleMax:      Int               = 100,
    /** Replaces the Normal / Monitor / At risk wording where it does not fit (Strain: Light ... All out). */
    val levelLabel:    String?           = null,
    /**
     * Judged against a baseline that is still provisional (calibration days
     * 14-29). Shown as its own chip, never as a contributor.
     */
    val provisional:   Boolean           = false,
    val computedAt:    Long              = System.currentTimeMillis()
) {
    /** True when a number may be displayed for this score. */
    val hasNumber: Boolean get() = availability.hasNumber

    /** The value as displayed: its own scale's figure, or the 0-100 score. */
    val valueLabel: String get() = displayValue ?: score.toString()

    /** "8.4 / 21", "63 / 100", or just the label for label-only values. */
    val valueWithScale: String get() = if (scaleMax > 0) "$valueLabel / $scaleMax" else valueLabel

    /** One-line explanation shown wherever the number cannot be. */
    val reasonLine: String get() = when (availability) {
        ScoreAvailability.AVAILABLE          -> ""
        // Say what is actually missing: "environment only" used to appear
        // whenever any single input (even a water log) was absent.
        ScoreAvailability.PARTIAL            -> missingInputs.takeIf { it.isNotEmpty() }
            ?.let { "Without " + it.joinToString(", ") }
            ?: "Partial data"
        ScoreAvailability.NEEDS_BAND         -> "Needs band data" +
            missingInputs.takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")") .orEmpty()
        ScoreAvailability.NEEDS_MORE_HISTORY -> missingInputs.firstOrNull() ?: "Building baseline"
        ScoreAvailability.NEEDS_INPUT        -> missingInputs.firstOrNull() ?: "Needs your input"
    }

    /** The word beside the value: the domain's own (Strain "Moderate") or the concern level. */
    val levelWord: String get() = levelLabel ?: when (level) {
        RiskLevel.LOW    -> "low"
        RiskLevel.MEDIUM -> "medium"
        RiskLevel.HIGH   -> "high"
    }
}

/** Whether a domain describes a hazard (higher = worse) or wellness (higher = better). */
enum class ScoreCategory {
    WELLNESS,
    HAZARD,
    /** Shown, never drives the headline, the health status or alerts. */
    EXPERIMENTAL
}

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
    AUTONOMIC_LOAD("Autonomic load",    ScoreCategory.EXPERIMENTAL, false, "Is your body under load while you rest?"),
    FLOOD         ("Flood & Disaster",  ScoreCategory.HAZARD, false),
    SLEEP_QUALITY ("Sleep Quality",     ScoreCategory.HAZARD, false);

    val isWellness: Boolean get() = category == ScoreCategory.WELLNESS
    val isHazard: Boolean get() = category == ScoreCategory.HAZARD

    /** How the score is worked out, in plain words - shown with the score in place of a badge. */
    val method: String get() = when (this) {
        READINESS -> "Recovery and sleep (40% each) and how much strain you have left (20%), lowered when a " +
            "hazard score is high."
        RECOVERY -> "Last night's pulse rate variability, resting heart rate, sleep length and skin temperature, " +
            "each compared with your own recent nights."
        SLEEP, SLEEP_QUALITY -> "When you fell asleep and woke, worked out from the band's movement and heart rate " +
            "overnight, and how long you slept."
        STRAIN -> "Heart-rate load during your active minutes (a training-impulse method), on a 0-21 scale."
        HEAT_STRESS -> "Heat index from local weather, official heat alerts, time since your last drink and - when " +
            "the band is worn - your heart rate and skin temperature against your own baseline."
        RESPIRATORY -> "Blood oxygen and a breathing-effort signal from the band, with local air quality."
        FLOOD -> "Official IMD and NDMA alerts for your area, by how severe and how close they are."
        AUTONOMIC_LOAD -> com.bipss.app.domain.ali.AutonomicLoad.TOOLTIP
    }
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
