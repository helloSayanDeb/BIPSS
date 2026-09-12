package com.bipss.app.domain.strain

import com.bipss.app.data.db.dao.MetricStats

data class StrainBreakdown(
    val totalScore: Int?,
    val activityLevel: String,
    val missingInputs: List<String>
)

object StrainCalculator {
    fun compute(stats: MetricStats?): StrainBreakdown {
        if (stats == null || stats.sampleCount == 0) {
            return StrainBreakdown(null, "Unknown", listOf("activity data"))
        }

        // Extremely simplified strain calculation using ACCEL_MAG
        val avgMag = stats.avgValue ?: 0f
        
        // Assume avgMag is loosely related to activity intensity
        // Very rough heuristic mapping to a 0-100 score
        val rawScore = (avgMag * 20f).toInt().coerceIn(0, 100)
        
        val level = when {
            rawScore > 80 -> "High"
            rawScore > 40 -> "Moderate"
            else -> "Low"
        }

        return StrainBreakdown(
            totalScore = rawScore,
            activityLevel = level,
            missingInputs = emptyList()
        )
    }
}
