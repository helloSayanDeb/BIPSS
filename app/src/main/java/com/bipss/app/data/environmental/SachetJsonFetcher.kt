package com.bipss.app.data.environmental

import android.util.Log
import com.bipss.app.domain.environmental.AlertSeverity
import com.bipss.app.domain.environmental.ImdAlert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Separators seen in Sachet's area_description field. Newline and carriage
 * return are given by code point to keep this list free of escapes.
 */
private val AREA_SEPARATORS = charArrayOf(',', ';', '|', Char(10), Char(13))

/**
 * Re-inserts the delimiter Sachet sometimes drops between district names.
 *
 * Verified against the live feed: alert 1788853278614017 publishes its area as
 * "KolkataSouth 24 ParganasNorth 24 Parganas" - three districts concatenated
 * with no separator at all. This is upstream data corruption rather than a
 * parsing fault, but rendered verbatim it looks like our bug.
 *
 * The split is deliberately conservative: it only fires on a lower-case letter
 * immediately followed by an upper-case one, which is the exact signature of
 * two run-together names, and it is presentation only - no information is
 * invented and nothing is dropped. Ordinary values are untouched, because
 * "Kolkata district of West Bengal" has a space before every capital.
 */
internal fun splitRunTogetherAreaNames(value: String): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    value.forEachIndexed { i, c ->
        if (i > 0 && c.isUpperCase() && value[i - 1].isLowerCase()) {
            out += current.toString()
            current.clear()
        }
        current.append(c)
    }
    if (current.isNotEmpty()) out += current.toString()
    return out.map { it.trim() }.filter { it.isNotEmpty() }
}

private const val TAG = "SachetJsonFetcher"

private const val SACHET_ALL_ALERTS_URL = "https://sachet.ndma.gov.in/cap_public_website/FetchAllAlertDetails"
private const val SACHET_CAP_XML_URL    = "https://sachet.ndma.gov.in/cap_public_website/FetchXMLFile?identifier="

/** Sachet timestamps are java.util.Date.toString() output, e.g. "Tue Sep 08 01:58:00 IST 2026". */
private const val PATTERN_WITH_ZONE = "EEE MMM dd HH:mm:ss zzz yyyy"
private const val PATTERN_NO_ZONE   = "EEE MMM dd HH:mm:ss yyyy"

private val IST_TOKEN  = Regex("""\sIST\s""")
private val INDIA_ZONE = TimeZone.getTimeZone("Asia/Kolkata")

private val JSON = Json { ignoreUnknownKeys = true }

/**
 * The bulk listing tier [SachetCapFetcher] was missing. FetchAllAlertDetails
 * returns every currently published NDMA alert in one call, so we no longer
 * need an identifier in hand before we can see anything.
 *
 * The response is a bare JSON array, not an object with a wrapper key, and its
 * field types are inconsistent - identifier arrives as a number while
 * area_covered arrives as a quoted string. Everything is therefore read through
 * [stringField] rather than a typed @Serializable class, which would reject the
 * whole payload the first time NDMA changes one field's encoding.
 *
 * NDMA's own docs advertise this endpoint over http; we request https. If that
 * fails we log and return nothing rather than downgrading the transport.
 */
class SachetJsonFetcher(private val okHttpClient: OkHttpClient) {

    suspend fun fetchAll(): List<ImdAlert> = withContext(Dispatchers.IO) {
        try {
            val request  = Request.Builder().url(SACHET_ALL_ALERTS_URL).build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Sachet bulk fetch failed: HTTP ${response.code}")
                return@withContext emptyList()
            }
            val body = response.body?.string() ?: return@withContext emptyList()
            parseAll(body)
        } catch (e: Exception) {
            Log.e(TAG, "Sachet bulk fetch error: ${e.message}")
            emptyList()
        }
    }

    /**
     * One malformed row out of roughly eighty must not cost us the other
     * seventy-nine, so rows are mapped individually and dropped on failure.
     */
    private fun parseAll(json: String): List<ImdAlert> {
        return try {
            val rows   = JSON.parseToJsonElement(json).jsonArray
            val alerts = rows.mapNotNull { element -> (element as? JsonObject)?.let(::toAlert) }
            if (alerts.size < rows.size) {
                Log.w(TAG, "Skipped ${rows.size - alerts.size} of ${rows.size} Sachet rows")
            }
            alerts
        } catch (e: Exception) {
            Log.e(TAG, "Sachet parse error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Sachet has no CAP instruction field, so advice text stays null rather
     * than being synthesised from the warning message - the feed's own words
     * are the only ones we are entitled to present as official advice.
     */
    private fun toAlert(row: JsonObject): ImdAlert? {
        val identifier = row.stringField("identifier")    ?: return null
        val disaster   = row.stringField("disaster_type") ?: return null
        // pubDateMs is non-null in the model and drives ordering and expiry, so a
        // row whose start time will not parse is dropped, never back-filled.
        val startMs    = parseSachetDateMs(row.stringField("effective_start_time")) ?: return null
        val centroid   = parseCentroid(row.stringField("centroid"))

        return ImdAlert(
            identifier      = identifier,
            title           = disaster,
            description     = row.stringField("warning_message").orEmpty(),
            category        = disaster,
            severity        = AlertSeverity.fromSachet(
                                  row.stringField("severity"),
                                  row.stringField("severity_color")
                              ),
            // Sachet is inconsistent about separators: some rows use commas,
            // others newlines or semicolons. Splitting on commas alone left a
            // newline-separated list as one area, which then rendered with the
            // district names run together once Compose collapsed the newline.
            areas           = row.stringField("area_description")
                                 ?.split(*AREA_SEPARATORS)
                                 ?.map { it.trim() }
                                 ?.filter { it.isNotEmpty() }
                                 ?.flatMap { splitRunTogetherAreaNames(it) }
                                 .orEmpty(),
            pubDateMs       = startMs,
            expiresMs       = parseSachetDateMs(row.stringField("effective_end_time")),
            capXmlUrl       = SACHET_CAP_XML_URL + identifier,
            instruction     = null,
            centroidLat     = centroid?.lat,
            centroidLon     = centroid?.lon,
            areaCoveredSqKm = row.stringField("area_covered")?.toDoubleOrNull(),
            language        = row.stringField("actual_lang")  ?: "en",
            source          = row.stringField("alert_source") ?: "NDMA Sachet"
        )
    }
}

private data class Centroid(val lat: Double, val lon: Double)

/**
 * Sachet publishes the centre as "LONGITUDE,LATITUDE" - the reverse of the
 * order every other source in this app uses. Getting it backwards puts a
 * Gorakhpur alert in the Arctic Ocean and silently defeats distance filtering,
 * so the pair is range-checked before it is trusted.
 */
private fun parseCentroid(raw: String?): Centroid? {
    val parts = raw?.split(",") ?: return null
    if (parts.size != 2) return null
    val lon = parts[0].trim().toDoubleOrNull() ?: return null
    val lat = parts[1].trim().toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return Centroid(lat = lat, lon = lon)
}

/**
 * Returns null rather than a substitute timestamp. A guessed time would reorder
 * the feed and un-expire alerts that have already lapsed, which is worse than
 * having no alert at all.
 *
 * "IST" is ambiguous: SimpleDateFormat resolves zone abbreviations from locale
 * data and can read it as Israel Standard Time, a two and a half hour error in
 * the wrong direction. Setting the parser's own zone is not enough on its own
 * because a zone token in the text overrides it, so a recognised IST token is
 * removed and the remainder parsed against Asia/Kolkata explicitly. Any other
 * zone spelling is left in place for the zzz field to handle.
 */
private fun parseSachetDateMs(raw: String?): Long? {
    val text    = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val hasIst  = IST_TOKEN.containsMatchIn(text)
    val cleaned = if (hasIst) IST_TOKEN.replace(text, " ") else text
    val pattern = if (hasIst) PATTERN_NO_ZONE else PATTERN_WITH_ZONE
    return try {
        // SimpleDateFormat is not thread safe, so it is built per call rather than shared.
        val parser = SimpleDateFormat(pattern, Locale.ENGLISH)
        parser.timeZone = INDIA_ZONE
        parser.parse(cleaned)?.time
    } catch (e: Exception) {
        Log.w(TAG, "Unparseable Sachet timestamp: $text")
        null
    }
}

/**
 * Sachet is inconsistent about quoting - identifier is a bare number, area_covered
 * is a quoted decimal - so fields are read as text either way and converted at the
 * point of use. Blank and literal "null" are treated as absent.
 */
private fun JsonObject.stringField(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.content.trim().takeIf { it.isNotEmpty() && it != "null" }
}
