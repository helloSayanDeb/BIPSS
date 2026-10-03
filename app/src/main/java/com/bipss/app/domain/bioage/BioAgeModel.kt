package com.bipss.app.domain.bioage

/** Sex for the sex-specific targets. Anything other than male or female uses the midpoint of the two. */
enum class BioSex { MALE, FEMALE, OTHER }

/** One local day as BioAge reads it. Mirrors the daily_summary row. */
data class DayStats(
    val epochDay: Long,
    /** Minutes with a measured heart rate. */
    val validMinutes: Int,
    val sleepMinutes: Int? = null,
    val sleepStartMin: Int? = null,
    val sleepEndMin: Int? = null,
    val restingHr: Double? = null,
    val steps: Int? = null,
    val zoneMin1: Int = 0,
    val zoneMin2: Int = 0,
    val zoneMin3: Int = 0,
    val zoneMin45: Int = 0,
    val strengthMin: Int = 0
) {
    /** Heart-rate-reserve zones 1 to 3 (40-80 percent). */
    val zone13: Int get() = zoneMin1 + zoneMin2 + zoneMin3
}

enum class Contributor {
    SLEEP_DURATION, SLEEP_CONSISTENCY, RESTING_HR, STEPS, ZONES_1_3, ZONES_4_5, STRENGTH, VO2_MAX, LEAN_MASS
}

/** Why a contributor adds no years. */
enum class Skipped { NO_DATA, NOT_ENTERED }

/**
 * One contributor's say in the result. years is signed: positive makes the
 * age older. value and target are in the contributor's own unit (hours,
 * bpm, minutes per week...), for display.
 */
data class ContributorResult(
    val contributor: Contributor,
    val years: Double?,
    val value: Double?,
    val target: Double?,
    val skipped: Skipped? = null
)

/** What the pure maths needs besides the daily rows. */
data class BioAgeInput(
    val age: Int,
    val sex: BioSex,
    val days: List<DayStats>,
    val windowEndDay: Long,
    val vo2Max: Double? = null,
    val leanMassPct: Double? = null
)

enum class BioAgeStage { CALIBRATING, PROVISIONAL, ESTABLISHED }

data class BioAgeResult(
    val chronologicalAge: Int,
    /** Null while still calibrating. */
    val bioAge: Double?,
    val yearsDelta: Double?,
    val contributors: List<ContributorResult>,
    val stage: BioAgeStage,
    /** Days in the window with enough band wear to count. */
    val validDays: Int,
    /** Days of the window actually covered, out of BioAge.LONG_WINDOW_DAYS. */
    val windowDays: Int
)

enum class PaceLabel { REVERSING, SLOWING, STEADY, ACCELERATED }

data class PaceResult(val pace: Double, val label: PaceLabel)

/** The contributors' raw numbers, averaged over a window. Null where there was too little data. */
data class Aggregates(
    val sleepHours: Double?,
    val consistency: Double?,
    val restingHr: Double?,
    val steps: Double?,
    val zone13Weekly: Double?,
    val zone45Weekly: Double?,
    val strengthWeekly: Double?,
    val strengthEver: Boolean,
    val validDays: Int
)
