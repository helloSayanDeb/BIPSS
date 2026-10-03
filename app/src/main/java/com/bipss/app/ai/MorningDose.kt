package com.bipss.app.ai

import com.bipss.app.localization.AppText
import com.bipss.app.R

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
            sleep.efficiencyPct >= 90 -> AppText.text(R.string.copy_a9122fb15e5b, "That is efficient sleep - very little time awake in bed.")
            sleep.efficiencyPct >= 80 -> AppText.text(R.string.copy_34874f0a3680, "Efficiency was reasonable, with some restlessness.")
            else                      -> AppText.text(R.string.copy_415a9c59706c, "You spent a fair amount of that time awake in bed.")
        }
        val wakes = when {
            sleep.awakenings == 0 -> AppText.text(R.string.copy_44e3f6484f0f, "No awakenings were detected.")
            sleep.awakenings == 1 -> AppText.text(R.string.copy_a04ab2530efd, "One awakening was detected.")
            else                  -> AppText.text(R.string.copy_9a218bd790b2, "%1\$s awakenings were detected.", sleep.awakenings)
        }
        return CoachCard(
            title = AppText.text(R.string.copy_b322a90abaab, "Last night"),
            body  = AppText.text(R.string.copy_68742292d230, "You slept %1\$sh %2\$sm at %3\$s% efficiency. ", hours, mins, sleep.efficiencyPct) +
                    "$quality $wakes" +
                    (score?.let { AppText.text(R.string.copy_e99f5081c43a, " Sleep scored %1\$s out of 100.", it) } ?: ""),
            kind  = CoachCardKind.SLEEP
        )
    }

    /** Null when nothing about the environment is actually known. */
    private fun conditionsCard(env: EnvironmentalSnapshot): CoachCard? {
        if (env.isStale) return null
        val parts = buildList {
            env.ambientTempC?.let { add("${it.toInt()}°C") }
            env.humidityPercent?.let { add(AppText.text(R.string.copy_47173208782c, "%1\$s% humidity", it.toInt())) }
            env.aqiValue?.let { aqi ->
                val what = if (env.aqiSource == com.bipss.app.domain.environmental.AqiSource.MODEL) AppText.text(R.string.copy_5c51e7ca2884, "modelled AQI") else "AQI"
                add("$what $aqi${env.aqiRiskLevel?.let { " (${it.label})" } ?: ""}")
            }
            env.uvIndex?.let { if (it >= 3f) add(AppText.text(R.string.copy_dabea1099fc6, "UV %1\$s", "%.0f".format(it))) }
        }
        if (parts.isEmpty()) return null

        val advice = when {
            (env.aqiValue ?: 0) > 200 ->
                AppText.text(R.string.copy_65e12b53b1f0, "Air quality is poor enough to matter if you exercise outdoors - consider moving it inside.")
            (env.ambientTempC ?: 0f) > 33f && (env.humidityPercent ?: 0f) > 70f ->
                AppText.text(R.string.copy_ec339e23bd9a, "Heat and humidity together make cooling harder than either alone. Drink earlier than you think you need to.")
            (env.uvIndex ?: 0f) >= 8f ->
                AppText.text(R.string.copy_e32a755b484c, "UV is high around midday - cover up if you will be out for long.")
            else ->
                AppText.text(R.string.copy_86abc147e6d9, "Check conditions again before spending time outdoors.")
        }
        return CoachCard(
            title = AppText.text(R.string.copy_36ba5286c614, "Today's conditions"),
            body  = parts.joinToString(", ") + ". " + advice,
            kind  = CoachCardKind.CONDITIONS
        )
    }

    private fun alertCard(ranked: RankedAlert): CoachCard = CoachCard(
        title = ranked.alert.title.take(40),
        body  = buildString {
            append(ranked.alert.severity.label).append(AppText.text(R.string.copy_ccb2f8fb3cff, " for "))
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
            .filter { it.type.isHazard && it.availability == ScoreAvailability.AVAILABLE }
            .maxByOrNull { it.score }
            ?.takeIf { it.score > 50 }
            ?: return null

        val drivers = worst.factors
            .map { it.label.trim() }
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString(AppText.text(R.string.copy_e3ee915a8e8c, " and "))
        return CoachCard(
            title = AppText.text(R.string.copy_b43559f5c64a, "Watch out for"),
            body  = AppText.text(R.string.copy_e027140d4516, "%1\$s is %2\$s out of 100", worst.type.displayName, worst.score) +
                    (if (drivers.isNotBlank()) AppText.text(R.string.copy_6dbea5b8742d, ", driven by %1\$s.", drivers) else ".") +
                    AppText.text(R.string.copy_4039e73cedb3, " It is worth checking again this afternoon."),
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
                AppText.text(R.string.copy_f347640991bf, "Take a break somewhere cool and keep drinks within reach. Follow any fluid limit you've been given.")
            waterLoggedYesterday < 4 ->
                AppText.text(R.string.copy_a46a6bb5da7c, "Keep a drink within reach today. Log it when you have it to see your daily progress.")
            (readiness?.score ?: 100) < 50 ->
                AppText.text(R.string.copy_cf219b72d8a2, "Readiness is low. Keep today's load light and see whether it recovers overnight.")
            sleep != null && sleep.totalSleepMs < 6 * 3_600_000L ->
                AppText.text(R.string.copy_20f3a1a6d03d, "Last night was short. Make room for a gentler day and enough time to rest tonight.")
            else ->
                AppText.text(R.string.copy_1e9cc9429e04, "Make time for comfortable movement, regular drinks and a consistent bedtime.")
        }
        return CoachCard(title = AppText.text(R.string.copy_b8f8513c6aac, "One small step"), body = action, kind = CoachCardKind.ACTION)
    }
}
