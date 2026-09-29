package com.bipss.app.ai

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
        skinTempC:  Float?
    ): String {
        val q = question.lowercase()

        // "why is my X high" - explain the drivers, or say why we cannot.
        riskScores.forEach { score ->
            val name = score.type.displayName.lowercase()
            if (("why" in q || "reason" in q || "cause" in q) && name in q) {
                return if (!score.hasNumber) {
                    "I can't score your ${score.type.displayName} yet - ${score.reasonLine.lowercase()}."
                } else if (score.factors.isEmpty()) {
                    "Your ${score.type.displayName} score is ${score.valueWithScale}, but no single factor stands out."
                } else {
                    val drivers = score.factors.joinToString(", ") { it.label }
                    val caveat = if (score.availability == ScoreAvailability.PARTIAL)
                        " It was worked out ${score.reasonLine.replaceFirstChar { it.lowercase() }}."
                    else ""
                    "Your ${score.type.displayName} score of ${score.valueWithScale} is driven by: $drivers.$caveat"
                }
            }
        }

        // "what is my X"
        riskScores.forEach { score ->
            if (("what" in q || "score" in q) && score.type.displayName.lowercase() in q) {
                val prefix = ""
                return if (score.hasNumber)
                    "${prefix}Your ${score.type.displayName} score is ${score.valueWithScale} (${score.levelWord})."
                else
                    "I don't have a ${score.type.displayName} score for you - ${score.reasonLine.lowercase()}."
            }
        }

        if ("heart" in q || " hr" in q || "bpm" in q) {
            return heartRate?.let { "Your current heart rate is $it bpm." }
                ?: "I'm not receiving a heart rate right now - put the band on and it'll appear within a few seconds."
        }

        if ("spo2" in q || "oxygen" in q || "saturation" in q) {
            return spO2?.let { "Your blood oxygen saturation is $it%." }
                ?: "I don't have a blood oxygen reading right now. That needs the band, with good skin contact."
        }

        if ("temp" in q || "temperature" in q) {
            return skinTempC?.let { "Your skin temperature is ${"%.1f".format(it)}°C." }
                ?: "I don't have a skin temperature reading right now - that one comes from the band."
        }

        if ("water" in q || "hydrat" in q || "drink" in q) {
            return "Tap \"Log water\" on the Home screen each time you drink. " +
                "Until you log something, hydration is left out of your Heat Stress score entirely."
        }

        if ("all" in q || "overview" in q || "summary" in q) {
            val known = riskScores.filter { it.hasNumber }
            if (known.isEmpty()) {
                return "I don't have enough data for any score yet. " +
                    "Connect the band and let the environment sync finish, then ask me again."
            }
            val summary = known.joinToString(". ") {
                "${it.type.displayName}: ${it.valueWithScale} (${it.levelWord})" +
                    ""
            }
            val unknown = riskScores.filter { !it.hasNumber }
            val tail = if (unknown.isEmpty()) ""
            else ". Still waiting on: " + unknown.joinToString(", ") { it.type.displayName }
            return "$summary$tail"
        }

        // Default. Reports only what is actually measured.
        val parts = buildList {
            heartRate?.let { add("HR $it bpm") }
            spO2?.let { add("SpO2 $it%") }
            skinTempC?.let { add("skin temp ${"%.1f".format(it)}°C") }
        }
        return if (parts.isEmpty()) {
            "I'm not receiving any vitals at the moment, so I can't tell you how you're doing. " +
                "Once the band is streaming, ask me about a specific score."
        } else {
            "Your current readings are ${parts.joinToString(", ")}. " +
                "Ask me about a specific score for more detail."
        }
    }
}
