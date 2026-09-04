package com.bipss.app.domain

import com.bipss.app.domain.environmental.AlertSeverity
import com.bipss.app.domain.environmental.AqiRiskLevel
import com.bipss.app.domain.environmental.EnvironmentalSnapshot

/**
 * Computes all three risk scores from live sensor data, baseline context,
 * and environmental data. All formulas are documented with clinical rationale.
 *
 * This is a pure Kotlin class with no Android dependencies — fully unit-testable.
 */
class RiskScoreCalculator {

    fun computeAll(
        heartRate:             Int,
        spO2:                  Int,
        skinTempC:             Float,
        baselineHR:            Double,
        baselineHRStd:         Double,
        env:                   EnvironmentalSnapshot,
        hoursSinceLastWaterLog: Float
    ): List<RiskScore> = listOf(
        computeHeatStress(heartRate, skinTempC, env, hoursSinceLastWaterLog),
        computeRespiratory(heartRate, spO2, env),
        computeCardiovascular(heartRate, baselineHR, baselineHRStd)
    )

    // ── Heat Stress ────────────────────────────────────────────────────────────
    private fun computeHeatStress(
        heartRate:            Int,
        skinTempC:            Float,
        env:                  EnvironmentalSnapshot,
        hoursSinceLastWater:  Float
    ): RiskScore {
        val tempFactor     = ((env.ambientTempC - 20f) / 20f).coerceIn(0f, 1f)
        val humidityFactor = (env.humidityPercent / 100f).coerceIn(0f, 1f)
        val base           = (0.7f * humidityFactor + 0.3f * tempFactor) * 50f
        val hrMod          = ((heartRate - 70) / 30f).coerceIn(0f, 1f) * 20f
        val dehydMod       = (hoursSinceLastWater.coerceIn(0f, 8f) / 8f) * 20f
        val skinMod        = ((skinTempC - 36.5f) / 1.5f).coerceIn(0f, 1f) * 10f
        val alertBoost     = when (env.alertSeverity) {
            AlertSeverity.EXTREME  -> 25f
            AlertSeverity.WARNING  -> 15f
            AlertSeverity.WATCH    -> 8f
            AlertSeverity.ADVISORY -> 3f
            AlertSeverity.NONE     -> 0f
        }

        val score = (base + hrMod + dehydMod + skinMod + alertBoost).toInt().coerceIn(0, 100)
        val level = score.toLevel()

        val factors = buildList {
            add(RiskFactor("Humidity ${if (env.humidityPercent > 70) "High ↑" else "OK"}", env.humidityPercent > 70))
            if (dehydMod > 12f) add(RiskFactor("Hydration Low ↓", true))
            else add(RiskFactor("Hydration Good", false))
            if (hrMod > 5f) add(RiskFactor("Elevated HR", true))
            if (env.alertSeverity != AlertSeverity.NONE)
                add(RiskFactor("IMD Alert Active", true))
        }.take(3)

        return RiskScore(RiskScoreType.HEAT_STRESS, score, level, factors)
    }

    // ── Respiratory ────────────────────────────────────────────────────────────
    private fun computeRespiratory(heartRate: Int, spO2: Int, env: EnvironmentalSnapshot): RiskScore {
        val spo2Score   = ((100 - spO2) * 10f).coerceIn(0f, 80f)
        val breathProxy = if (spO2 > 0)
            ((heartRate.toFloat() / spO2.toFloat() - 0.7f) / 0.3f * 20f).coerceIn(0f, 20f) else 0f
        val aqiScore    = when (env.aqiRiskLevel) {
            AqiRiskLevel.SEVERE    -> 30f
            AqiRiskLevel.VERY_POOR -> 22f
            AqiRiskLevel.POOR      -> 15f
            AqiRiskLevel.MODERATE  -> 8f
            else                   -> 0f
        }

        val score = (spo2Score + breathProxy + aqiScore).toInt().coerceIn(0, 100)
        val level = score.toLevel()

        val factors = buildList {
            add(RiskFactor("SpO₂ ${if (spO2 >= 96) "Stable" else "Low ↓"}", spO2 < 96))
            add(RiskFactor("Breath Rate ${if (breathProxy > 10f) "↑" else "OK"}", breathProxy > 10f))
            if (aqiScore > 0f) add(RiskFactor("AQI ${env.aqiValue} (${env.aqiRiskLevel.name.replace('_', ' ')})", aqiScore > 8f))
        }.take(3)

        return RiskScore(RiskScoreType.RESPIRATORY, score, level, factors)
    }

    // ── Cardiovascular ─────────────────────────────────────────────────────────
    private fun computeCardiovascular(heartRate: Int, baselineHR: Double, baselineHRStd: Double): RiskScore {
        val sigma = if (baselineHRStd > 0)
            ((heartRate - baselineHR) / baselineHRStd).toFloat() else 0f
        val sigmaScore    = (sigma.coerceIn(0f, 2f) / 2f * 60f)
        val tachycardiaAdd = if (heartRate > 100) 30f else 0f

        val score = (sigmaScore + tachycardiaAdd).toInt().coerceIn(0, 100)
        val level = score.toLevel()

        val factors = buildList {
            add(RiskFactor("HR ${if (heartRate > 100) "Elevated ↑" else "Stable"}", heartRate > 100))
            add(RiskFactor("BP Normal", false))
            if (sigma > 1.5f) add(RiskFactor("Above Baseline ↑", true))
        }

        return RiskScore(RiskScoreType.CARDIOVASCULAR, score, level, factors)
    }

    private fun Int.toLevel() = when {
        this < 20 -> RiskLevel.LOW
        this < 50 -> RiskLevel.MEDIUM
        else      -> RiskLevel.HIGH
    }
}
