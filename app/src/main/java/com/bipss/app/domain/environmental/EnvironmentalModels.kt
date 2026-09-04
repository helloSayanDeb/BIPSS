package com.bipss.app.domain.environmental

/**
 * Environmental data model — replaces the Plan 7 EnvironmentalData placeholder.
 * Unified snapshot from IMD, CPCB, and Open-Meteo sources.
 */
data class EnvironmentalSnapshot(
    val ambientTempC:       Float           = 28f,
    val humidityPercent:    Float           = 60f,
    val uvIndex:            Float           = 4f,
    // Only populated when OpenWeatherMap is the active source (needs
    // OWM_API_KEY) — Open-Meteo's free tier doesn't return these, so they
    // stay null under the no-key fallback rather than showing a fake 0.
    val pressureHpa:        Float?          = null,
    val windSpeedKph:       Float?          = null,
    val aqiValue:           Int             = 80,
    val aqiRiskLevel:       AqiRiskLevel    = AqiRiskLevel.SATISFACTORY,
    val dominantPollutant:  String          = "PM2.5",
    val activeAlerts:       List<ImdAlert>  = emptyList(),
    val alertSeverity:      AlertSeverity   = AlertSeverity.NONE,
    val fetchedAtMs:        Long            = 0L,
    val isStale:            Boolean         = true,
    val isCachedData:       Boolean         = true,
    val dataAgeMs:          Long            = Long.MAX_VALUE
) {
    companion object {
        val STALE_THRESHOLD_MS = 2 * 60 * 60 * 1000L

        val PLACEHOLDER = EnvironmentalSnapshot()  // all defaults = placeholder
    }

    val dataAgeLabel: String get() {
        if (dataAgeMs == Long.MAX_VALUE) return "Never synced"
        val minutes = dataAgeMs / 60_000L
        return when {
            minutes < 1  -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            else         -> "${minutes / 60}h ago"
        }
    }
}

data class ImdAlert(
    val title:      String,
    val description: String,
    val category:   String,
    val pubDateMs:  Long,
    val capXmlUrl:  String
)

enum class AlertSeverity { NONE, ADVISORY, WATCH, WARNING, EXTREME }

data class AqiSnapshot(
    val stationCity:        String,
    val aqiValue:           Int,
    val dominantPollutant:  String,
    val fetchedAtMs:        Long = System.currentTimeMillis()
) {
    val riskLevel: AqiRiskLevel get() = AqiRiskLevel.fromAqi(aqiValue)
}

enum class AqiRiskLevel {
    GOOD, SATISFACTORY, MODERATE, POOR, VERY_POOR, SEVERE;

    companion object {
        fun fromAqi(aqi: Int): AqiRiskLevel = when {
            aqi <= 50  -> GOOD
            aqi <= 100 -> SATISFACTORY
            aqi <= 200 -> MODERATE
            aqi <= 300 -> POOR
            aqi <= 400 -> VERY_POOR
            else       -> SEVERE
        }
    }
}
