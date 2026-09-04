package com.bipss.app.domain

/** Domain models for risk scoring (Plan 7) */

data class RiskScore(
    val type:        RiskScoreType,
    val score:       Int,
    val level:       RiskLevel,
    val factors:     List<RiskFactor>,
    val computedAt:  Long = System.currentTimeMillis()
)

enum class RiskScoreType(val displayName: String) {
    HEAT_STRESS("Heat Stress"),
    RESPIRATORY("Respiratory"),
    CARDIOVASCULAR("Cardiovascular")
}

enum class RiskLevel { LOW, MEDIUM, HIGH }

data class RiskFactor(
    val label:      String,
    val isElevated: Boolean   // true → amber chip; false → teal chip
)

data class ChatMessage(
    val id:        String  = java.util.UUID.randomUUID().toString(),
    val content:   String,
    val sender:    Sender,
    val timestamp: Long    = System.currentTimeMillis(),
    val isError:   Boolean = false
)

enum class Sender { USER, AI }
