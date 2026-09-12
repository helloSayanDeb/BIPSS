package com.bipss.app.ai

import com.bipss.app.data.db.entity.SleepSessionEntity
import com.bipss.app.domain.RiskScore
import com.bipss.app.domain.RiskScoreType
import com.bipss.app.domain.ScoreAvailability
import com.bipss.app.domain.environmental.EnvironmentalSnapshot
import com.bipss.app.domain.environmental.RankedAlert

/** One card in the morning briefing. */
data class CoachCard(
    val title: String,
    val body:  String,
    val kind:  CoachCardKind
)

enum class CoachCardKind { SLEEP, CONDITIONS, WATCH_OUT, ACTION, ADVISORY }

/**
 * Builds the morning briefing: three to five short cards, 50-60 words each.
 *
 * Deterministic by design. The on-device model rephrases and answers follow-up
 * questions, but the briefing itself is assembled from the rule engine's own
 * output so it cannot contradict the scores shown two inches above it, and so
 * it still works when the model has not been downloaded.
 *
 * Cards are omitted rather than padded. A morning with no sleep data produces
 * no sleep card - not a card saying the app does not know, and certainly not an
 * invented summary of a night it did not record.
 */
object MorningDose {

    fun build(
        sleep:      SleepSessionEntity?,
        sleepScore: Int?,
        scores:     List<RiskScore>,
        env:        EnvironmentalSnapshot,
        topAlert:   RankedAlert?,
        waterLoggedYesterday: Int
    ): List<CoachCard> = buildList {

        sleep?.let { add(sleepCard(it, sleepScore)) }
        conditionsCard(env)?.let { add(it) }
        topAlert?.let { add(alertCard(it)) }
        watchOutCard(scores)?.let { add(it) }
        add(actionCard(scores, sleep, env, waterLoggedYesterday))
    }

    private fun sleepCard(sleep: SleepSessionEntity, score: Int?): CoachCard {
        val hours = sleep.totalSleepMs / 3_600_000
        val mins  = (sleep.totalSleepMs % 3_600_000) / 60_000
        val quality = when {
            sleep.efficiencyPct >= 90 -> "That is efficient sleep - very little time awake in bed."
            sleep.efficiencyPct >= 80 -> "Efficiency was reasonable, with some restlessness."
            else                      -> "You spent a fair amount of that time awake in bed."
        }
        val wakes = when {
            sleep.awakenings == 0 -> "No awakenings were detected."
            sleep.awakenings == 1 -> "One awakening was detected."
            else                  -> "${sleep.awakenings} awakenings were detected."
        }
        return CoachCard(
            title = "Last night",
            body  = "You slept ${hours}h ${mins}m at ${sleep.efficiencyPct}% efficiency. " +
                    "$quality $wakes" +
                    (score?.let { " Sleep scored $it out of 100." } ?: ""),
            kind  = CoachCardKind.SLEEP
        )
    }

    /** Null when nothing about the environment is actually known. */
    private fun conditionsCard(env: EnvironmentalSnapshot): CoachCard? {
        val parts = buildList {
            env.ambientTempC?.let { add("${it.toInt()}°C") }
            env.humidityPercent?.let { add("${it.toInt()}% humidity") }
            env.aqiValue?.let { aqi -> add("AQI $aqi${env.aqiRiskLevel?.let { " (${it.label})" } ?: ""}") }
            env.uvIndex?.let { if (it >= 3f) add("UV ${"%.0f".format(it)}") }
        }
        if (parts.isEmpty()) return null

        val advice = when {
            (env.aqiValue ?: 0) > 200 ->
                "Air quality is poor enough to matter if you exercise outdoors - consider moving it inside."
            (env.ambientTempC ?: 0f) > 33f && (env.humidityPercent ?: 0f) > 70f ->
                "Heat and humidity together make cooling harder than either alone. Drink earlier than you think you need to."
            (env.uvIndex ?: 0f) >= 8f ->
                "UV is high around midday - cover up if you will be out for long."
            else ->
                "Nothing in today's conditions needs special precautions."
        }
        return CoachCard(
            title = "Today's conditions",
            body  = parts.joinToString(", ") + ". " + advice,
            kind  = CoachCardKind.CONDITIONS
        )
    }

    private fun alertCard(ranked: RankedAlert): CoachCard = CoachCard(
        title = ranked.alert.title.take(40),
        body  = buildString {
            append(ranked.alert.severity.label).append(" for ")
                .append(ranked.alert.areaLabel.trim().trimEnd(','))
                .append(". ")
            // The personal note is only ever present when the matching score
            // was genuinely computed, so passing it through cannot overstate.
            ranked.personalNote?.let { append(it).append(". ") }
            append(ranked.alert.description.take(120))
        }.trim(),
        kind = CoachCardKind.ADVISORY
    )

    /** Only fires on a domain the app could actually compute. */
    private fun watchOutCard(scores: List<RiskScore>): CoachCard? {
        val worst = scores
            .filter { !it.type.isWellness && it.availability == ScoreAvailability.AVAILABLE }
            .maxByOrNull { it.score }
            ?.takeIf { it.score > 50 }
            ?: return null

        val drivers = worst.factors
            .map { it.label.trim() }
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString(" and ")
        return CoachCard(
            title = "Watch out for",
            body  = "${worst.type.displayName} is ${worst.score} out of 100" +
                    (if (drivers.isNotBlank()) ", driven by $drivers." else ".") +
                    " It is worth checking again this afternoon.",
            kind  = CoachCardKind.WATCH_OUT
        )
    }

    private fun actionCard(
        scores: List<RiskScore>,
        sleep:  SleepSessionEntity?,
        env:    EnvironmentalSnapshot,
        waterLoggedYesterday: Int
    ): CoachCard {
        val heat = scores.firstOrNull {
            it.type == RiskScoreType.HEAT_STRESS && it.availability == ScoreAvailability.AVAILABLE
        }
        val readiness = scores.firstOrNull { it.type == RiskScoreType.READINESS && it.hasNumber }

        val action = when {
            (heat?.score ?: 0) > 65 ->
                "Drink water before you feel thirsty and log it, so your Heat Stress score reflects it."
            waterLoggedYesterday < 4 ->
                "You logged water $waterLoggedYesterday times yesterday. Logging each drink is what lets " +
                    "hydration count toward your score at all."
            (readiness?.score ?: 100) < 50 ->
                "Readiness is low. Keep today's load light and see whether it recovers overnight."
            sleep != null && sleep.totalSleepMs < 6 * 3_600_000L ->
                "Last night was short. An earlier bedtime tonight will do more than anything else today."
            else ->
                "Nothing is flagged. Keep logging water and wear the band through the night for sleep data."
        }
        return CoachCard(title = "Do this", body = action, kind = CoachCardKind.ACTION)
    }
}
