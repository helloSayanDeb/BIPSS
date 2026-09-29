package com.bipss.app.domain

import com.bipss.app.domain.environmental.AlertSeverity
import com.bipss.app.domain.environmental.AqiRiskLevel
import com.bipss.app.domain.environmental.EnvironmentalSnapshot
import com.bipss.app.domain.ali.AliLevel
import com.bipss.app.domain.ali.AliResult
import com.bipss.app.domain.ali.AutonomicLoad
import com.bipss.app.domain.recovery.RecoveryInput
import com.bipss.app.domain.recovery.RecoveryResult
import com.bipss.app.domain.strain.StrainScale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Computes every risk and wellness score from whatever data actually exists.
 *
 * The contract that matters: **every input is nullable and nothing is ever
 * substituted.** The previous version took non-null primitives, so the caller
 * had to invent HR 72 / SpO2 98 / skin temp 36.5 whenever the band was absent
 * - and the app then displayed a confident "Heat Stress 82" computed from
 * vitals that did not exist, two inches above tiles honestly reading
 * "Waiting for readings".
 *
 * Instead each domain declares a **point budget**. A component contributes its
 * points only when its input is present, and also contributes its maximum to
 * the denominator. The final score is normalised against what was actually
 * measurable, so a score computed from environment alone is still meaningful
 * rather than pretending the missing components scored zero. When nothing in a
 * domain was measurable the domain reports NEEDS_BAND or NEEDS_MORE_HISTORY
 * and the UI renders an em dash - never a number, and never a zero.
 *
 * Pure Kotlin, no Android dependencies - fully unit-testable.
 */
class RiskScoreCalculator {

    /**
     * Accumulates (earned, possible) points so a score can be normalised
     * against what was measurable rather than against a fixed 100.
     */
    private class Budget {
        var earned = 0f
        var possible = 0f
        val factors = mutableListOf<RiskFactor>()
        val missing = mutableListOf<String>()

        /** Record a component whose input was present. */
        fun add(points: Float, max: Float) {
            earned += points.coerceIn(0f, max)
            possible += max
        }

        /** Record a component whose input was absent. It contributes nothing either way. */
        fun skip(inputName: String) {
            missing += inputName
        }

        fun factor(label: String, elevated: Boolean) {
            factors += RiskFactor(label, elevated)
        }

        val hasAnything: Boolean get() = possible > 0f
        val score: Int get() = if (possible <= 0f) 0 else ((earned / possible) * 100f).toInt().coerceIn(0, 100)

        /** Fraction of the domain's full budget that was actually measurable. */
        fun coverage(fullBudget: Float): Float =
            if (fullBudget <= 0f) 0f else (possible / fullBudget).coerceIn(0f, 1f)
    }

    private companion object {
        /**
         * How much of a domain must be measurable before it may report a number.
         *
         * Normalising against what was measured is honest right up until only
         * one component survives, at which point a saturated component reports
         * as 100 out of 100. Observed live: with no band, no water log and a
         * failed weather fetch, an active weather alert was the only measurable
         * input to Heat Stress and the app showed "100 - At risk" on the
         * strength of one alert and nothing else. A confident maximum derived
         * from a single input is exactly the failure this scoring model exists
         * to prevent, so below this coverage the domain reports no number at
         * all and says what it is missing.
         *
         * Set so that temperature plus humidity alone (50 of Heat Stress's 125
         * points) still qualifies - the band-off demo state stays useful - while
         * an alert alone (25) does not.
         */
        const val MIN_COVERAGE_FOR_NUMBER = 0.4f

        /** Words that must not be left dangling at the end of a truncated label. */
        val TRAILING_JOINERS = setOf("with", "and", "of", "in", "for", "over", "at", "to", "the", "a")
    }

    /**
     * The personal context the scores are judged against. Everything nullable:
     * a missing baseline means the population fallback (or no score), never an
     * invented personal value.
     */
    data class Personal(
        /** Days with data (BaselineSchedule). */
        val dayN:            Int = 0,
        /** Resting heart rate from sleep (EWMA mean), bpm. */
        val restingHr:       Double? = null,
        /** The wearer's usual skin temperature, C. */
        val usualSkinTempC:  Double? = null
    )

    /** What a missing pillar is waiting for. */
    enum class MissingKind { BAND, HISTORY, INPUT }

    /** A pillar that was worked out elsewhere, or the reason it could not be. */
    sealed class Pillar<out T> {
        data class Value<T>(val value: T) : Pillar<T>()
        data class Missing(val reason: String, val kind: MissingKind = MissingKind.HISTORY) : Pillar<Nothing>()
    }

    /** Strain as the calculator needs it: the 0-21 value and whether HRrest was an estimate. */
    data class StrainInput(val display: Double, val restIsEstimate: Boolean, val activeMinutes: Int, val scoredMinutes: Int)

    fun computeAll(
        hr:              Int?,
        spo2:            Int?,
        skinTempC:       Float?,
        personal:        Personal,
        env:             EnvironmentalSnapshot?,
        /** Null when the user has never logged water - absence of a log is not evidence of thirst. */
        hoursSinceWater: Float?,
        sleepScore:      Int?,
        recovery:        RecoveryResult?,
        strain:          Pillar<StrainInput>,
        ali:             AliResult?,
        dataMode:        DataMode
    ): List<RiskScore> {
        val hazards = listOf(
            computeHeatStress(hr, skinTempC, env, hoursSinceWater, personal, dataMode),
            computeRespiratory(hr, spo2, env, dataMode),
            computeFlood(env, dataMode)
        )

        val recoveryScore = computeRecovery(recovery, personal.dayN, dataMode)
        val sleepPillar   = computeSleep(sleepScore, dataMode)
        val strainPillar  = computeStrain(strain, dataMode)
        val readiness     = computeReadiness(recoveryScore, sleepPillar, strainPillar, hazards, dataMode)

        return listOf(readiness, recoveryScore, sleepPillar, strainPillar) + hazards + computeAutonomicLoad(ali, personal.dayN, dataMode)
    }

    // -- Heat stress -----------------------------------------------------------
    // Computable from environment alone at reduced confidence: 95 of the 125
    // available points need no band at all. That is what makes the no-band
    // demo state honest AND useful rather than blank.

    private fun computeHeatStress(
        hr:              Int?,
        skinTempC:       Float?,
        env:             EnvironmentalSnapshot?,
        hoursSinceWater: Float?,
        personal:        Personal,
        dataMode:        DataMode
    ): RiskScore {
        val b = Budget()
        // From day 14 the body inputs are judged against this wearer's own
        // resting heart rate and usual skin temperature; before that, against
        // population thresholds, because two weeks of data cannot yet separate
        // a person's normal from an unusual fortnight.
        val personalBody = BaselineSchedule.isUsable(personal.dayN)

        // Environmental base - 50 points, split 35 humidity / 15 temperature.
        val humidity = env?.humidityPercent
        if (humidity != null) {
            val f = (humidity / 100f).coerceIn(0f, 1f)
            b.add(f * 35f, 35f)
            if (humidity > 70f) b.factor("Humidity High", true)
        } else b.skip("humidity")

        val ambient = env?.ambientTempC
        if (ambient != null) {
            val f = ((ambient - 20f) / 20f).coerceIn(0f, 1f)
            b.add(f * 15f, 15f)
            if (f > 0.6f) b.factor("Ambient Heat High", true)
        } else b.skip("ambient temperature")

        // Active heat/humidity advisories - 25 points. Only counted once a fetch
        // has actually happened; AlertSeverity.NONE on a never-fetched snapshot
        // means "unknown", not "all clear".
        if (env != null && env.fetchedAtMs > 0L) {
            val boost = when (env.alertSeverity) {
                AlertSeverity.EXTREME  -> 25f
                AlertSeverity.WARNING  -> 15f
                AlertSeverity.WATCH    -> 8f
                AlertSeverity.ADVISORY -> 3f
                AlertSeverity.NONE     -> 0f
            }
            b.add(boost, 25f)
            if (boost > 0f) b.factor("Weather Alert Active", true)
        } else b.skip("weather alerts")

        // Hydration - 20 points, and only when the user has actually logged.
        // Never having logged is not the same as being dehydrated; the old code
        // defaulted this to 8 hours, which put a permanent, invented 20 points
        // into every Heat Stress score forever.
        if (hoursSinceWater != null) {
            val f = (hoursSinceWater.coerceIn(0f, 8f) / 8f)
            b.add(f * 20f, 20f)
            if (hoursSinceWater > 4f) b.factor("Hydration Low", true)
            else if (hoursSinceWater < 2f) b.factor("Hydration Good", false)
        } else b.skip("water log")

        // Body-dependent components - 30 points, band only.
        if (hr != null) {
            val rest = personal.restingHr?.takeIf { personalBody }
            // Personal: 25 bpm above your resting rate earns the full points.
            // Population: 70 bpm upward, full at 100.
            val f = if (rest != null) ((hr - rest) / 25.0).toFloat().coerceIn(0f, 1f)
                    else ((hr - 70f) / 30f).coerceIn(0f, 1f)
            b.add(f * 20f, 20f)
            if (f > 0.5f) b.factor(if (rest != null) "Heart Rate Above Your Resting Rate" else "Heart Rate Elevated", true)
        } else b.skip("heart rate")

        if (skinTempC != null) {
            val usual = personal.usualSkinTempC?.takeIf { personalBody }
            // Personal: 1.0 C above your usual earns the full points.
            // Population: 36.5 C upward, full at 38.
            val f = if (usual != null) ((skinTempC - usual) / 1.0).toFloat().coerceIn(0f, 1f)
                    else ((skinTempC - 36.5f) / 1.5f).coerceIn(0f, 1f)
            b.add(f * 10f, 10f)
            if (f > 0.5f) b.factor(if (usual != null) "Skin Warmer Than Usual" else "Skin Temp High", true)
        } else b.skip("skin temperature")

        if (!b.hasAnything || b.coverage(125f) < MIN_COVERAGE_FOR_NUMBER) {
            return unavailable(
                RiskScoreType.HEAT_STRESS, ScoreAvailability.NEEDS_BAND, dataMode,
                b.missing.ifEmpty { listOf("environment data") }
            )
        }

        val bodyMissing = hr == null || skinTempC == null
        val usedPersonal = personalBody && (
            (hr != null && personal.restingHr != null) || (skinTempC != null && personal.usualSkinTempC != null))
        return RiskScore(
            type          = RiskScoreType.HEAT_STRESS,
            provisional   = usedPersonal && BaselineSchedule.stage(personal.dayN) == BaselineStage.PROVISIONAL,
            score         = b.score,
            level         = levelFor(b.score, RiskScoreType.HEAT_STRESS),
            factors       = b.factors.take(3),
            availability  = if (b.missing.isEmpty()) ScoreAvailability.AVAILABLE else ScoreAvailability.PARTIAL,
            confidence    = confidenceFrom(b.possible, 125f, bodyMissing),
            dataMode      = dataMode,
            missingInputs = b.missing,
            measuredOutOf = b.possible.toInt(),
            fullBudget    = 125,
            // A weighting of environment and body inputs, not a measurement.
            fidelity      = Fidelity.ESTIMATE
        )
    }

    // -- Respiratory -----------------------------------------------------------

    private fun computeRespiratory(
        hr:       Int?,
        spo2:     Int?,
        env:      EnvironmentalSnapshot?,
        dataMode: DataMode
    ): RiskScore {
        val b = Budget()

        if (spo2 != null) {
            // 100 % maps to 0, 92 % maps to the full 50 - below 92 is saturated.
            val f = ((100 - spo2) / 8f).coerceIn(0f, 1f)
            b.add(f * 50f, 50f)
            b.factor(if (spo2 >= 96) "SpO2 Stable" else "SpO2 Low", spo2 < 96)
        } else b.skip("blood oxygen")

        if (hr != null && spo2 != null && spo2 > 0) {
            val ratio = hr.toFloat() / spo2.toFloat()
            val f = ((ratio - 0.7f) / 0.3f).coerceIn(0f, 1f)
            b.add(f * 15f, 15f)
            if (f > 0.66f) b.factor("Breathing Effort Elevated", true)
        } else b.skip("breathing rate proxy")

        val aqi = env?.aqiValue
        if (aqi != null) {
            val points = when (env.aqiRiskLevel ?: AqiRiskLevel.fromAqi(aqi)) {
                AqiRiskLevel.SEVERE       -> 35f
                AqiRiskLevel.VERY_POOR    -> 27f
                AqiRiskLevel.POOR         -> 18f
                AqiRiskLevel.MODERATE     -> 10f
                AqiRiskLevel.SATISFACTORY -> 3f
                AqiRiskLevel.GOOD         -> 0f
            }
            b.add(points, 35f)
            val level = env.aqiRiskLevel ?: AqiRiskLevel.fromAqi(aqi)
            if (points > 0f) b.factor("AQI $aqi (${level.label})", points > 10f)
        } else b.skip("air quality")

        // AQI alone is 35 of 100 points. Reporting "0 - Normal" off air quality
        // with no SpO2 reads as "your breathing is fine", which is a different
        // statement from "we have not measured your breathing".
        if (!b.hasAnything || b.coverage(100f) < MIN_COVERAGE_FOR_NUMBER) {
            return unavailable(
                RiskScoreType.RESPIRATORY, ScoreAvailability.NEEDS_BAND, dataMode,
                b.missing.ifEmpty { listOf("blood oxygen") }
            )
        }

        return RiskScore(
            type          = RiskScoreType.RESPIRATORY,
            score         = b.score,
            level         = levelFor(b.score, RiskScoreType.RESPIRATORY),
            factors       = b.factors.take(3),
            availability  = if (b.missing.isEmpty()) ScoreAvailability.AVAILABLE else ScoreAvailability.PARTIAL,
            confidence    = confidenceFrom(b.possible, 100f, spo2 == null),
            dataMode      = dataMode,
            missingInputs = b.missing,
            measuredOutOf = b.possible.toInt()
        )
    }

    // -- Flood and disaster ----------------------------------------------------
    // Purely environmental. Needs no band at all, which is why it stays live in
    // the band-removed demo state.

    private fun computeFlood(env: EnvironmentalSnapshot?, dataMode: DataMode): RiskScore {
        if (env == null || env.fetchedAtMs == 0L) {
            return unavailable(
                RiskScoreType.FLOOD, ScoreAvailability.NEEDS_BAND, dataMode,
                listOf("disaster alert feed")
            )
        }
        // Without a location the alert list is the whole national feed, and
        // scoring it would report a cyclone two states away as personal risk -
        // the original "Madhya Pradesh warnings in Kolkata" bug, arriving via
        // the score instead of the card. The alerts stay visible and clearly
        // labelled; only the personal score is withheld.
        if (env.latitude == null && env.districtName.isNullOrBlank() && env.stateName.isNullOrBlank()) {
            return unavailable(
                RiskScoreType.FLOOD, ScoreAvailability.NEEDS_MORE_HISTORY, dataMode,
                listOf("your location, to tell which warnings apply to you")
            )
        }

        val floodWords = listOf("flood", "rain", "cyclone", "storm", "surge", "tide", "thunder", "wind")
        val relevant = env.activeAlerts.filter { alert ->
            val t = (alert.category + " " + alert.title).lowercase()
            floodWords.any { it in t }
        }
        val worst = relevant.maxByOrNull { it.severity.rank }?.severity ?: AlertSeverity.NONE

        val points = when (worst) {
            AlertSeverity.EXTREME  -> 90f
            AlertSeverity.WARNING  -> 65f
            AlertSeverity.WATCH    -> 40f
            AlertSeverity.ADVISORY -> 15f
            AlertSeverity.NONE     -> 0f
        }
        // Several concurrent warnings are worse than one, but only slightly:
        // a dozen district-level rain advisories in one state is an ordinary
        // monsoon day, not twelve times the danger.
        val stacking = ((relevant.size - 1).coerceAtLeast(0) * 2f).coerceAtMost(8f)
        val score = (points + stacking).toInt().coerceIn(0, 100)

        val factors = buildList {
            if (relevant.isEmpty()) add(RiskFactor("No active warnings", false))
            else {
                add(RiskFactor(shortLabel(relevant.first().title), true))
                if (relevant.size > 1)
                    add(RiskFactor("${relevant.size} warnings in your region", true))
            }
        }

        return RiskScore(
            type          = RiskScoreType.FLOOD,
            score         = score,
            level         = levelFor(score, RiskScoreType.FLOOD),
            factors       = factors,
            availability  = ScoreAvailability.AVAILABLE,
            // Cached alerts are still real alerts, just older.
            confidence    = if (env.isStale) 0.7f else 1.0f,
            dataMode      = dataMode,
            measuredOutOf = 100
        )
    }

    // -- Wellness pillars ------------------------------------------------------

    /**
     * Recovery from last night, as RecoveryEngine judged it against this
     * wearer's own baseline. The composite is an estimate; the inputs it is
     * built from are shown on the detail screen.
     */
    private fun computeRecovery(recovery: RecoveryResult?, dayN: Int, dataMode: DataMode): RiskScore {
        if (recovery == null) {
            return unavailable(
                RiskScoreType.RECOVERY, ScoreAvailability.NEEDS_MORE_HISTORY, dataMode,
                listOf("a night's sleep with the band")
            )
        }
        val score = recovery.score ?: return unavailable(
            RiskScoreType.RECOVERY, ScoreAvailability.NEEDS_MORE_HISTORY, dataMode,
            listOf(recovery.reason ?: BaselineSchedule.missingLabel(dayN))
        )
        val factors = recovery.components.mapNotNull { c ->
            if (c.input == RecoveryInput.SKIN_TEMP) {
                // Only a genuinely unusual night is worth naming; a typical one
                // is not a driver in either direction.
                val raw = c.z ?: return@mapNotNull null
                return@mapNotNull if (abs(raw) >= 1.0) RiskFactor("Skin Temp Unusual", true) else null
            }
            val z = c.signedZ ?: return@mapNotNull null
            if (abs(z) < 0.5) return@mapNotNull null
            val better = z > 0
            val label = when (c.input) {
                RecoveryInput.PRV            -> if (better) "PRV Above Usual" else "PRV Below Usual"
                RecoveryInput.RESTING_HR     -> if (better) "Resting HR Below Usual" else "Resting HR Above Usual"
                else                         -> if (better) "Longer Sleep" else "Short Sleep"
            }
            RiskFactor(label, !better)
        }
        return RiskScore(
            type          = RiskScoreType.RECOVERY,
            score         = score,
            level         = levelFor(score, RiskScoreType.RECOVERY),
            provisional   = recovery.stage == BaselineStage.PROVISIONAL,
            factors       = factors.take(3),
            availability  = ScoreAvailability.AVAILABLE,
            confidence    = BaselineSchedule.confidenceFor(dayN),
            dataMode      = dataMode,
            fidelity      = Fidelity.ESTIMATE
        )
    }

    private fun computeSleep(sleepScore: Int?, dataMode: DataMode): RiskScore {
        if (sleepScore == null) {
            return unavailable(
                RiskScoreType.SLEEP, ScoreAvailability.NEEDS_MORE_HISTORY, dataMode,
                listOf("at least one night of sleep data")
            )
        }
        return RiskScore(
            type = RiskScoreType.SLEEP, score = sleepScore,
            level = levelFor(sleepScore, RiskScoreType.SLEEP),
            factors = emptyList(), dataMode = dataMode,
            fidelity = Fidelity.ESTIMATE
        )
    }

    /**
     * Strain on its own 0-21 scale. [RiskScore.score] carries the same value
     * as a share of 100 for the ring and Readiness; the display value is the
     * 0-21 figure.
     */
    private fun computeStrain(strain: Pillar<StrainInput>, dataMode: DataMode): RiskScore {
        val input = when (strain) {
            is Pillar.Missing -> return unavailable(
                RiskScoreType.STRAIN,
                when (strain.kind) {
                    MissingKind.BAND    -> ScoreAvailability.NEEDS_BAND
                    MissingKind.HISTORY -> ScoreAvailability.NEEDS_MORE_HISTORY
                    MissingKind.INPUT   -> ScoreAvailability.NEEDS_INPUT
                },
                dataMode, listOf(strain.reason)
            ).copy(fidelity = Fidelity.PRODUCTION)
            is Pillar.Value -> strain.value
        }
        val asPercent = (input.display / StrainScale.MAX_DISPLAY * 100).roundToInt().coerceIn(0, 100)
        return RiskScore(
            type          = RiskScoreType.STRAIN,
            score         = asPercent,
            level         = levelFor(asPercent, RiskScoreType.STRAIN),
            factors       = listOf(RiskFactor("${input.scoredMinutes} of ${input.activeMinutes} active min scored", false)),
            dataMode      = dataMode,
            // Banister TRIMP is the established method; with the first-day
            // resting-rate fallback it is an estimate.
            fidelity      = if (input.restIsEstimate) Fidelity.ESTIMATE else Fidelity.PRODUCTION,
            // Load, not a health verdict: "Normal"/"At risk" do not apply.
            levelLabel    = when {
                input.display < 10.0 -> "Light"
                input.display < 14.0 -> "Moderate"
                input.display < 18.0 -> "High"
                else                 -> "All out"
            },
            displayValue  = "%.1f".format(input.display),
            scaleMax      = StrainScale.MAX_DISPLAY.roundToInt()
        )
    }

    /**
     * The Autonomic Load Indicator - EXPERIMENTAL. Reported as Low / Elevated /
     * High, never as a number; it never feeds Readiness, the health status or
     * alert ranking.
     */
    private fun computeAutonomicLoad(ali: AliResult?, dayN: Int, dataMode: DataMode): RiskScore {
        val level = ali?.level ?: return unavailable(
            RiskScoreType.AUTONOMIC_LOAD, ScoreAvailability.NEEDS_MORE_HISTORY, dataMode,
            listOf(ali?.reason ?: "Needs still, clean band signal")
        ).copy(fidelity = Fidelity.EXPERIMENTAL)
        val ring = when (level) { AliLevel.LOW -> 20; AliLevel.ELEVATED -> 55; AliLevel.HIGH -> 85 }
        return RiskScore(
            type          = RiskScoreType.AUTONOMIC_LOAD,
            score         = ring,
            level         = when (level) { AliLevel.LOW -> RiskLevel.LOW; AliLevel.ELEVATED -> RiskLevel.MEDIUM; AliLevel.HIGH -> RiskLevel.HIGH },
            factors       = buildList {
                ali.prvSuppressionZ?.takeIf { it >= 1.0 }?.let { add(RiskFactor("PRV Below Your Still Range", true)) }
                ali.stillHrElevationZ?.takeIf { it >= 1.0 }?.let { add(RiskFactor("Still Heart Rate Above Usual", true)) }
            },
            dataMode      = dataMode,
            fidelity      = Fidelity.EXPERIMENTAL,
            // Its own words, never the Normal / Monitor / At risk of a hazard.
            levelLabel    = level.label,
            displayValue  = level.label,
            scaleMax      = 0,
            confidence    = BaselineSchedule.confidenceFor(dayN),
            provisional   = BaselineSchedule.stage(dayN) == BaselineStage.PROVISIONAL
        )
    }

    /**
     * Readiness - the daily headline.
     *
     * The line that matters is the environmental drag at the end: a heat wave
     * makes you less ready. That single term is what ties the market-standard
     * hero number to the hazard domains the problem statement requires, in one
     * formula, and it only fires on a hazard score the app could actually
     * compute.
     */
    private fun computeReadiness(
        recovery: RiskScore,
        sleep:    RiskScore,
        strain:   RiskScore,
        hazards:  List<RiskScore>,
        dataMode: DataMode
    ): RiskScore {
        var weighted = 0f
        var weight = 0f
        val factors = mutableListOf<RiskFactor>()

        if (recovery.hasNumber) { weighted += recovery.score * 0.4f; weight += 0.4f }
        if (sleep.hasNumber)    { weighted += sleep.score * 0.4f;    weight += 0.4f }
        // Strain runs the other way: more load means less readiness.
        if (strain.hasNumber)   { weighted += (100 - strain.score) * 0.2f; weight += 0.2f }

        // Strain alone is not readiness: a quiet day says nothing about how
        // well the body has recovered, so a number needs recovery or sleep.
        if (weight <= 0f || (!recovery.hasNumber && !sleep.hasNumber)) {
            return unavailable(
                RiskScoreType.READINESS, ScoreAvailability.NEEDS_MORE_HISTORY, dataMode,
                listOf("at least one night of sleep data")
            )
        }

        var readiness = weighted / weight

        val worstHazard = hazards
            .filter { it.availability == ScoreAvailability.AVAILABLE }
            .maxByOrNull { it.score }
        if (worstHazard != null && worstHazard.score > 60) {
            readiness -= (worstHazard.score - 60) * 0.3f
            factors += RiskFactor("${worstHazard.type.displayName} ${worstHazard.score}", true)
        }

        if (recovery.hasNumber) factors += RiskFactor("Recovery ${recovery.score}", recovery.score < 50)
        if (sleep.hasNumber)    factors += RiskFactor("Sleep ${sleep.score}", sleep.score < 50)

        val score = readiness.toInt().coerceIn(0, 100)
        val pillarsPresent = listOf(recovery, sleep, strain).count { it.hasNumber }

        return RiskScore(
            type          = RiskScoreType.READINESS,
            score         = score,
            level         = levelFor(score, RiskScoreType.READINESS),
            factors       = factors.take(3),
            availability  = if (pillarsPresent == 3) ScoreAvailability.AVAILABLE else ScoreAvailability.PARTIAL,
            confidence    = weight,
            fidelity      = Fidelity.ESTIMATE,
            dataMode      = dataMode,
            missingInputs = buildList {
                if (!recovery.hasNumber) add("recovery")
                if (!sleep.hasNumber)    add("sleep")
                if (!strain.hasNumber)   add("strain")
            },
            measuredOutOf = 100
        )
    }

    // -- Helpers ---------------------------------------------------------------

    /**
     * Shortens an alert title for a factor chip without cutting mid-phrase.
     *
     * A hard character cut left labels like "Moderate Thunderstorms with",
     * which then read as "driven by Moderate Thunderstorms with and 11
     * warnings" once composed into a sentence. Trimming back to a word
     * boundary and dropping a trailing joining word keeps it grammatical.
     */
    private fun shortLabel(title: String, maxChars: Int = 28): String {
        val clean = title.trim().trimEnd(',')
        if (clean.length <= maxChars) return clean
        val cut = clean.take(maxChars)
        val atBoundary = cut.substringBeforeLast(' ', cut)
        return atBoundary.trimEnd(',').split(' ')
            .dropLastWhile { it.lowercase() in TRAILING_JOINERS }
            .joinToString(" ")
            .ifBlank { atBoundary }
    }

    private fun unavailable(
        type:         RiskScoreType,
        availability: ScoreAvailability,
        dataMode:     DataMode,
        missing:      List<String>
    ) = RiskScore(
        type          = type,
        score         = 0,
        level         = RiskLevel.LOW,
        factors       = emptyList(),
        availability  = availability,
        confidence    = 0f,
        dataMode      = dataMode,
        missingInputs = missing,
        measuredOutOf = 0
    )

    /**
     * Confidence tracks how much of the domain we could actually measure, with
     * an extra penalty when the missing part is body data specifically - an
     * environment-only heat score is directionally useful but should never
     * present itself as being as certain as one that saw the wearer.
     */
    private fun confidenceFrom(possible: Float, fullBudget: Float, bodyMissing: Boolean): Float {
        val coverage = (possible / fullBudget).coerceIn(0f, 1f)
        return (if (bodyMissing) coverage * 0.75f else coverage).coerceIn(0f, 1f)
    }
}
