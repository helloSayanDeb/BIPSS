package com.bipss.app.data.environmental

import com.bipss.app.domain.environmental.AlertSeverity
import com.bipss.app.domain.environmental.ImdAlert
import com.bipss.app.worker.WorkScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class CapAlertsTest {

    /**
     * The IMD document behind RSS item 2026-09-27-07-55-55 (fetched 29 Sep
     * 2026), signature removed. Its areaDesc names three states the polygon
     * does not cover - the polygon and description are Uttarakhand.
     */
    private fun imdCap(
        severity: String = "Severe",
        status: String = "Actual",
        msgType: String = "Alert",
        area: String = """<cap:polygon>30.3729,77.6514 29.4587,78.7061 28.8832,79.7168 29.8406,80.5518 30.1071,81.2988 30.9399,79.8047 31.5411,78.9258 31.0529,77.8711 30.3729,77.6514</cap:polygon>"""
    ) = """<?xml version="1.0" encoding="UTF-8"?>
<cap:alert xmlns:cap="urn:oasis:names:tc:emergency:cap:1.2">
  <cap:identifier>urn:oid:2.49.0.1.356.0.2026.9.27.7.55.55</cap:identifier>
  <cap:sender>rainfallnwfc@gmail.com</cap:sender>
  <cap:sent>2026-09-27T13:25:55+05:30</cap:sent>
  <cap:status>$status</cap:status>
  <cap:msgType>$msgType</cap:msgType>
  <cap:scope>Public</cap:scope>
  <cap:info>
    <cap:language>en</cap:language>
    <cap:category>Met</cap:category>
    <cap:event>Extremely heavy</cap:event>
    <cap:urgency>Expected</cap:urgency>
    <cap:severity>$severity</cap:severity>
    <cap:certainty>Likely</cap:certainty>
    <cap:onset>2026-09-27T07:00:00+05:30</cap:onset>
    <cap:expires>2026-09-28T07:00:00+05:30</cap:expires>
    <cap:headline>Heavy to very heavy with extremely heavy rainfall</cap:headline>
    <cap:description>Isolated extremely rainfall likely over Uttarakhand.</cap:description>
    <cap:instruction>Action suggested

	Avoid roadway underpasses, drainage ditches, low lying areas.
	      Drive carefully.</cap:instruction>
    <cap:area>
      <cap:areaDesc>Coastal Andhra Pradesh, Chhattisgarh, Odisha</cap:areaDesc>
      $area
    </cap:area>
  </cap:info>
</cap:alert>"""

    @Test
    fun `reads severity, expiry, instruction and area from a real IMD document`() {
        val e = CapAlertParser.parse(imdCap(), fetchedAtMs = 1L)
        assertNotNull(e)
        e!!
        assertEquals("urn:oid:2.49.0.1.356.0.2026.9.27.7.55.55", e.identifier)
        assertEquals(AlertSeverity.WARNING, e.severity)       // Severe = IMD orange, "Alert"
        assertEquals(OffsetDateTime.parse("2026-09-28T07:00:00+05:30").toInstant().toEpochMilli(), e.expiresMs)
        assertEquals("Action suggested\nAvoid roadway underpasses, drainage ditches, low lying areas.\nDrive carefully.", e.instruction)
        assertFalse(e.withdrawn)
        assertEquals(1, e.polygons.size)
        // Centroid inside Uttarakhand, whatever areaDesc says.
        assertTrue(e.centroidLat!! in 29.5..31.0)
        assertTrue(e.centroidLon!! in 78.5..80.5)
        // Roughly the size of the state (53,483 km²): the polygon is coarse, so only the order of magnitude is checked.
        assertTrue("area ${e.areaSqKm}", e.areaSqKm!! in 25_000.0..80_000.0)
    }

    @Test
    fun `the polygon, not areaDesc, decides who it is for`() {
        val e = CapAlertParser.parse(imdCap())!!
        val ring = e.polygons.single()
        assertTrue(Geo.contains(ring, 30.3165, 78.0322))      // Dehradun
        assertFalse(Geo.contains(ring, 20.2961, 85.8245))     // Bhubaneswar, Odisha (named in areaDesc)
        assertFalse(Geo.contains(ring, 22.5726, 88.3639))     // Kolkata
    }

    @Test
    fun `CAP severity maps to IMD's colour levels, unknown still shown as an advisory`() {
        assertEquals(AlertSeverity.EXTREME, CapAlertParser.parse(imdCap(severity = "Extreme"))!!.severity)
        assertEquals(AlertSeverity.WARNING, CapAlertParser.parse(imdCap(severity = "Severe"))!!.severity)
        assertEquals(AlertSeverity.WATCH, CapAlertParser.parse(imdCap(severity = "Moderate"))!!.severity)
        assertEquals(AlertSeverity.ADVISORY, CapAlertParser.parse(imdCap(severity = "Minor"))!!.severity)
        assertEquals(AlertSeverity.ADVISORY, CapAlertParser.parse(imdCap(severity = "Unknown"))!!.severity)
        // Only orange and red reach the notification gate.
        assertTrue(AlertSeverity.fromCap("Severe").rank >= AlertSeverity.WARNING.rank)
        assertTrue(AlertSeverity.fromCap("Moderate").rank < AlertSeverity.WARNING.rank)
    }

    @Test
    fun `tests, exercises and cancellations are withdrawn, and applying one expires the alert`() {
        assertTrue(CapAlertParser.parse(imdCap(status = "Test"))!!.withdrawn)
        assertTrue(CapAlertParser.parse(imdCap(status = "Exercise"))!!.withdrawn)
        val cancel = CapAlertParser.parse(imdCap(msgType = "Cancel"))!!
        assertTrue(cancel.withdrawn)
        assertTrue(cancel.applyTo(rssItem()).isExpiredAt(System.currentTimeMillis()))
    }

    @Test
    fun `a circle area gives a centre and an area`() {
        val e = CapAlertParser.parse(imdCap(area = "<cap:circle>22.57,88.36 10</cap:circle>"))!!
        assertEquals(22.57, e.centroidLat!!, 1e-9)
        assertEquals(88.36, e.centroidLon!!, 1e-9)
        assertEquals(Math.PI * 100.0, e.areaSqKm!!, 1e-6)
        assertTrue(e.polygons.isEmpty())
    }

    @Test
    fun `not a CAP document is unreadable, not an empty alert`() {
        assertNull(CapAlertParser.parse("<html><body>503</body></html>"))
        assertNull(CapAlertParser.parse("not xml at all"))
    }

    @Test
    fun `an enriched RSS item takes the document's severity, expiry and geography`() {
        val item = rssItem()
        assertEquals(AlertSeverity.ADVISORY, item.severity)   // until the CAP document is read
        val enriched = CapAlertParser.parse(imdCap())!!.applyTo(item)
        assertEquals(AlertSeverity.WARNING, enriched.severity)
        assertNotNull(enriched.expiresMs)
        assertEquals(1, enriched.polygons.size)
        assertNotNull(enriched.instruction)
        // Title and description stay the feed's own.
        assertEquals(item.title, enriched.title)
    }

    @Test
    fun `area and centroid of a simple square`() {
        // 1 x 1 degree square on the equator: about 110.574 x 111.320 km.
        val sq = listOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0)
        val (area, c) = Geo.areaAndCentroid(sq)
        assertEquals(110.574 * 111.320 * Math.cos(Math.toRadians(0.5)), area, 1.0)
        assertEquals(0.5, c.first, 1e-6)
        assertEquals(0.5, c.second, 1e-6)
    }

    @Test
    fun `simplify keeps at most the requested number of vertices`() {
        val ring = (0 until 500).flatMap { listOf(it * 0.001, it * 0.002) }
        assertTrue(Geo.simplify(ring, 120).size / 2 <= 120)
        assertEquals(ring.take(20), Geo.simplify(ring.take(20), 120))
    }

    @Test
    fun `the enrichment cache drops long-expired entries and keeps the newest`() {
        val now = 1_000_000_000_000L
        val day = 86_400_000L
        val cache = mapOf(
            "old" to CapEnrichment("old", expiresMs = now - 3 * day, fetchedAtMs = now - 4 * day),
            "recent" to CapEnrichment("recent", expiresMs = now - day, fetchedAtMs = now - 2 * day),
            "live" to CapEnrichment("live", expiresMs = now + day, fetchedAtMs = now),
            "open" to CapEnrichment("open", expiresMs = null, fetchedAtMs = now)
        )
        assertEquals(setOf("recent", "live", "open"), ImdCapEnricher.prune(cache, now).keys)
        val many = (0 until 400).associate { "id$it" to CapEnrichment("id$it", expiresMs = null, fetchedAtMs = it.toLong()) }
        val kept = ImdCapEnricher.prune(many, now)
        assertEquals(60, kept.size)
        assertTrue("id399" in kept && "id0" !in kept)
    }

    @Test
    fun `only a change from not granted is a new location grant`() {
        assertTrue(WorkScheduler.isNewGrant(lastSeen = false, grantedNow = true))
        assertFalse(WorkScheduler.isNewGrant(lastSeen = true, grantedNow = true))
        // First look after an update: an existing user who always had location is not sent through setup.
        assertFalse(WorkScheduler.isNewGrant(lastSeen = null, grantedNow = true))
        assertFalse(WorkScheduler.isNewGrant(lastSeen = false, grantedNow = false))
    }

    private fun rssItem() = ImdAlert(
        identifier  = "urn:oid:2.49.0.1.356.0.2026.9.27.7.55.55",
        title       = "Heavy to very heavy with extremely heavy rainfall",
        description = "Isolated extremely rainfall likely over Uttarakhand.",
        category    = "Met",
        severity    = AlertSeverity.ADVISORY,
        areas       = listOf("Uttarakhand"),
        pubDateMs   = 0L,
        capXmlUrl   = "https://cap-sources.s3.amazonaws.com/in-imd-en/2026-09-27-07-55-55.xml"
    )
}
