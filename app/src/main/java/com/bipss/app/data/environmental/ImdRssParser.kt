package com.bipss.app.data.environmental

import android.util.Log
import com.bipss.app.domain.environmental.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

private const val TAG       = "ImdRssParser"
private const val IMD_CAP_RSS_URL = "https://cap-sources.s3.amazonaws.com/in-imd-en/rss.xml"

class ImdRssParser(private val okHttpClient: OkHttpClient) {

    /**
     * Fetches and parses the IMD CAP Alerts RSS feed.
     *
     * TODO: If the full IMD REST API becomes available (requires government API key),
     *       swap fetchImdAlerts() to call that endpoint instead. The CAP RSS feed
     *       is the no-auth fallback confirmed live at [IMD_CAP_RSS_URL].
     */
    suspend fun fetchImdAlerts(): List<ImdAlert> = withContext(Dispatchers.IO) {
        try {
            val request  = Request.Builder().url(IMD_CAP_RSS_URL).build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "RSS fetch failed: HTTP ${response.code}")
                return@withContext emptyList()
            }
            val xml = response.body?.string() ?: return@withContext emptyList()
            parseRss(xml)
        } catch (e: Exception) {
            Log.e(TAG, "IMD fetch error: ${e.message}")
            emptyList()
        }
    }

    private fun parseRss(xml: String): List<ImdAlert> {
        val alerts = mutableListOf<ImdAlert>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var currentTitle = ""
            var currentDesc  = ""
            var currentLink  = ""
            var currentDate  = ""
            var inItem = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                        "item" -> inItem = true
                        "title" -> if (inItem) currentTitle = parser.nextText()
                        "description" -> if (inItem) currentDesc  = parser.nextText()
                        "link" -> if (inItem) currentLink  = parser.nextText()
                        "pubdate" -> if (inItem) currentDate  = parser.nextText()
                    }
                    XmlPullParser.END_TAG -> if (parser.name.lowercase() == "item" && inItem) {
                        val severity = detectSeverity(currentTitle)
                        alerts.add(ImdAlert(
                            title       = currentTitle,
                            description = currentDesc,
                            category    = "Weather",
                            pubDateMs   = parseDateMs(currentDate),
                            capXmlUrl   = currentLink
                        ))
                        currentTitle = ""; currentDesc = ""; currentLink = ""; currentDate = ""
                        inItem = false
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "RSS parse error: ${e.message}")
        }
        return alerts.sortedByDescending { it.pubDateMs }
    }

    private fun detectSeverity(title: String): AlertSeverity {
        val t = title.lowercase()
        return when {
            "extreme" in t || "red alert" in t  -> AlertSeverity.EXTREME
            "warning" in t || "orange alert" in t -> AlertSeverity.WARNING
            "watch" in t || "yellow alert" in t  -> AlertSeverity.WATCH
            "advisory" in t || "green alert" in t -> AlertSeverity.ADVISORY
            else                                  -> AlertSeverity.ADVISORY
        }
    }

    private fun parseDateMs(dateStr: String): Long = try {
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.ENGLISH)
            .parse(dateStr)?.time ?: System.currentTimeMillis()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }
}
