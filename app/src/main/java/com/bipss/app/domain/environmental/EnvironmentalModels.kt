package com.bipss.app.domain.environmental

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unified environmental snapshot.
 *
 * Every measured field is nullable on purpose. The previous version defaulted
 * temperature to 28 C, humidity to 60 %, UV to 4.0 and AQI to 80, which meant
 * a total network failure still rendered a confident, entirely invented
 * weather report. Null means "we do not have this", and the UI renders an em
 * dash. Nothing here is ever substituted.
 */
data class EnvironmentalSnapshot(
    val ambientTempC:      Float?         = null,
    val humidityPercent:   Float?         = null,
    val uvIndex:           Float?         = null,
    val pressureHpa:       Float?         = null,
    val windSpeedKph:      Float?         = null,

    /** Null when unavailable. Never a placeholder. */
    val aqiValue:          Int?           = null,
    /** ALWAYS derived from [aqiValue] - never set independently. */
    val aqiRiskLevel:      AqiRiskLevel?  = aqiValue?.let { AqiRiskLevel.fromAqi(it) },
    val aqiSource:         AqiSource      = AqiSource.NONE,
    /** Regional station AQI, kept beside the band reading for the indoor/outdoor inference. */
    val regionalAqiValue:  Int?           = null,
    val dominantPollutant: String?        = null,
    /** CPCB per-pollutant sub-indices of the station used (the feed publishes indices, not concentrations). */
    val pollutantSubIndices: Map<String, Int> = emptyMap(),
    val aqiStationName:    String?        = null,
    val aqiStationDistanceKm: Double?     = null,

    val activeAlerts:      List<ImdAlert>       = emptyList(),
    val advisories:        List<HealthAdvisory> = emptyList(),
    /** Highest severity among [activeAlerts]. Per-alert severity lives on the alert. */
    val alertSeverity:     AlertSeverity  = AlertSeverity.NONE,
    /**
     * When the alert feeds were last actually read successfully - 0 when never.
     * Tracked apart from [fetchedAtMs] because weather can refresh while every
     * alert feed is unreachable, and "no alerts" must only ever be claimed for a
     * check that really happened.
     */
    val alertsCheckedAtMs: Long           = 0L,
    /** The feeds that answered in that check, e.g. ["NDMA Sachet"] or ["NDMA Sachet", "IMD"]. */
    val alertSourcesChecked: List<String> = emptyList(),
    /** True when the most recent attempt reached no alert feed; [activeAlerts] are then from the last good check. */
    val alertCheckFailed:  Boolean        = false,

    val districtName:      String?        = null,
    val stateName:         String?        = null,
    val latitude:          Double?        = null,
    val longitude:         Double?        = null,

    val fetchedAtMs:       Long           = 0L,
    val isStale:           Boolean        = true,
    val isCachedData:      Boolean        = true,
    val dataAgeMs:         Long           = Long.MAX_VALUE
) {
    companion object {
        val STALE_THRESHOLD_MS = 2 * 60 * 60 * 1000L

        /** Nothing known yet. Every field null - the honest starting state. */
        val EMPTY = EnvironmentalSnapshot()
    }

    /** True once at least one real environmental measurement exists. */
    val hasAnyReading: Boolean
        get() = ambientTempC != null || humidityPercent != null || aqiValue != null

    val dataAgeLabel: String get() {
        if (dataAgeMs == Long.MAX_VALUE || fetchedAtMs == 0L) return "Never synced"
        val minutes = dataAgeMs / 60_000L
        return when {
            minutes < 1  -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            else         -> "${minutes / 60}h ago"
        }
    }
}

/** Where an AQI number came from. Shown to the user verbatim. */
enum class AqiSource(val label: String) {
    SENSOR ("Band sensor"),
    CPCB   ("CPCB station"),
    BLENDED("Band sensor*"),
    NONE   ("Unavailable")
}

// -- Alerts -----------------------------------------------------------------

/**
 * One disaster alert.
 *
 * Field names mirror what NDMA Sachet's FetchAllAlertDetails endpoint actually
 * returns (verified 8 Sep 2026), not the CAP spec: Sachet exposes a numeric
 * identifier, a free-text area description, a centroid and a covered area in
 * square kilometres rather than a polygon.
 */
@Serializable
data class ImdAlert(
    /** Dedup key. Sachet's numeric identifier as a string, or the title when absent. */
    val identifier:  String,
    val title:       String,
    val description: String,
    val category:    String,
    /** This alert's own severity - NOT the feed-wide maximum. */
    val severity:    AlertSeverity,
    /** District/state names as published, e.g. "Ganga, Farakka, Murshidabad, West Bengal". */
    val areas:       List<String>,
    val pubDateMs:   Long,
    /** Null when the feed gives no expiry. Non-null and past means do not display. */
    val expiresMs:   Long?   = null,
    val capXmlUrl:   String? = null,
    /** The feed's own advice text where present - authoritative, never paraphrased. */
    val instruction: String? = null,
    /** Alert centre, when published. Enables true distance filtering. */
    val centroidLat: Double? = null,
    val centroidLon: Double? = null,
    /** Published coverage in square kilometres, used to derive an effective radius. */
    val areaCoveredSqKm: Double? = null,
    /** ISO language code of the description - Sachet mixes en/hi/te in one feed. */
    val language:    String  = "en",
    val source:      String  = "IMD"
) {
    /** Radius in km implied by the covered area, treating that area as a circle. */
    val effectiveRadiusKm: Double?
        get() = areaCoveredSqKm?.takeIf { it > 0.0 }?.let { sqrt(it / PI) }

    fun isExpiredAt(nowMs: Long): Boolean = expiresMs != null && expiresMs < nowMs

    val areaLabel: String
        get() = when {
            areas.isEmpty() -> "Area not specified"
            areas.size > 3  -> areas.take(2).joinToString(", ") + " +" + (areas.size - 2) + " more"
            else            -> areas.joinToString(", ")
        }
}

/**
 * Severity ladder. The rank is the only ordering used anywhere.
 *
 * IMD and NDMA publish a four-colour code rather than CAP's vocabulary:
 * green = all clear, yellow = watch, orange = alert, red = warning.
 * [fromSachet] maps that; [fromCap] maps genuine CAP feeds.
 */
enum class AlertSeverity(val rank: Int, val label: String) {
    NONE    (0, "No alert"),
    ADVISORY(1, "Advisory"),
    WATCH   (2, "Watch"),
    WARNING (3, "Alert"),
    EXTREME (4, "Warning");

    companion object {
        /** CAP standard vocabulary: Extreme / Severe / Moderate / Minor / Unknown. */
        fun fromCap(s: String?): AlertSeverity = when (s?.trim()?.lowercase()) {
            "extreme"  -> EXTREME
            "severe"   -> WARNING
            "moderate" -> WATCH
            "minor"    -> ADVISORY
            else       -> NONE
        }

        /**
         * Sachet publishes severity as free text ("ALERT", "WATCH", "Yellow",
         * "Orange") alongside a colour ("red" / "orange" / "yellow" / "green").
         * The two disagree often enough that we take the stronger of the pair
         * rather than trusting either alone.
         */
        fun fromSachet(severity: String?, colour: String?): AlertSeverity {
            val bySeverity = when (severity?.trim()?.lowercase()) {
                "red", "warning", "extreme", "severe" -> EXTREME
                "orange", "alert"                     -> WARNING
                "yellow", "watch"                     -> WATCH
                "green", "advisory", "minor"          -> ADVISORY
                else                                  -> NONE
            }
            val byColour = when (colour?.trim()?.lowercase()) {
                "red"    -> EXTREME
                "orange" -> WARNING
                "yellow" -> WATCH
                "green"  -> ADVISORY
                else     -> NONE
            }
            return if (bySeverity.rank >= byColour.rank) bySeverity else byColour
        }
    }
}

/** How close an alert is to the user. Ordinal is the sort key - DIRECT first. */
enum class Relevance(val label: String) {
    DIRECT  ("Your district"),
    NEARBY  ("Your state"),
    REGIONAL("Nearby")
}

/** An alert plus the personalisation the app computed for it. */
data class RankedAlert(
    val alert:      ImdAlert,
    val relevance:  Relevance,
    /** 0f..1f. severity x proximity x personal vulnerability. */
    val score:      Float,
    val distanceKm: Double? = null,
    /**
     * Set only when the user's own score in the matching domain is genuinely
     * elevated. Null otherwise - never invented to make a card look smarter.
     */
    val personalNote: String? = null
)

// -- Air quality ------------------------------------------------------------

/**
 * A CPCB station reading.
 *
 * data.gov.in resource 3b01bcb8 returns one row per pollutant whose values are
 * that pollutant's sub-index; the station AQI is the largest of them. [aqiValue]
 * is null when the station reported too few pollutants for CPCB's own validity
 * rule - the sub-indices it did report are still in [pollutantSubIndices].
 */
data class AqiSnapshot(
    val stationCity:         String,
    val stationName:         String? = null,
    val aqiValue:            Int?,
    val dominantPollutant:   String?,
    val pollutantSubIndices: Map<String, Int> = emptyMap(),
    val distanceKm:          Double? = null,
    val latitude:            Double? = null,
    val longitude:           Double? = null,
    val fetchedAtMs:         Long    = System.currentTimeMillis()
) {
    val riskLevel: AqiRiskLevel? get() = aqiValue?.let { AqiRiskLevel.fromAqi(it) }
}

/** CPCB national AQI bands. Boundaries are the published ones. */
enum class AqiRiskLevel(val label: String) {
    GOOD        ("good"),
    SATISFACTORY("satisfactory"),
    MODERATE    ("moderate"),
    POOR        ("poor"),
    VERY_POOR   ("very poor"),
    SEVERE      ("severe");

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

/**
 * A value that may come from the band sensor, a regional API, or both.
 *
 * When both exist we display the sensor value (it is what the user is actually
 * breathing) and footnote the regional average, rather than silently averaging
 * two readings that mean different things.
 */
data class BlendedReading(
    val sensorValue: Float?,
    val apiValue:    Float?,
    val source:      AqiSource
) {
    val hasBoth:      Boolean get() = sensorValue != null && apiValue != null
    /** What to show: the sensor when present, otherwise the API. */
    val displayValue: Float?  get() = sensorValue ?: apiValue
    /** Mean of the two, only when both exist. Never shown as the headline. */
    val blendedValue: Float?  get() = if (hasBoth) (sensorValue!! + apiValue!!) / 2f else null
    val divergence:   Float?  get() = if (hasBoth) abs(sensorValue!! - apiValue!!) else null

    companion object {
        fun of(sensor: Float?, api: Float?): BlendedReading = BlendedReading(
            sensorValue = sensor,
            apiValue    = api,
            source      = when {
                sensor != null && api != null -> AqiSource.BLENDED
                sensor != null                -> AqiSource.SENSOR
                api    != null                -> AqiSource.CPCB
                else                          -> AqiSource.NONE
            }
        )
    }
}

// -- Health advisories ------------------------------------------------------

/**
 * A disease-outbreak advisory.
 *
 * The scope is displayed on every card. These sources are national or global;
 * labelling them honestly is the difference between a real feature and a
 * claim of district-level surveillance the app does not have.
 */
@Serializable
data class HealthAdvisory(
    val id:          String,
    val title:       String,
    val summary:     String,
    val source:      String,
    val scope:       AdvisoryScope,
    val publishedMs: Long,
    val url:         String? = null
)

@Serializable
enum class AdvisoryScope(val label: String) {
    GLOBAL  ("Global advisory"),
    NATIONAL("India"),
    REGIONAL("Your state"),
    LOCAL   ("Your district")
}

// -- Indoor / outdoor inference --------------------------------------------

enum class EnvironmentContext { INDOOR, OUTDOOR, UNCERTAIN }

data class ContextInference(
    val context:    EnvironmentContext,
    val confidence: Float,
    val reason:     String,
    val sensorAqi:  Int?,
    val apiAqi:     Int?
) {
    /** Only worth showing when both inputs existed. */
    val isConclusive: Boolean get() = context != EnvironmentContext.UNCERTAIN
}

// -- Geo --------------------------------------------------------------------

/** Great-circle distance in kilometres. Used for alert proximity. */
fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}
