package com.bipss.app.data.environmental

import android.util.Log
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.domain.environmental.*
import kotlinx.coroutines.flow.*

private const val TAG = "DisasterAlertRepo"

/**
 * 3-tier environmental data pipeline (IMD → CPCB → Open-Meteo).
 * Each tier falls back to the next if unavailable. Cached values used when offline.
 */
class DisasterAlertRepository(
    private val imdParser:      ImdRssParser,
    private val cpcbFetcher:    CpcbAqiFetcher,
    private val openMeteo:      OpenMeteoFetcher,
    private val openWeatherMap: OpenWeatherMapFetcher,
    private val prefsRepo:      UserPreferencesRepository
) {
    /**
     * Fetches a fresh [EnvironmentalSnapshot]. Falls back gracefully at each tier.
     * Always persists the result to DataStore for offline access.
     */
    suspend fun fetchAndCache(): EnvironmentalSnapshot {
        Log.d(TAG, "Fetching environmental snapshot…")

        // Tier 1: IMD CAP RSS (bulk disaster alerts — see SachetCapFetcher's doc
        // comment for why Sachet isn't the automatic bulk source yet)
        val alerts   = imdParser.fetchImdAlerts()
        val severity = alerts.maxOfOrNull { detectSeverity(it) } ?: AlertSeverity.NONE

        // Tier 2: CPCB AQI (requires API key)
        val aqiSnapshot = cpcbFetcher.fetchAqi()

        // Tier 3a: OpenWeatherMap (requires API key + One Call 3.0 enabled) —
        // richer data (pressure/wind), tried first when configured.
        val owmWeather = openWeatherMap.fetchCurrent()
        // Tier 3b: Open-Meteo (no auth) — automatic fallback, no pressure/wind.
        val openMeteoWeather = if (owmWeather == null) openMeteo.fetchCurrent() else null

        val snapshot = EnvironmentalSnapshot(
            ambientTempC    = owmWeather?.tempC      ?: openMeteoWeather?.tempC      ?: 28f,
            humidityPercent = owmWeather?.humidity   ?: openMeteoWeather?.humidity   ?: 60f,
            uvIndex         = owmWeather?.uvIndex    ?: openMeteoWeather?.uvIndex    ?: 4f,
            pressureHpa     = owmWeather?.pressureHpa,
            windSpeedKph    = owmWeather?.windSpeedKph,
            aqiValue        = aqiSnapshot?.aqiValue ?: 80,
            aqiRiskLevel    = aqiSnapshot?.riskLevel ?: AqiRiskLevel.MODERATE,
            dominantPollutant = aqiSnapshot?.dominantPollutant ?: "PM2.5",
            activeAlerts    = alerts,
            alertSeverity   = severity,
            fetchedAtMs     = System.currentTimeMillis(),
            isStale         = false,
            isCachedData    = false,
            dataAgeMs       = 0L
        )

        // Persist to DataStore
        prefsRepo.cacheEnvironmentalSnapshot(
            tempC          = snapshot.ambientTempC,
            humidity       = snapshot.humidityPercent,
            uvIndex        = snapshot.uvIndex,
            aqiValue       = snapshot.aqiValue,
            aqiPollutant   = snapshot.dominantPollutant,
            alertTitle     = alerts.firstOrNull()?.title ?: "",
            alertSeverity  = severity.name,
            fetchedAtMs    = snapshot.fetchedAtMs
        )

        return snapshot
    }

    /** Reads the cached snapshot from DataStore. Returns null if never fetched. */
    suspend fun getCachedSnapshot(): EnvironmentalSnapshot? {
        val cached = prefsRepo.cachedEnvironmentalData.first() ?: return null
        val ageMs  = System.currentTimeMillis() - cached.fetchedAtMs
        return EnvironmentalSnapshot(
            ambientTempC    = cached.tempC,
            humidityPercent = cached.humidity,
            uvIndex         = cached.uvIndex,
            aqiValue        = cached.aqiValue,
            aqiRiskLevel    = AqiRiskLevel.fromAqi(cached.aqiValue),
            dominantPollutant = cached.aqiPollutant,
            activeAlerts    = emptyList(),
            alertSeverity   = runCatching { AlertSeverity.valueOf(cached.alertSeverity) }
                                  .getOrDefault(AlertSeverity.NONE),
            fetchedAtMs     = cached.fetchedAtMs,
            isStale         = ageMs > EnvironmentalSnapshot.STALE_THRESHOLD_MS,
            isCachedData    = true,
            dataAgeMs       = ageMs
        )
    }

    private fun detectSeverity(alert: ImdAlert): AlertSeverity {
        val t = alert.title.lowercase()
        return when {
            "extreme" in t || "red" in t    -> AlertSeverity.EXTREME
            "warning" in t || "orange" in t  -> AlertSeverity.WARNING
            "watch" in t || "yellow" in t    -> AlertSeverity.WATCH
            else                             -> AlertSeverity.ADVISORY
        }
    }
}
