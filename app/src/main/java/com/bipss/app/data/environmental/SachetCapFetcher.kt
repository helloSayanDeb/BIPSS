package com.bipss.app.data.environmental

import android.util.Log
import com.bipss.app.domain.environmental.ImdAlert
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

private const val TAG = "SachetCapFetcher"

/**
 * NDMA's Sachet national disaster alert portal publishes individual CAP
 * (Common Alerting Protocol) XML documents at a confirmed public endpoint:
 *
 *   https://sachet.ndma.gov.in/cap_public_website/FetchXMLFile?identifier=<id>
 *
 * That endpoint needs an alert identifier you already know — it's a
 * detail-fetch API, not a bulk "list everything active near me right now"
 * feed. Sachet's integration guide (sachet.ndma.gov.in/docs/
 * Integration_Guide_For_Agencies.pdf) mentions an RSS option alongside the
 * XML feed, but the guide is written for alert-issuing agencies and its
 * bulk/list endpoint details weren't resolvable from the publicly fetchable
 * parts of that document during this build.
 *
 * TODO: Confirm the actual bulk listing endpoint (or an identifier feed) by
 * contacting NDMA/reading the full integration guide, then have
 * [DisasterAlertRepository] call [fetchAlertById] for each identifier it
 * returns. Until then this fetcher can enrich a *known* alert (e.g. one a
 * user reports or one cross-referenced from another source) but does NOT
 * replace [ImdRssParser] as the automatic bulk tier — IMD RSS stays primary.
 */
class SachetCapFetcher(private val okHttpClient: OkHttpClient) {

    private val FETCH_URL = "https://sachet.ndma.gov.in/cap_public_website/FetchXMLFile"

    suspend fun fetchAlertById(identifier: String): ImdAlert? {
        return try {
            val request  = Request.Builder().url("$FETCH_URL?identifier=$identifier").build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Sachet fetch failed: HTTP ${response.code}")
                return null
            }
            val xml = response.body?.string() ?: return null
            parseCapXml(xml)
        } catch (e: Exception) {
            Log.e(TAG, "Sachet fetch error: ${e.message}")
            null
        }
    }

    /** Parses a single CAP <alert> document into the same [ImdAlert] shape IMD RSS produces. */
    private fun parseCapXml(xml: String): ImdAlert? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var headline = ""
            var description = ""
            var severity = ""
            var sent = ""
            var eventType = XmlPullParser.START_DOCUMENT

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name.lowercase()) {
                        "headline"    -> headline    = parser.nextText()
                        "description" -> description = parser.nextText()
                        "severity"    -> severity    = parser.nextText()
                        "sent"        -> sent         = parser.nextText()
                    }
                }
                eventType = parser.next()
            }

            if (headline.isBlank()) return null
            ImdAlert(
                title       = headline,
                description = description,
                category    = "Disaster (Sachet/NDMA)",
                pubDateMs   = parseCapDateMs(sent),
                capXmlUrl   = FETCH_URL
            )
        } catch (e: Exception) {
            Log.e(TAG, "Sachet CAP parse error: ${e.message}")
            null
        }
    }

    private fun parseCapDateMs(dateStr: String): Long = try {
        java.time.OffsetDateTime.parse(dateStr).toInstant().toEpochMilli()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }
}
