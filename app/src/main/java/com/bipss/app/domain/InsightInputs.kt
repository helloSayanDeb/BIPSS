package com.bipss.app.domain

import com.bipss.app.domain.ali.AliResult
import com.bipss.app.domain.environmental.EnvironmentalSnapshot
import com.bipss.app.domain.recovery.RecoveryInput
import com.bipss.app.domain.recovery.RecoveryResult

/** Attach the same operands used by computeAll, never a later live reading. */
internal fun insightInputs(
    type: RiskScoreType, scores: List<RiskScore>, hr: Int?, spo2: Int?, skin: Float?,
    personal: RiskScoreCalculator.Personal, env: EnvironmentalSnapshot?, waterHours: Float?,
    recovery: RecoveryResult?, strain: RiskScoreCalculator.Pillar<RiskScoreCalculator.StrainInput>, ali: AliResult?
): List<InsightInput> {
    fun input(key: String, label: String, n: Number?, unit: String = "", detail: String = "", baseline: Double? = null) =
        InsightInput(key, label, n?.toDouble()?.takeIf { it.isFinite() }, unit, detail, baseline)
    val environmentNote = if (env?.isStale == true) "Older environmental reading" else "Local conditions"
    return when (type) {
        RiskScoreType.HEAT_STRESS -> listOf(
            input("ambient", "Ambient temperature", env?.ambientTempC, "°C", environmentNote),
            input("humidity", "Humidity", env?.humidityPercent, "%", environmentNote),
            input("hr", "Heart rate", hr, "bpm", baseline = personal.restingHr.takeIf { personal.dayN >= 14 }),
            input("skin", "Skin temperature", skin, "°C", baseline = personal.usualSkinTempC.takeIf { personal.dayN >= 14 }),
            input("water", "Time since logged drink", waterHours, "h", "A drink log does not measure hydration"),
            InsightInput("alerts", "Weather alerts", isNumeric = false, detail = if (env != null && env.alertsCheckedAtMs > 0) env.alertSeverity.label else "Not checked")
        )
        RiskScoreType.RESPIRATORY -> listOf(
            input("spo2", "Blood oxygen", spo2, "%"), input("hr", "Heart rate", hr, "bpm"),
            input("ratio", "Heart rate / oxygen ratio", if (hr != null && spo2 != null && spo2 > 0) hr.toDouble() / spo2 else null,
                detail = "A scoring input, not measured breathing rate"),
            input("aqi", "Outdoor AQI", env?.aqiValue, detail = if (env?.aqiSource == com.bipss.app.domain.environmental.AqiSource.MODEL) "Estimated outdoor air quality" else environmentNote)
        )
        RiskScoreType.FLOOD -> {
            val relevant = env?.takeIf { it.alertsCheckedAtMs > 0 }?.activeAlerts?.filter { a -> listOf("flood", "rain", "cyclone", "storm", "surge", "tide", "thunder", "wind").any { it in (a.category + " " + a.title).lowercase() } }
            listOf(input("warning_count", "Relevant warnings", relevant?.size),
                InsightInput("area", "Area", isNumeric = false, detail = env?.districtName ?: env?.stateName ?: "Location needed"),
                InsightInput("warning_level", "Strongest warning", isNumeric = false, detail = relevant?.maxByOrNull { it.severity.rank }?.severity?.label ?: if (env?.alertsCheckedAtMs != null && env.alertsCheckedAtMs > 0) "No active warning" else "Not checked"),
                InsightInput("checked", "Warnings last checked", isNumeric = false, detail = env?.alertsCheckedAtMs?.takeIf { it > 0 }?.let {
                    java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM, h:mm a"))
                } ?: "Not checked")) +
                relevant.orEmpty().take(5).mapIndexed { i, a -> InsightInput("warning_$i", a.title, isNumeric = false, detail = a.instruction?.takeIf { it.isNotBlank() } ?: a.severity.label) }
        }
        RiskScoreType.READINESS -> scores.filter { it.type in listOf(RiskScoreType.RECOVERY, RiskScoreType.SLEEP, RiskScoreType.STRAIN) || (it.type.isHazard && it.availability == ScoreAvailability.AVAILABLE) }.map {
            input(it.type.name, it.type.displayName, if (it.hasNumber) if (it.type == RiskScoreType.STRAIN) it.valueLabel.replace(',', '.').toDoubleOrNull() else it.score else null,
                "/${it.scaleMax}", if (it.type.isHazard) "Only the highest complete hazard score above 60 lowers readiness" else when (it.type) {
                    RiskScoreType.STRAIN -> "20% weight; more strain lowers readiness"
                    else -> "40% weight; higher supports readiness"
                })
        }
        RiskScoreType.RECOVERY -> RecoveryInput.entries.map { kind ->
            val c = recovery?.components?.firstOrNull { it.input == kind }
            input(kind.key, kind.label, c?.value, when (kind) {
                RecoveryInput.PRV -> "ms"; RecoveryInput.RESTING_HR -> "bpm"; RecoveryInput.SLEEP_DURATION -> "min"; RecoveryInput.SKIN_TEMP -> "°C"
            }, "${(kind.weight * 100).toInt()}% weight when available", c?.baselineMean)
        }
        RiskScoreType.STRAIN -> {
            val s = (strain as? RiskScoreCalculator.Pillar.Value)?.value
            listOf(input("active", "Active minutes", s?.activeMinutes, "min"), input("scored", "Minutes with usable heart rate", s?.scoredMinutes, "min"),
                InsightInput("rest", "Resting heart rate reference", isNumeric = false, detail = if (s == null) "Not available" else if (s.restIsEstimate) "Estimated while your baseline builds" else "Your personal baseline"))
        }
        RiskScoreType.AUTONOMIC_LOAD -> listOf(
            input("prv_z", "Pulse variability below usual", ali?.prvSuppressionZ, "z", "Positive values mean below your still range"),
            input("hr_z", "Still heart rate above usual", ali?.stillHrElevationZ, "z", "Positive values mean above your still range"),
            input("still", "Clean still windows", ali?.recentEpochs, "windows", "30 seconds per window"),
            input("baseline_days", "Baseline coverage", personal.dayN, "days")
        )
        RiskScoreType.SLEEP, RiskScoreType.SLEEP_QUALITY -> emptyList() // Session details attached by the caller.
    }
}
