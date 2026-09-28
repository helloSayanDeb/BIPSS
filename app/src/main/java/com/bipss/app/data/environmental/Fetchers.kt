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
 * The station's average concentration for its pollutant row.
 *
 * The live feed names these `avg_value` / `min_value` / `max_value`. Several
 * published descriptions of this resource (and its own field catalogue) use
 * `pollutant_avg` instead, so both spellings are accepted: reading only the
 * documented name yields null for every row and an AQI that never computes -
 * which is silent, because a missing field is indistinguishable from a station
 * that simply did not report.
 */
private fun JsonObject.pollutantAvg(): Float? =
    float("avg_value") ?: float("pollutant_avg")

/**
 * Real-time AQI from the CPCB feed on data.gov.in
 * (resource 3b01bcb8-0b14-4abf-b6f2-c1bfd384ba69).
 *
 * The previous implementation read fields named `aqi_index` and
 * `prominent_pollutant`. **Neither field exists in this resource.** It returns
 * one row *per pollutant per station* - `pollutant_id` plus `min_value` /
 * `avg_value` / `max_value` - so there is no index to read: the AQI has to be
 * computed from the per-pollutant sub-indices (see [AqiIndex]). With a valid
 * key the old code would still have reported 0.
 *
 * Verified against the live feed on 8 Sep 2026: a `filters[state]=West Bengal`
 * query returns 154 rows across 7 Kolkata stations plus Howrah, Durgapur,
 * Asansol and Siliguri.
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
        const val RESOURCE = "3b01bcb8-0b14-4abf-b6f2-c1bfd384ba69"
        const val LIMIT = 500
    }

    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && apiKey != "your_key_here" && apiKey != "YOUR_KEY"

    /**
     * Fetches every station in [state] and returns the one nearest to the
     * supplied coordinates. Returns null when no coordinates are available:
     * without them there is no basis for calling any station "yours".
     */
    suspend fun fetchAqi(
        state: String?,
        lat:   Double?,
        lon:   Double?
    ): AqiSnapshot? = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            Log.w(TAG, "CPCB API key not configured - AQI will read as unavailable")
            return@withContext null
        }
        try {
            val url = buildString {
                append("https://api.data.gov.in/resource/").append(RESOURCE)
                append("?api-key=").append(apiKey)
                append("&format=json&limit=").append(LIMIT)
                // The state filter matches the literal published value, which
                // contains spaces ("West Bengal"). Substituting underscores
                // returns an empty record set rather than an error, so the
                // failure is silent - verified against the live feed.
                state?.takeIf { it.isNotBlank() }?.let {
                    append("&filters[state]=").append(URLEncoder.encode(it.trim(), "UTF-8"))
                }
            }
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

    private fun parseNearestStation(json: String, lat: Double?, lon: Double?): AqiSnapshot? = try {
        val records = lenientJson.parseToJsonElement(json).jsonObject["records"]?.jsonArray
        if (records == null || records.isEmpty()) null
        else {
            // Group the per-pollutant rows back into stations.
            val byStation = records.mapNotNull { it.jsonObject }
                .groupBy { it.str("station") ?: it.str("city") ?: "unknown" }

            val chosen = if (lat != null && lon != null) {
                byStation.entries.minByOrNull { (_, rows) ->
                    val sLat = rows.firstNotNullOfOrNull { it.double("latitude") }
                    val sLon = rows.firstNotNullOfOrNull { it.double("longitude") }
                    if (sLat == null || sLon == null) Double.MAX_VALUE
                    else haversineKm(lat, lon, sLat, sLon)
                }
            } else {
                // With no coordinates and no state filter the "first" station is
                // simply whichever the feed returned first - observed live as an
                // Andhra Pradesh station being presented to a Kolkata user as
                // their air quality. A reading from an arbitrary station a
                // thousand kilometres away is not a measurement of anything the
                // user is breathing, so report nothing instead.
                Log.w(TAG, "No location available - declining to pick a station")
                null
            }

            chosen?.let { (stationName, rows) -> toSnapshot(stationName, rows) }
        }
    } catch (e: Exception) {
        Log.e(TAG, "CPCB parse error: ${e.message}")
        null
    }

    private fun toSnapshot(stationName: String, rows: List<JsonObject>): AqiSnapshot? {
        val readings = rows.mapNotNull { row ->
            val id = row.str("pollutant_id") ?: return@mapNotNull null
            AqiIndex.PollutantReading(id, row.pollutantAvg())
        }
        // Null, not zero: no usable pollutant row means we do not know the AQI.
        val computed = AqiIndex.compute(readings) ?: return null

        fun avgOf(id: String) = rows
            .firstOrNull { it.str("pollutant_id")?.equals(id, true) == true }
            ?.pollutantAvg()

        return AqiSnapshot(
            stationCity       = rows.firstNotNullOfOrNull { it.str("city") } ?: stationName,
            stationName       = stationName,
            aqiValue          = computed.aqi,
            dominantPollutant = computed.dominantPollutant,
            pm25              = avgOf("PM2.5"),
            pm10              = avgOf("PM10"),
            latitude          = rows.firstNotNullOfOrNull { it.double("latitude") },
            longitude         = rows.firstNotNullOfOrNull { it.double("longitude") }
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
                    .jsonObject["current"]?.jsonObject ?: return@withContext null
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
