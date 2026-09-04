package com.bipss.app.data.environmental

import android.util.Log
import com.bipss.app.domain.environmental.AqiSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "CpcbAqiFetcher"

/**
 * Fetches real-time AQI data from the CPCB data.gov.in endpoint.
 *
 * TODO: A registered API key from data.gov.in is required. Store it in
 *       local.properties as CPCB_AQI_API_KEY — never hardcode it in source.
 *       If the key is empty (prototype mode), this function returns null
 *       and [DisasterAlertRepository] falls back to Open-Meteo.
 */
class CpcbAqiFetcher(
    private val okHttpClient: OkHttpClient,
    private val apiKey:       String
) {
    private val CPCB_URL = "https://api.data.gov.in/resource/3b01bcb8-0b14-4abf-b6f2-c1bfd384ba69" +
                           "?api-key=$apiKey&format=json&limit=1&filters[city]=Delhi"

    suspend fun fetchAqi(city: String = "Delhi"): AqiSnapshot? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey == "your_key_here") {
            Log.w(TAG, "CPCB API key not configured — skipping AQI fetch")
            return@withContext null
        }
        try {
            val url     = CPCB_URL.replace("Delhi", city)
            val request = Request.Builder().url(url).build()
            val resp    = okHttpClient.newCall(request).execute()
            if (!resp.isSuccessful) return@withContext null
            val body = resp.body?.string() ?: return@withContext null
            parseResponse(body, city)
        } catch (e: Exception) {
            Log.e(TAG, "CPCB fetch error: ${e.message}")
            null
        }
    }

    private fun parseResponse(json: String, city: String): AqiSnapshot? {
        return try {
            val root    = Json { ignoreUnknownKeys = true }.parseToJsonElement(json).jsonObject
            val records = root["records"]?.jsonArray ?: return null
            val first   = records.firstOrNull()?.jsonObject ?: return null
            AqiSnapshot(
                stationCity       = city,
                aqiValue          = first["aqi_index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                dominantPollutant = first["prominent_pollutant"]?.jsonPrimitive?.content ?: "PM2.5"
            )
        } catch (e: Exception) {
            Log.e(TAG, "CPCB parse error: ${e.message}")
            null
        }
    }
}

/**
 * Fetches temp/humidity/pressure/wind/UV from OpenWeatherMap's One Call 3.0
 * API. Requires OWM_API_KEY in local.properties AND that key's "One Call by
 * Call" product enabled on the OpenWeatherMap account dashboard — a plain
 * API key alone returns 401 for this endpoint, it's a separate (free-tier
 * available) subscription toggle, not just a key. If either is missing this
 * returns null and [DisasterAlertRepository] falls back to Open-Meteo,
 * which has no such extra step but also no pressure/wind data.
 */
class OpenWeatherMapFetcher(
    private val okHttpClient: OkHttpClient,
    private val apiKey:       String
) {
    private val BASE_URL = "https://api.openweathermap.org/data/3.0/onecall" +
                           "?lat=28.6139&lon=77.2090" +   // Delhi default
                           "&exclude=minutely,hourly,daily,alerts" +
                           "&units=metric&appid=$apiKey"

    data class OwmSnapshot(val tempC: Float, val humidity: Float, val pressureHpa: Float, val windSpeedKph: Float, val uvIndex: Float)

    suspend fun fetchCurrent(): OwmSnapshot? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey == "your_key_here") {
            Log.w(TAG, "OpenWeatherMap API key not configured — skipping")
            return@withContext null
        }
        try {
            val request = Request.Builder().url(BASE_URL).build()
            val resp    = okHttpClient.newCall(request).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "OpenWeatherMap fetch failed: HTTP ${resp.code}")
                return@withContext null
            }
            val body = resp.body?.string() ?: return@withContext null
            parseResponse(body)
        } catch (e: Exception) {
            Log.e(TAG, "OpenWeatherMap fetch error: ${e.message}")
            null
        }
    }

    private fun parseResponse(json: String): OwmSnapshot? {
        return try {
            val root    = Json { ignoreUnknownKeys = true }.parseToJsonElement(json).jsonObject
            val current = root["current"]?.jsonObject ?: return null
            OwmSnapshot(
                tempC        = current["temp"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 28f,
                humidity     = current["humidity"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 60f,
                pressureHpa  = current["pressure"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 1013f,
                // wind_speed from One Call 3.0 is in m/s (units=metric) — convert to km/h
                windSpeedKph = (current["wind_speed"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f) * 3.6f,
                uvIndex      = current["uvi"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 4f
            )
        } catch (e: Exception) {
            Log.e(TAG, "OpenWeatherMap parse error: ${e.message}")
            null
        }
    }
}

/**
 * Fetches ambient temperature/humidity/UV from Open-Meteo (no API key required).
 *
 * This is the default environmental data source — free, no auth, GDPR-compliant.
 * Falls back to cached values if unavailable.
 */
class OpenMeteoFetcher(private val okHttpClient: OkHttpClient) {

    private val BASE_URL = "https://api.open-meteo.com/v1/forecast" +
                           "?latitude=28.6139&longitude=77.2090" +   // Delhi default
                           "&current=temperature_2m,relative_humidity_2m,uv_index" +
                           "&timezone=auto&forecast_days=1"

    data class OpenMeteoSnapshot(val tempC: Float, val humidity: Float, val uvIndex: Float)

    suspend fun fetchCurrent(): OpenMeteoSnapshot? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(BASE_URL).build()
            val resp    = okHttpClient.newCall(request).execute()
            if (!resp.isSuccessful) return@withContext null
            val body = resp.body?.string() ?: return@withContext null
            parseResponse(body)
        } catch (e: Exception) {
            Log.e(TAG, "Open-Meteo fetch error: ${e.message}")
            null
        }
    }

    private fun parseResponse(json: String): OpenMeteoSnapshot? {
        return try {
            val root    = Json { ignoreUnknownKeys = true }.parseToJsonElement(json).jsonObject
            val current = root["current"]?.jsonObject ?: return null
            OpenMeteoSnapshot(
                tempC    = current["temperature_2m"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 28f,
                humidity = current["relative_humidity_2m"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 60f,
                uvIndex  = current["uv_index"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 4f
            )
        } catch (e: Exception) {
            null
        }
    }
}
