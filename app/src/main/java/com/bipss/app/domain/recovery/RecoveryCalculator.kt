package com.bipss.app.domain.recovery

import com.bipss.app.domain.BaselineSchedule

data class RecoveryMetrics(
    val restingHr: Int?,
    val baselineHr: Double?,
    val hrvRmssd: Int?,
    val baselineDays: Int
)

data class RecoveryComponent(
    val label: String,
    val score: Int,
    val maxScore: Int,
    val summary: String,
    val isElevated: Boolean
)

data class RecoveryBreakdown(
    val totalScore: Int?,
    val components: List<RecoveryComponent>,
    val missingInputs: List<String>
)

object RecoveryCalculator {
    fun compute(metrics: RecoveryMetrics): RecoveryBreakdown {
        if (metrics.hrvRmssd == null && metrics.restingHr == null) {
            return RecoveryBreakdown(null, emptyList(), listOf("heart rate variability", "resting heart rate"))
        }
        if (metrics.baselineHr == null || !BaselineSchedule.isUsable(metrics.baselineDays)) {
            return RecoveryBreakdown(null, emptyList(), listOf(BaselineSchedule.missingLabel(metrics.baselineDays)))
        }

        var totalEarned = 0f
        var totalPossible = 0f
        val components = mutableListOf<RecoveryComponent>()
        val missing = mutableListOf<String>()

        if (metrics.restingHr != null) {
            val delta = (metrics.restingHr - metrics.baselineHr).toFloat()
            val f = (1f - (delta / 15f)).coerceIn(0f, 1f)
            val points = f * 60f
            totalEarned += points
            totalPossible += 60f
            
            components.add(
                RecoveryComponent(
                    label = "Resting HR",
                    score = points.toInt(),
                    maxScore = 60,
                    summary = if (delta <= 2f) "At Baseline" else "Elevated",
                    isElevated = delta > 2f
                )
            )
        } else {
            missing.add("resting heart rate")
        }

        if (metrics.hrvRmssd != null) {
            val f = ((metrics.hrvRmssd - 20f) / 50f).coerceIn(0f, 1f)
            val points = f * 40f
            totalEarned += points
            totalPossible += 40f
            
            components.add(
                RecoveryComponent(
                    label = "HRV",
                    score = points.toInt(),
                    maxScore = 40,
                    summary = if (f > 0.5f) "Strong" else "Suppressed",
                    isElevated = f <= 0.5f
                )
            )
        } else {
            missing.add("heart rate variability")
        }

        val total = if (totalPossible <= 0f) null else ((totalEarned / totalPossible) * 100f).toInt().coerceIn(0, 100)

        return RecoveryBreakdown(total, components, missing)
    }
}
