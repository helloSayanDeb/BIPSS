package com.bipss.app.ai

import com.bipss.app.domain.RiskScore
import com.bipss.app.domain.RiskScoreType

/**
 * Keyword-based fallback when [ChatbotEngine] failed to initialize.
 * Matches user question against score/factor data and constructs a natural-language
 * sentence. The user should never see a raw error — always a working, simpler answer.
 */
object TemplatedResponder {

    fun respond(
        question:   String,
        riskScores: List<RiskScore>,
        heartRate:  Int,
        spO2:       Int,
        skinTempC:  Float
    ): String {
        val q = question.lowercase()

        // Match "why" + score name → explain top factors
        riskScores.forEach { score ->
            val scoreName = score.type.displayName.lowercase()
            if (("why" in q || "reason" in q || "cause" in q) && scoreName in q) {
                val factorList = score.factors.joinToString(", ") { it.label }
                return "Your ${score.type.displayName} score of ${score.score} is driven by: $factorList."
            }
        }

        // "what is" + score name → return score
        riskScores.forEach { score ->
            if (("what" in q || "score" in q) && score.type.displayName.lowercase() in q) {
                return "Your ${score.type.displayName} score is currently ${score.score} (${score.level.name.lowercase()})."
            }
        }

        // Heart rate queries
        if ("heart" in q || " hr" in q || "bpm" in q) {
            return "Your current heart rate is $heartRate bpm."
        }

        // SpO₂ queries
        if ("spo2" in q || "oxygen" in q || "saturation" in q) {
            return "Your blood oxygen saturation is currently $spO2%."
        }

        // Temperature queries
        if ("temp" in q || "temperature" in q) {
            return "Your skin temperature is ${"%.1f".format(skinTempC)}°C."
        }

        // All scores summary
        if ("all" in q || "overview" in q || "summary" in q) {
            return riskScores.joinToString(". ") {
                "${it.type.displayName}: ${it.score} (${it.level.name.lowercase()})"
            }
        }

        // Default
        return "Based on your current readings — HR $heartRate bpm, SpO₂ $spO2%, " +
               "skin temp ${"%.1f".format(skinTempC)}°C — everything looks within expected range. " +
               "Ask me about a specific score for more details."
    }
}
