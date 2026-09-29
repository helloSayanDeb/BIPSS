package com.bipss.app.data.environmental

import android.util.Log
import com.bipss.app.data.net.fetchText
import com.bipss.app.domain.environmental.AdvisoryDigest
import com.bipss.app.domain.environmental.AdvisoryItem
import com.bipss.app.domain.environmental.AdvisorySource
import com.bipss.app.domain.environmental.HealthAdvisory
import com.bipss.app.domain.environmental.htmlToPlainText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.xml.parsers.DocumentBuilderFactory

private const val TAG = "HealthAdvisoryFetcher"

/**
 * WHO's OData endpoint for Disease Outbreak News. The RSS feed this project
 * first targeted (feeds/entity/csr/don/en/rss.xml) answers 404 since 8 Sep 2026.
 */
private const val WHO_DON_BASE = "https://www.who.int/api/news/diseaseoutbreaknews"
/** UrlName ("2026-DON618") is a slug; the readable article hangs off this. */
private const val WHO_ARTICLE_BASE = "https://www.who.int/emergencies/disease-outbreak-news/item/"
/** CDC Travelers' Health notices, levels 1 to 4 (checked 30 Sep 2026). */
private const val CDC_NOTICES_RSS = "https://wwwnc.cdc.gov/travel/rss/notices.xml"
/** ECDC "Epidemiological updates" - outbreak write-ups, not its general news (checked 30 Sep 2026). */
private const val ECDC_UPDATES_RSS = "https://www.ecdc.europa.eu/en/taxonomy/term/1310/feed"

/**
 * Disease-outbreak reports from WHO, CDC and ECDC - all public, none needs a
 * key - summarised and grouped on the phone into one card per outbreak
 * ([AdvisoryDigest]).
 *
 * India's own surveillance (NCDC/IDSP weekly outbreak reports) is published
 * only as PDF files, several weeks behind, so there is no Indian feed to read;
 * WHO covers India when an outbreak there is notable (Nipah in Kerala, June 2026).
 *
 * Scope is only ever the country or worldwide - never a state or district.
 * None of these sources resolves below country level, and claiming otherwise
 * would tell someone their district is affected on the strength of a national
 * bulletin.
 */
class HealthAdvisoryFetcher(private val okHttpClient: OkHttpClient) {

    /** The digest, and which agencies answered this time (empty: none could be reached). */
    data class Result(val advisories: List<HealthAdvisory>, val sourcesAnswered: List<String>)

    suspend fun fetch(nowMs: Long = System.currentTimeMillis()): Result = coroutineScope {
        val who  = async { read(AdvisorySource.WHO, AdvisoryFeeds.whoUrl(nowMs), "application/json") { AdvisoryFeeds.parseWho(it) } }
        val cdc  = async { read(AdvisorySource.CDC, CDC_NOTICES_RSS, "application/rss+xml") { AdvisoryFeeds.parseRss(it, AdvisorySource.CDC) } }
        val ecdc = async { read(AdvisorySource.ECDC, ECDC_UPDATES_RSS, "application/rss+xml") { AdvisoryFeeds.parseRss(it, AdvisorySource.ECDC) } }
        val answers = listOf(who.await(), cdc.await(), ecdc.await())
        val items = answers.flatMap { it?.second.orEmpty() }
        Result(AdvisoryDigest.build(items, nowMs), answers.mapNotNull { it?.first?.label })
    }

    /** The source's items, or null when it could not be read (distinct from "read, nothing recent"). */
    private suspend fun read(
        source: AdvisorySource,
        url: String,
        accept: String,
        parse: (String) -> List<AdvisoryItem>?
    ): Pair<AdvisorySource, List<AdvisoryItem>>? = withContext(Dispatchers.IO) {
        try {
            val resp = okHttpClient.fetchText(Request.Builder().url(url).header("Accept", accept).build())
            if (!resp.isSuccessful) {
                Log.w(TAG, "${source.label}: HTTP ${resp.code}")
                return@withContext null
            }
            val items = parse(resp.body ?: return@withContext null)
                ?: return@withContext null.also { Log.w(TAG, "${source.label}: unreadable answer") }
            Log.d(TAG, "${source.label}: ${items.size} report(s)")
            source to items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "${source.label}: ${e.message}")
            null
        }
    }
}

/** The feeds' formats, apart from the network so they are tested on real answers. */
object AdvisoryFeeds {

    private val lenientJson = Json { ignoreUnknownKeys = true }
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /**
     * Only the window the app shows, with the risk assessment: about 5 KB,
     * where the previous 40-report query was 70 KB. The dollar of each OData
     * option and the spaces are pre-encoded (%24, %20).
     */
    fun whoUrl(nowMs: Long): String {
        val since = Instant.ofEpochMilli(nowMs - AdvisoryDigest.MAX_AGE_DAYS * DAY_MS).truncatedTo(ChronoUnit.DAYS)
        return "$WHO_DON_BASE?%24select=Title,PublicationDateAndTime,UrlName,Summary,Assessment" +
            "&%24filter=PublicationDateAndTime%20ge%20$since" +
            "&%24orderby=PublicationDateAndTime%20desc&%24top=25"
    }

    /** Null when the answer is not the expected JSON (a failed read, not "nothing new"). */
    fun parseWho(body: String): List<AdvisoryItem>? {
        val items = runCatching { (lenientJson.parseToJsonElement(body) as? JsonObject)?.get("value") as? JsonArray }
            .getOrNull() ?: return null
        return items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val title = o.text("Title").ifBlank { return@mapNotNull null }
            val published = parseIso(o.text("PublicationDateAndTime")) ?: return@mapNotNull null
            val slug = o.text("UrlName")
            AdvisoryItem(
                source      = AdvisorySource.WHO,
                id          = slug.ifBlank { title },
                title       = title,
                text        = htmlToPlainText(o.text("Summary")),
                publishedMs = published,
                url         = slug.takeIf { it.isNotBlank() }?.let { WHO_ARTICLE_BASE + it },
                assessment  = htmlToPlainText(o.text("Assessment"))
            )
        }
    }

    /** RSS 2.0 items. Null when the document is not RSS at all. */
    fun parseRss(xml: String, source: AdvisorySource): List<AdvisoryItem>? = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            // No DTDs, so no external entities from a document we did not write.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.trimStart('﻿', ' ', '\n', '\r').toByteArray(Charsets.UTF_8)))
        if (doc.documentElement.tagName != "rss") null
        else {
            val nodes = doc.getElementsByTagName("item")
            (0 until nodes.length).mapNotNull { i ->
                val item = nodes.item(i) as? Element ?: return@mapNotNull null
                val title = item.child("title").trim().ifEmpty { return@mapNotNull null }
                val published = parseRfc1123(item.child("pubDate")) ?: return@mapNotNull null
                val link = item.child("link").trim()
                AdvisoryItem(
                    source      = source,
                    id          = item.child("guid").trim().ifEmpty { link.ifEmpty { title } },
                    title       = title,
                    // The description is escaped HTML inside the XML.
                    text        = htmlToPlainText(item.child("description")),
                    publishedMs = published,
                    url         = link.ifEmpty { null },
                    travelLevel = if (source == AdvisorySource.CDC)
                        Regex("^Level (\\d)").find(title.trim())?.groupValues?.get(1)?.toIntOrNull() else null
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun Element.child(tag: String): String =
        getElementsByTagName(tag).takeIf { it.length > 0 }?.item(0)?.textContent.orEmpty()

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    /** ISO-8601 UTC; a stamp without an offset is read as UTC, not device-local. */
    internal fun parseIso(raw: String): Long? {
        if (raw.isBlank()) return null
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    /** "Wed, 23 Sep 2026 04:00:00 GMT" and "Mon, 28 Sep 2026 17:36:21 +0200". */
    internal fun parseRfc1123(raw: String): Long? =
        runCatching { ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
}
