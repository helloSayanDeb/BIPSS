package com.bipss.app.data.environmental

import android.util.Log
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.domain.environmental.AlertSeverity
import com.bipss.app.domain.environmental.ImdAlert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.OffsetDateTime
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

private const val TAG = "CapAlerts"

/**
 * What an IMD item's CAP document adds to the bare RSS item: its own severity,
 * expiry, official instruction and geography. The RSS item carries none of
 * these, which is why severity used to be guessed from keywords - and every
 * "extremely heavy rainfall" headline came out as the highest level.
 */
@Serializable
data class CapEnrichment(
    val identifier:  String,
    /** CAP `severity` as published: Extreme / Severe / Moderate / Minor / Unknown. */
    val capSeverity: String? = null,
    val expiresMs:   Long? = null,
    val instruction: String? = null,
    /**
     * As published, but not used for matching: on 29 Sep 2026 an IMD document
     * whose description and polygon were Uttarakhand gave "Coastal Andhra
     * Pradesh, Chhattisgarh, Odisha" here. The polygon is what places an alert.
     */
    val areaDesc:    String? = null,
    /** Each polygon as flat [lat0, lon0, lat1, lon1, ...], simplified to at most [CapAlertParser.MAX_VERTICES] points. */
    val polygons:    List<List<Double>> = emptyList(),
    val centroidLat: Double? = null,
    val centroidLon: Double? = null,
    val areaSqKm:    Double? = null,
    /** A test or exercise, or withdrawn by its sender (msgType Cancel): never shown. */
    val withdrawn:   Boolean = false,
    val fetchedAtMs: Long = 0L
) {
    /**
     * The explicit CAP to app severity map, following IMD's colour code
     * (see [AlertSeverity.fromCap]). An alert whose severity is missing or
     * "Unknown" is still shown, as an advisory, but never notified.
     */
    val severity: AlertSeverity get() = AlertSeverity.fromCap(capSeverity)

    fun applyTo(alert: ImdAlert): ImdAlert = alert.copy(
        severity        = severity,
        expiresMs       = if (withdrawn) 0L else expiresMs,
        instruction     = instruction ?: alert.instruction,
        centroidLat     = centroidLat,
        centroidLon     = centroidLon,
        areaCoveredSqKm = areaSqKm,
        polygons        = polygons
    )
}

/** CAP 1.x parsing. DOM rather than XmlPullParser so it runs in plain JVM tests too. */
object CapAlertParser {

    const val MAX_VERTICES = 120

    fun parse(xml: String, fetchedAtMs: Long = System.currentTimeMillis()): CapEnrichment? = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // No DTDs, so no external entities from a document we did not write.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val root = doc.documentElement
        val identifier = root.child("identifier")?.text() ?: return null
        val status = root.child("status")?.text()
        val msgType = root.child("msgType")?.text()

        // One <info> per language; English when there is a choice.
        val infos = root.children("info")
        val info = infos.firstOrNull { it.child("language")?.text()?.startsWith("en", ignoreCase = true) == true }
            ?: infos.firstOrNull()

        val polygons = mutableListOf<List<Double>>()
        var circleCentre: Pair<Double, Double>? = null
        var circleAreaSqKm = 0.0
        val areaDescs = mutableListOf<String>()
        info?.children("area")?.forEach { area ->
            area.child("areaDesc")?.text()?.let { areaDescs += it }
            area.children("polygon").mapNotNull { parsePolygon(it.text()) }.forEach { polygons += it }
            area.children("circle").mapNotNull { parseCircle(it.text()) }.forEach { (lat, lon, radiusKm) ->
                if (circleCentre == null) circleCentre = lat to lon
                circleAreaSqKm += PI * radiusKm * radiusKm
            }
        }

        // The largest polygon places the alert; the areas add up.
        val measured = polygons.map { it to Geo.areaAndCentroid(it) }
        val largest = measured.maxByOrNull { it.second.first }
        val totalArea = measured.sumOf { it.second.first } + circleAreaSqKm

        CapEnrichment(
            identifier  = identifier,
            capSeverity = info?.child("severity")?.text(),
            expiresMs   = info?.child("expires")?.text()?.let(::parseTimeMs),
            instruction = info?.child("instruction")?.text()?.let(::tidy)?.ifBlank { null },
            areaDesc    = areaDescs.joinToString("; ").ifBlank { null },
            polygons    = polygons.map { Geo.simplify(it, MAX_VERTICES) },
            centroidLat = largest?.second?.second?.first ?: circleCentre?.first,
            centroidLon = largest?.second?.second?.second ?: circleCentre?.second,
            areaSqKm    = totalArea.takeIf { it > 0.0 },
            withdrawn   = !status.equals("Actual", ignoreCase = true) || msgType.equals("Cancel", ignoreCase = true),
            fetchedAtMs = fetchedAtMs
        )
    } catch (e: Exception) {
        // Callers log; this stays free of android.util so it runs in JVM tests.
        null
    }

    /** "lat,lon lat,lon ..." to a flat list; null when it is not a usable ring. */
    internal fun parsePolygon(text: String): List<Double>? {
        val flat = text.trim().split(Regex("\\s+")).mapNotNull { pair ->
            val parts = pair.split(',')
            if (parts.size != 2) return@mapNotNull null
            val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lon = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            listOf(lat, lon)
        }.flatten()
        return flat.takeIf { it.size >= 6 }
    }

    /** "lat,lon radiusKm" to (lat, lon, radiusKm). */
    internal fun parseCircle(text: String): Triple<Double, Double, Double>? {
        val parts = text.trim().split(Regex("\\s+"))
        if (parts.size != 2) return null
        val ll = parts[0].split(',')
        val lat = ll.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lon = ll.getOrNull(1)?.toDoubleOrNull() ?: return null
        val r = parts[1].toDoubleOrNull() ?: return null
        return Triple(lat, lon, r)
    }

    internal fun parseTimeMs(s: String): Long? =
        runCatching { OffsetDateTime.parse(s.trim()).toInstant().toEpochMilli() }.getOrNull()

    /** CAP instructions arrive with tabs and ragged line breaks. */
    private fun tidy(s: String): String =
        s.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    private fun Element.children(localName: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && (n.localName ?: n.nodeName.substringAfter(':')) == localName) out += n
        }
        return out
    }

    private fun Element.child(localName: String): Element? = children(localName).firstOrNull()
    private fun Element.text(): String = textContent.orEmpty().trim()
}

/** Small spherical-earth helpers for alert areas (km). Accurate enough at district scale. */
object Geo {
    private const val KM_PER_DEG_LAT = 110.574
    private const val KM_PER_DEG_LON_EQUATOR = 111.320

    /** (area km², (lat, lon) centroid) of a flat [lat, lon, ...] ring, by the shoelace formula on a local projection. */
    fun areaAndCentroid(flat: List<Double>): Pair<Double, Pair<Double, Double>> {
        val n = flat.size / 2
        val lat0 = (0 until n).sumOf { flat[2 * it] } / n
        val kx = KM_PER_DEG_LON_EQUATOR * cos(Math.toRadians(lat0))
        var a2 = 0.0; var cx = 0.0; var cy = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val xi = flat[2 * i + 1] * kx; val yi = flat[2 * i] * KM_PER_DEG_LAT
            val xj = flat[2 * j + 1] * kx; val yj = flat[2 * j] * KM_PER_DEG_LAT
            val cross = xi * yj - xj * yi
            a2 += cross
            cx += (xi + xj) * cross
            cy += (yi + yj) * cross
        }
        if (abs(a2) < 1e-9) {
            // Degenerate ring: fall back to the vertex mean.
            val lon0 = (0 until n).sumOf { flat[2 * it + 1] } / n
            return 0.0 to (lat0 to lon0)
        }
        val area = abs(a2) / 2.0
        return area to ((cy / (3.0 * a2)) / KM_PER_DEG_LAT to (cx / (3.0 * a2)) / kx)
    }

    /** Ray casting; the ring may be open or closed. */
    fun contains(flat: List<Double>, lat: Double, lon: Double): Boolean {
        val n = flat.size / 2
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val yi = flat[2 * i]; val xi = flat[2 * i + 1]
            val yj = flat[2 * j]; val xj = flat[2 * j + 1]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Distance to the nearest vertex - the ring is simplified, so this is approximate by design. */
    fun nearestVertexKm(flat: List<Double>, lat: Double, lon: Double): Double =
        (0 until flat.size / 2).minOf { haversineKm(lat, lon, flat[2 * it], flat[2 * it + 1]) }

    /** Every k-th vertex so at most [max] remain. */
    fun simplify(flat: List<Double>, max: Int): List<Double> {
        val n = flat.size / 2
        if (n <= max) return flat
        val step = (n + max - 1) / max
        return (0 until n step step).flatMap { listOf(flat[2 * it], flat[2 * it + 1]) }
    }

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
    }
}

/**
 * Fetches and caches the CAP document behind each IMD RSS item.
 *
 * Enrichment is cached by the alert's identifier (a CAP document never
 * changes once published), and at most [MAX_NEW_FETCHES_PER_SYNC] documents
 * are fetched per sync, so a long feed cannot turn one sync into dozens of
 * requests. An item not yet enriched is kept, as an advisory, and is
 * enriched on a later sync.
 */
class ImdCapEnricher(
    private val okHttpClient: OkHttpClient,
    private val prefs:        UserPreferencesRepository
) {
    suspend fun enrich(items: List<ImdAlert>, nowMs: Long = System.currentTimeMillis()): List<ImdAlert> {
        if (items.isEmpty()) return items
        val cache = prefs.capEnrichmentCache().toMutableMap()
        var fetched = 0
        val out = items.map { item ->
            cache[item.identifier]?.let { return@map it.applyTo(item) }
            val url = item.capXmlUrl ?: return@map item
            if (fetched >= MAX_NEW_FETCHES_PER_SYNC) return@map item
            fetched++
            val enrichment = fetchCap(url, nowMs)
            if (enrichment == null) item else {
                cache[item.identifier] = enrichment
                enrichment.applyTo(item)
            }
        }
        if (fetched > 0) prefs.saveCapEnrichmentCache(prune(cache, nowMs))
        return out
    }

    private suspend fun fetchCap(url: String, nowMs: Long): CapEnrichment? = withContext(Dispatchers.IO) {
        try {
            okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "CAP fetch failed: HTTP ${response.code}")
                    return@withContext null
                }
                response.body?.string()?.let { CapAlertParser.parse(it, nowMs) }
                    .also { if (it == null) Log.w(TAG, "CAP document unreadable: $url") }
            }
        } catch (e: Exception) {
            Log.w(TAG, "CAP fetch error: ${e.message}")
            null
        }
    }

    companion object {
        const val MAX_NEW_FETCHES_PER_SYNC = 10
        // The cache lives in the preferences file, rewritten on every edit, so it stays small.
        private const val MAX_CACHED = 60
        private const val KEEP_AFTER_EXPIRY_MS = 2L * 24 * 60 * 60 * 1000

        /** Drops entries long expired, then the oldest beyond [MAX_CACHED]. */
        internal fun prune(cache: Map<String, CapEnrichment>, nowMs: Long): Map<String, CapEnrichment> =
            cache.filterValues { e -> e.expiresMs == null || e.expiresMs + KEEP_AFTER_EXPIRY_MS > nowMs }
                .entries.sortedByDescending { it.value.fetchedAtMs }
                .take(MAX_CACHED)
                .associate { it.key to it.value }
    }
}
