package com.bipss.app.data.environmental

import android.util.Log
import com.bipss.app.domain.environmental.AdvisoryScope
import com.bipss.app.domain.environmental.HealthAdvisory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

private const val TAG         = "HealthAdvisoryFetcher"
private const val SOURCE_NAME = "WHO Disease Outbreak News"

/**
 * WHO's OData endpoint for Disease Outbreak News.
 *
 * The RSS feed this project originally targeted (feeds/entity/csr/don/en/rss.xml)
 * is dead - it answers HTTP 404 as of 8 Sep 2026 - so nothing here should be
 * pointed back at it. This OData service is the verified replacement.
 *
 * The leading dollar of each OData system option and the space in the orderby
 * clause are written pre-encoded (%24, %20). OkHttp's HttpUrl passes existing
 * percent escapes through untouched but would not add them for us, and a bare
 * dollar in a Kotlin string would read as a template prefix besides.
 */
private const val DON_URL = "https://www.who.int/api/news/diseaseoutbreaknews" +
                            "?%24select=Title,PublicationDateAndTime,UrlName,Summary" +
                            "&%24orderby=PublicationDateAndTime%20desc" +
                            "&%24top=40"

/** UrlName ("2026-DON616") is a slug, not a link; the readable article hangs off this. */
private const val ARTICLE_BASE = "https://www.who.int/emergencies/disease-outbreak-news/item/"

private const val MAX_AGE_MS        = 180L * 24 * 60 * 60 * 1000L
private const val SUMMARY_MAX_CHARS = 240
private const val ELLIPSIS          = "..."

private val lenientJson = Json { ignoreUnknownKeys = true }

private val HTML_TAG   = Regex("<[^>]*>")
private val WHITESPACE = Regex("\\s+")

/**
 * Matches India, Indian and the plurals as whole words. The word boundary is
 * the point: a plain substring test would promote a US outbreak in Indiana to
 * national scope for an Indian user.
 */
private val INDIA_MENTION = Regex("\\bindian?s?\\b", RegexOption.IGNORE_CASE)

/**
 * Titles that declare the report multi-country. WHO uses these consistently,
 * and they override an India mention buried in the body text.
 */
private val GLOBAL_TITLE_MARKER =
    Regex("global|multi-country|multi-locations|multiple countries", RegexOption.IGNORE_CASE)

/** Reads a field as trimmed text, treating a missing, null or non-scalar value as blank. */
private fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

/**
 * Disease-outbreak advisories from WHO.
 *
 * Scope is only ever [AdvisoryScope.GLOBAL] or [AdvisoryScope.NATIONAL], never
 * REGIONAL or LOCAL. This source has no district resolution, so claiming
 * otherwise would be a false claim of local surveillance - the app would be
 * telling someone their district is affected on the strength of a country-level
 * bulletin. That restriction is deliberate and must survive any refactor.
 */
class HealthAdvisoryFetcher(private val okHttpClient: OkHttpClient) {

    suspend fun fetchAdvisories(): List<HealthAdvisory> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(DON_URL)
                .header("Accept", "application/json")
                .build()
            val resp = okHttpClient.newCall(request).execute()
            resp.use {
                if (!it.isSuccessful) {
                    Log.w(TAG, "WHO DON fetch failed: HTTP ${it.code}")
                    return@withContext emptyList()
                }
                val body = it.body?.string() ?: return@withContext emptyList()
                parseResponse(body, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e(TAG, "WHO DON fetch error: ${e.message}")
            emptyList()
        }
    }

    private fun parseResponse(body: String, nowMs: Long): List<HealthAdvisory> = try {
        val root  = lenientJson.parseToJsonElement(body) as? JsonObject
        val items = root?.get("value") as? JsonArray
        if (items == null) {
            Log.w(TAG, "WHO DON response carried no value array - the API shape may have moved")
            emptyList()
        } else {
            toAdvisories(items, nowMs)
        }
    } catch (e: Exception) {
        Log.e(TAG, "WHO DON parse error: ${e.message}")
        emptyList()
    }

    private fun toAdvisories(items: JsonArray, nowMs: Long): List<HealthAdvisory> {
        val advisories      = mutableListOf<HealthAdvisory>()
        var droppedStale    = 0
        var droppedUnusable = 0

        for (element in items) {
            val item = element as? JsonObject
            if (item == null) {
                droppedUnusable++
                continue
            }

            val title       = item.text("Title")
            val publishedMs = parseIsoUtcMs(item.text("PublicationDateAndTime"))
            if (title.isBlank() || publishedMs == null) {
                droppedUnusable++
                continue
            }
            if (nowMs - publishedMs > MAX_AGE_MS) {
                droppedStale++
                continue
            }

            val urlName    = item.text("UrlName")
            val rawSummary = item.text("Summary")
            advisories += HealthAdvisory(
                id          = urlName.ifBlank { title },
                title       = title,
                summary     = condense(rawSummary),
                source      = SOURCE_NAME,
                scope       = scopeFor(title, rawSummary),
                publishedMs = publishedMs,
                url         = if (urlName.isBlank()) null else ARTICLE_BASE + urlName
            )
        }

        if (droppedStale > 0) {
            Log.i(TAG, "Dropped $droppedStale WHO advisories older than 365 days")
        }
        if (droppedUnusable > 0) {
            Log.w(TAG, "Dropped $droppedUnusable WHO advisories with no title or no readable date")
        }

        // India first, newest first within each block.
        return advisories.sortedWith(
            compareByDescending<HealthAdvisory> { it.scope == AdvisoryScope.NATIONAL }
                .thenByDescending { it.publishedMs }
        )
    }

    /**
     * Country-level classification only. See the class note: this feed cannot
     * support REGIONAL or LOCAL and must never be made to claim them.
     *
     * The title wins over the body. WHO's multi-country situation reports
     * routinely list India among many affected states in the summary, and
     * matching on the body alone labelled "Mpox - Global situation" as an India
     * advisory - overstating local relevance on a card whose whole purpose is
     * to be honest about scope. An explicit global marker in the title is
     * therefore decisive.
     */
    private fun scopeFor(title: String, summary: String): AdvisoryScope = when {
        GLOBAL_TITLE_MARKER.containsMatchIn(title)   -> AdvisoryScope.GLOBAL
        INDIA_MENTION.containsMatchIn(title)         -> AdvisoryScope.NATIONAL
        INDIA_MENTION.containsMatchIn(summary)       -> AdvisoryScope.NATIONAL
        else                                         -> AdvisoryScope.GLOBAL
    }

    /**
     * WHO summaries arrive as HTML fragments. Strip the markup, restore the few
     * entities that survive it, then cut on a word boundary so a card never ends
     * mid-word. The marker comes out of the budget, so the result is never
     * longer than [SUMMARY_MAX_CHARS].
     */
    private fun condense(raw: String): String {
        val text = raw.replace(HTML_TAG, " ")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")   // last, or an escaped entity would decode twice
            .replace(WHITESPACE, " ")
            .trim()
        if (text.length <= SUMMARY_MAX_CHARS) return text

        val cut        = text.take(SUMMARY_MAX_CHARS - ELLIPSIS.length)
        val lastSpace  = cut.lastIndexOf(' ')
        val onBoundary = if (lastSpace > 0) cut.take(lastSpace) else cut
        return onBoundary.trimEnd(' ', ',', ';', ':', '.') + ELLIPSIS
    }

    /**
     * The stamp is ISO-8601 UTC ("2026-08-28T15:28:00Z"). A record that arrives
     * without the offset is read as UTC rather than device-local, so the age
     * cutoff does not drift with whatever timezone the phone is in.
     *
     * An unreadable stamp drops the item. A publication date we invented would
     * corrupt both the cutoff and the ordering, and the field is not nullable,
     * so there is nowhere honest to put "unknown".
     */
    private fun parseIsoUtcMs(raw: String): Long? {
        if (raw.isBlank()) return null
        return try {
            Instant.parse(raw).toEpochMilli()
        } catch (e: Exception) {
            try {
                LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }
    }
}

/**
 * The India subset of an already-fetched list.
 *
 * Scope is decided once, at parse time. A caller that re-ran its own string
 * match here could reach a different answer to the one the card badge shows.
 */
fun indiaRelevant(all: List<HealthAdvisory>): List<HealthAdvisory> =
    all.filter { it.scope == AdvisoryScope.NATIONAL }
