package com.bipss.app.ai

import com.bipss.app.localization.AppText
import com.bipss.app.R

import com.bipss.app.domain.RiskScore
import com.bipss.app.domain.ScoreAvailability

/**
 * Deterministic fallback for when the on-device model is unavailable.
 *
 * Every vital is nullable here for the same reason it is everywhere else: the
 * old version answered "Your current heart rate is 0 bpm" when no band was
 * connected, and "everything looks within expected range" on the strength of
 * readings it did not have. A coach that reassures you using invented numbers
 * is worse than one that admits it cannot see anything.
 */
object TemplatedResponder {

    fun respond(
        question:   String,
        riskScores: List<RiskScore>,
        heartRate:  Int?,
        spO2:       Int?,
        skinTempC:  Float?,
        waterTodayMl: Int? = null
    ): String {
        SymptomCoach.respond(question)?.let { return it.text }
        val q = com.bipss.app.localization.CoachLanguage.canonicalize(question)

        // "why is my X high" - explain the drivers, or say why we cannot.
        riskScores.forEach { score ->
            val name = score.type.englishDisplayName.lowercase()
            if (("why" in q || "reason" in q || "cause" in q) && name in q) {
                return if (!score.hasNumber) {
                    AppText.text(R.string.copy_f18e64e6473f, "I can't score your %1\$s yet - %2\$s.", score.type.displayName, score.reasonLine.lowercase())
                } else if (score.factors.isEmpty()) {
                    AppText.text(R.string.copy_5dc2ffddb9dc, "Your %1\$s score is %2\$s, but no single factor stands out.", score.type.displayName, score.valueWithScale)
                } else {
                    val drivers = score.factors.joinToString(", ") { it.label }
                    val caveat = if (score.availability == ScoreAvailability.PARTIAL)
                        AppText.text(R.string.copy_910871d038a4, " It was worked out %1\$s.", score.reasonLine.replaceFirstChar { it.lowercase() })
                    else ""
                    AppText.text(R.string.copy_2cd117f7e916, "Your %1\$s score of %2\$s is driven by: %3\$s.%4\$s", score.type.displayName, score.valueWithScale, drivers, caveat)
                }
            }
        }

        // "what is my X"
        riskScores.forEach { score ->
            if (("what" in q || "score" in q) && score.type.englishDisplayName.lowercase() in q) {
                val prefix = ""
                return if (score.hasNumber)
                    AppText.text(R.string.copy_a1d80c03781b, "%1\$sYour %2\$s score is %3\$s (%4\$s).", prefix, score.type.displayName, score.valueWithScale, score.levelWord)
                else
                    AppText.text(R.string.copy_bf3d66fc7a1f, "I don't have a %1\$s score for you - %2\$s.", score.type.displayName, score.reasonLine.lowercase())
            }
        }

        if (Regex("\\b(heart|hr|bpm)\\b").containsMatchIn(q)) {
            return heartRate?.let { AppText.text(R.string.copy_decfc19907f0, "Your current heart rate is %1\$s bpm.", it) }
                ?: AppText.text(R.string.copy_ca042f69c7aa, "I'm not receiving a heart rate right now - put the band on and it'll appear within a few seconds.")
        }

        if ("spo2" in q || "oxygen" in q || "saturation" in q) {
            return spO2?.let { AppText.text(R.string.copy_237142bb7119, "Your blood oxygen saturation is %1\$s%.", it) }
                ?: AppText.text(R.string.copy_7aa7cd62b4a2, "I don't have a blood oxygen reading right now. That needs the band, with good skin contact.")
        }

        if (Regex("\\b(temp|temperature)\\b").containsMatchIn(q)) {
            return skinTempC?.let { AppText.text(R.string.copy_09a736d283fb, "Your skin temperature is %1\$s°C.", "%.1f".format(it)) }
                ?: AppText.text(R.string.copy_5e1ba533a47e, "I don't have a skin temperature reading right now - that one comes from the band.")
        }

        if ("water" in q || "hydrat" in q || "drink" in q) {
            return (waterTodayMl?.let { AppText.text(R.string.copy_f039c96d3b4d, "You've logged %1\$s mL today. ", it) } ?: "") +
                AppText.text(R.string.copy_6078cb2b327c, "Use + on Home's Hydration card to log 250 mL and − to correct today's log. ") +
                AppText.text(R.string.copy_89f1cb30e0da, "Tap Hydration to set your own goal or see your drinks and 7-day history. ") +
                AppText.text(R.string.copy_81f1a7c45441, "Logs don't measure hydration; a missing entry doesn't mean you haven't had water.")
        }

        if (Regex("\\b(all|overview|summary)\\b").containsMatchIn(q)) {
            val known = riskScores.filter { it.hasNumber }
            if (known.isEmpty()) {
                return AppText.text(R.string.copy_a67c9589cf99, "I don't have enough data for any score yet. ") +
                    AppText.text(R.string.copy_8c221a24ec48, "Connect the band and let the environment sync finish, then ask me again.")
            }
            val summary = known.joinToString(". ") {
                "${it.type.displayName}: ${it.valueWithScale} (${it.levelWord})" +
                    ""
            }
            val unknown = riskScores.filter { !it.hasNumber }
            val tail = if (unknown.isEmpty()) ""
            else AppText.text(R.string.copy_cc36c39dcbe4, ". Still waiting on: ") + unknown.joinToString(", ") { it.type.displayName }
            return "$summary$tail"
        }

        return AppText.text(R.string.copy_8102a829d7f0, "I didn't quite understand what you need. Tell me what you're feeling and when it started, ") +
            AppText.text(R.string.copy_c60d51b38567, "or ask about a specific reading, score, or your water log. I can offer basic next steps, but I can't diagnose symptoms.")
    }
}
