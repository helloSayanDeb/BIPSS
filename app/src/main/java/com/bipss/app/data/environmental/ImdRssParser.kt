package com.bipss.app.data.environmental

import android.util.Log
import com.bipss.app.domain.environmental.AlertSeverity
import com.bipss.app.domain.environmental.ImdAlert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

private const val TAG = "ImdRssParser"
private const val IMD_CAP_RSS_URL = "https://cap-sources.s3.amazonaws.com/in-imd-en/rss.xml"

/**
 * Primary alert source: IMD's CAP RSS feed (via the WMO Alert Hub mirror).
 *
 * The RSS items themselves are thin - titles repeat across items and there is
 * no severity, expiry or area - but each links to its CAP document, which has
 * all three. [ImdCapEnricher] fetches those; this parser only reads the list.
 * Severity is never guessed from the text any more: keywords made every
 * "extremely heavy rainfall" headline the highest level. Until its CAP
 * document is read, an item is an advisory (shown, never notified).
 *
 * The region is still pulled out of the description, for items whose CAP
 * document could not be read yet.
 */
class ImdRssParser(private val okHttpClient: OkHttpClient) {

    /**
     * Current IMD items, or **null when the feed could not be read**. Empty
     * means IMD was reached and lists nothing; null means unknown, and must not
     * be presented as an all-clear.
     */
    suspend fun fetchImdAlerts(): List<ImdAlert>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(IMD_CAP_RSS_URL).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "RSS fetch failed: HTTP ${response.code}")
                    return@withContext null
                }
                val xml = response.body?.string() ?: return@withContext null
                parseRss(xml)
            }
        } catch (e: Exception) {
            Log.e(TAG, "IMD fetch error: ${e.message}")
            null
        }
    }

    /** Null when the document itself cannot be parsed (a failed read, not an empty feed). */
    internal fun parseRss(xml: String): List<ImdAlert>? {
        val alerts = mutableListOf<ImdAlert>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var title = ""; var desc = ""; var link = ""
            var date = ""; var guid = ""; var category = ""
            var inItem = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                        "item"        -> inItem = true
                        "title"       -> if (inItem) title    = parser.nextText()
                        "description" -> if (inItem) desc     = parser.nextText()
                        "link"        -> if (inItem) link     = parser.nextText()
                        "pubdate"     -> if (inItem) date     = parser.nextText()
                        "guid"        -> if (inItem) guid     = parser.nextText()
                        "category"    -> if (inItem) category = parser.nextText()
                    }
                    XmlPullParser.END_TAG -> if (parser.name.lowercase() == "item" && inItem) {
                        val areas = extractAreas(desc)
                        alerts += ImdAlert(
                            // Titles repeat across every item, so the guid is the
                            // only usable dedup key; where it is absent we fall
                            // back to title plus region, which at least separates
                            // the districts from one another.
                            identifier      = guid.ifBlank { (title + "|" + areas.joinToString()).hashCode().toString() },
                            title           = title.trim(),
                            description     = desc.trim(),
                            category        = category.ifBlank { "Weather" },
                            severity        = AlertSeverity.ADVISORY,
                            areas           = areas,
                            pubDateMs       = parseDateMs(date) ?: System.currentTimeMillis(),
                            // Expiry is in the CAP document (ImdCapEnricher); until
                            // then null means "unknown", which is kept, not expired.
                            expiresMs       = null,
                            capXmlUrl       = link.ifBlank { null },
                            instruction     = null,
                            centroidLat     = null,
                            centroidLon     = null,
                            areaCoveredSqKm = null,
                            language        = "en",
                            source          = "IMD"
                        )
                        title = ""; desc = ""; link = ""; date = ""; guid = ""; category = ""
                        inItem = false
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "RSS parse error: ${e.message}")
            return null
        }
        return alerts.sortedByDescending { it.pubDateMs }
    }

    /**
     * Pulls Indian state and territory names out of the free-text description.
     *
     * The feed puts the region only in prose ("...very likely over East Madhya
     * Pradesh"), so without this every item looks nationally applicable and the
     * geo-filter has nothing to match on.
     */
    internal fun extractAreas(description: String): List<String> {
        if (description.isBlank()) return emptyList()
        return INDIAN_REGIONS.filter { region ->
            description.contains(region, ignoreCase = true)
        }.distinct()
    }

    /** Returns null on failure - a wrong timestamp silently reorders the feed. */
    internal fun parseDateMs(dateStr: String): Long? = try {
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.ENGLISH)
            .parse(dateStr.trim())?.time
    } catch (e: Exception) {
        null
    }

    private companion object {
        val INDIAN_REGIONS = listOf(
            "Andaman", "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar",
            "Chandigarh", "Chhattisgarh", "Dadra", "Daman", "Delhi", "Goa", "Gujarat",
            "Haryana", "Himachal Pradesh", "Jammu", "Kashmir", "Jharkhand", "Karnataka",
            "Kerala", "Ladakh", "Lakshadweep", "Madhya Pradesh", "Maharashtra", "Manipur",
            "Meghalaya", "Mizoram", "Nagaland", "Odisha", "Puducherry", "Punjab",
            "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana", "Tripura", "Uttar Pradesh",
            "Uttarakhand", "West Bengal", "Gangetic West Bengal", "Sub-Himalayan West Bengal",
            "Konkan", "Vidarbha", "Marathwada", "Saurashtra", "Kutch", "Rayalaseema",
            "Coastal Andhra Pradesh", "Kolkata", "Mumbai", "Chennai", "Bengaluru", "Hyderabad"
        )
    }
}
