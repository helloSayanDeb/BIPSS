package com.bipss.app.data.environmental

import android.util.Log
import com.bipss.app.domain.environmental.AqiIndex
import com.bipss.app.domain.environmental.AqiSnapshot
import com.bipss.app.domain.environmental.haversineKm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

private const val TAG = "EnvFetchers"

private val lenientJson = Json { ignoreUnknownKeys = true }

/** Reads a field as a String whether the feed encoded it as a string or a number. */
private fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNullSafe()

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" && it != "NA" }

private fun JsonObject.float(key: String): Float? = str(key)?.toFloatOrNull()
private fun JsonObject.double(key: String): Double? = str(key)?.toDoubleOrNull()

/**
 * The pollutant row's average value. In this feed that value is the
 * pollutant's **AQI sub-index**, not a concentration (see [AqiIndex]).
 *
 * The live feed names these `avg_value` / `min_value` / `max_value`. Several
 * published descriptions of this resource use `pollutant_avg` instead, so both
 * spellings are accepted: reading only the documented name yields null for
 * every row and an AQI that never computes - silently, because a missing field
 * is indistinguishable from a station that simply did not report.
 */
private fun JsonObject.pollutantAvg(): Float? =
    float("avg_value") ?: float("pollutant_avg")

/**
 * Builds the data.gov.in request URL. Pure, so the encoding is pinned by tests.
 *
 * The state filter has to carry a real space ("West Bengal"). The previous
 * code used [URLEncoder], which is HTML-form encoding and turns the space into
 * '+'; data.gov.in does not decode '+' in this filter, matched the literal
 * "West+Bengal", and returned zero records - so the AQI read "—" with no error
 * anywhere. Query values are now percent-encoded with %20.
 */
object CpcbUrlBuilder {
    const val RESOURCE = "3b01bcb8-0b14-4abf-b6f2-c1bfd384ba69"
    private const val BASE = "https://api.data.gov.in/resource/$RESOURCE"

    fun build(apiKey: String, state: String?, limit: Int): String = buildString {
        append(BASE)
        append("?api-key=").append(encodeQueryValue(apiKey))
        append("&format=json&limit=").append(limit)
        state?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("&filters[state]=").append(encodeQueryValue(it))
        }
    }

    /** RFC 3986 percent-encoding for a query value: a space becomes %20, never '+'. */
    fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/**
 * Real-time AQI from the CPCB feed on data.gov.in
 * (resource 3b01bcb8-0b14-4abf-b6f2-c1bfd384ba69).
 *
 * The resource returns one row *per pollutant per station* - `pollutant_id`
 * plus `min_value` / `avg_value` / `max_value`, where the values are that
 * pollutant's sub-index - so the station AQI is the largest sub-index
 * ([AqiIndex.fromSubIndices]). There is no aqi_index field to read.
 *
 * Requires a data.gov.in key in local.properties as CPCB_AQI_API_KEY. Without
 * one this returns null and the app shows an em dash - it never substitutes a
 * plausible-looking number.
 */
class CpcbAqiFetcher(
    private val okHttpClient: OkHttpClient,
    private val apiKey:       String
) {
    private companion object {
        const val LIMIT = 500
        /** A station further away than this is not measuring the air the user is in. */
        const val MAX_STATION_KM = 50.0
    }

    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && apiKey != "your_key_here" && apiKey != "YOUR_KEY"

    /**
     * Fetches every station in [state] and returns the nearest one within
     * [MAX_STATION_KM]. Returns null without coordinates (there is then no basis
     * for calling any station "yours") and when no station is close enough.
     */
    /** Why the last [fetchAqi] returned what it did - lets setup say "no station here" rather than "failed". */
    enum class Outcome { NOT_RUN, OK, NOT_CONFIGURED, NO_LOCATION, FAILED, NO_STATION_NEARBY }

    @Volatile var lastOutcome: Outcome = Outcome.NOT_RUN
        private set

    suspend fun fetchAqi(
        state: String?,
        lat:   Double?,
        lon:   Double?
    ): AqiSnapshot? = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            Log.w(TAG, "CPCB API key not configured - AQI will read as unavailable")
            lastOutcome = Outcome.NOT_CONFIGURED
            return@withContext null
        }
        if (lat == null || lon == null) {
            Log.w(TAG, "No location available - not querying CPCB")
            lastOutcome = Outcome.NO_LOCATION
            return@withContext null
        }
        lastOutcome = Outcome.FAILED
        try {
            val url = CpcbUrlBuilder.build(apiKey, state, LIMIT)
            val resp = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
            resp.use {
                if (!it.isSuccessful) {
                    Log.w(TAG, "CPCB fetch failed: HTTP ${it.code}")
                    return@withContext null
                }
                val body = it.body?.string() ?: return@withContext null
                parseNearestStation(body, lat, lon)
            }
        } catch (e: Exception) {
            Log.e(TAG, "CPCB fetch error: ${e.message}")
            null
        }
    }

    private fun parseNearestStation(json: String, lat: Double, lon: Double): AqiSnapshot? = try {
        val records = lenientJson.parseToJsonElement(json).jsonObject["records"]?.jsonArray
        if (records == null || records.isEmpty()) {
            Log.w(TAG, "CPCB returned no records")
            null
        } else {
            // Group the per-pollutant rows back into stations, then take the
            // closest one that is actually near the user.
            val nearest = records.mapNotNull { it.jsonObject }
                .groupBy { it.str("station") ?: it.str("city") ?: "unknown" }
                .mapNotNull { (station, rows) ->
                    val sLat = rows.firstNotNullOfOrNull { it.double("latitude") } ?: return@mapNotNull null
                    val sLon = rows.firstNotNullOfOrNull { it.double("longitude") } ?: return@mapNotNull null
                    Triple(station, rows, haversineKm(lat, lon, sLat, sLon))
                }
                .minByOrNull { it.third }

            when {
                nearest == null -> null
                nearest.third > MAX_STATION_KM -> {
                    Log.w(TAG, "Nearest CPCB station is ${nearest.third.toInt()} km away - not using it")
                    lastOutcome = Outcome.NO_STATION_NEARBY
                    null
                }
                else -> toSnapshot(nearest.first, nearest.second, nearest.third)
                    ?.also { lastOutcome = Outcome.OK }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "CPCB parse error: ${e.message}")
        null
    }

    private fun toSnapshot(stationName: String, rows: List<JsonObject>, distanceKm: Double): AqiSnapshot? {
        val readings = rows.mapNotNull { row ->
            val id = row.str("pollutant_id") ?: return@mapNotNull null
            AqiIndex.PollutantReading(id, row.pollutantAvg())
        }
        // Null, not zero: no usable pollutant row means we do not know the AQI.
        val computed = AqiIndex.fromSubIndices(readings) ?: return null
        if (!computed.meetsCpcbMinimum) {
            // CPCB itself publishes no index for a station reporting fewer than
            // three pollutants (one of them particulate), so neither do we.
            Log.w(TAG, "$stationName reported ${computed.subIndices.keys} - below CPCB's minimum for an index")
        }
        return AqiSnapshot(
            stationCity         = rows.firstNotNullOfOrNull { it.str("city") } ?: stationName,
            stationName         = stationName,
            aqiValue            = computed.aqi.takeIf { computed.meetsCpcbMinimum },
            dominantPollutant   = computed.dominantPollutant.takeIf { computed.meetsCpcbMinimum },
            pollutantSubIndices = computed.subIndices,
            distanceKm          = distanceKm,
            latitude            = rows.firstNotNullOfOrNull { it.double("latitude") },
            longitude           = rows.firstNotNullOfOrNull { it.double("longitude") }
        )
    }
}

/**
 * Weather from Open-Meteo - the app's only weather source. No API key.
 *
 * Two fixes here matter. The coordinates are now the user's, not a hardcoded
 * Delhi pair - a Kolkata demo was previously reporting Delhi's weather. And
 * every field is nullable, so a partial response yields nulls rather than the
 * old 28 C / 60 % / UV 4.0 substitutions.
 *
 * The `uv_index` field name was verified live on 8 Sep 2026; a genuine 0.0 at
 * night is correct and is displayed as such.
 */
class OpenMeteoFetcher(private val okHttpClient: OkHttpClient) {

    data class OpenMeteoSnapshot(
        val tempC:        Float?,
        val humidity:     Float?,
        val uvIndex:      Float?,
        val pressureHpa:  Float?,
        val windSpeedKph: Float?
    )

    suspend fun fetchCurrent(lat: Double, lon: Double): OpenMeteoSnapshot? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                // pressure_msl (sea level), not surface_pressure: the station-level
                // value drops with altitude and is not what a weather report quotes.
                "&current=temperature_2m,relative_humidity_2m,uv_index,pressure_msl,wind_speed_10m" +
                "&timezone=auto&forecast_days=1"
            val resp = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
            resp.use {
                if (!it.isSuccessful) {
                    Log.w(TAG, "Open-Meteo fetch failed: HTTP ${it.code}")
                    return@withContext null
                }
                val current = lenientJson.parseToJsonElement(it.body?.string() ?: return@withContext null)
                    .jsonObject["current"]?.jsonObject
                    ?: return@withContext null.also { Log.w(TAG, "Open-Meteo answer had no current block") }
                OpenMeteoSnapshot(
                    tempC        = current.float("temperature_2m"),
                    humidity     = current.float("relative_humidity_2m"),
                    uvIndex      = current.float("uv_index"),
                    pressureHpa  = current.float("pressure_msl"),
                    // Open-Meteo reports wind in km/h by default.
                    windSpeedKph = current.float("wind_speed_10m")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Open-Meteo fetch error: ${e.message}")
            null
        }
    }
}
