package com.bipss.app.domain.environmental

import com.bipss.app.domain.BaselineStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AirContextTest {

    private fun snap(
        visible: Int = 10, used: Int = 8, cn0: Double? = 42.0, acc: Double? = 6.0,
        speed: Double? = 0.4, moved: Double? = 20.0, activity: Double? = 900.0
    ) = GnssSnapshot(visible, used, cn0, acc, speed, moved, activity)

    // ── Classifier ───────────────────────────────────────────────────────────

    @Test
    fun `open sky with a good fix is outdoors, with the evidence stated`() {
        val c = EnvContextClassifier.classify(snap())
        assertEquals(EnvContextKind.OUTDOOR, c.kind)
        assertTrue(c.confidence >= 0.8f)
        assertTrue(c.isConfident)
        assertTrue(c.evidence.any { it.startsWith("Strongest satellites at 42 dB-Hz") })
        assertTrue(c.evidence.any { it == "8 of 10 satellites used in the position fix" })
    }

    @Test
    fun `attenuated signal, few satellites, poor fix and sitting still is indoors`() {
        val c = EnvContextClassifier.classify(snap(visible = 5, used = 2, cn0 = 22.0, acc = 65.0, activity = 150.0))
        assertEquals(EnvContextKind.INDOOR, c.kind)
        assertEquals(0.8f, c.confidence, 1e-6f)
        assertTrue(c.evidence.contains("Band shows you mostly still"))
    }

    @Test
    fun `no satellites heard at all is weak evidence of indoors`() {
        val c = EnvContextClassifier.classify(snap(visible = 0, used = 0, cn0 = null, acc = null, activity = null))
        assertEquals(EnvContextKind.INDOOR, c.kind)
        assertEquals(0.6f, c.confidence, 1e-6f)
        assertEquals("No satellites heard during the check", c.evidence.first())
    }

    @Test
    fun `a weak signal while travelling is not called indoors - a metro or a car is neither`() {
        val c = EnvContextClassifier.classify(snap(visible = 4, used = 2, cn0 = 24.0, acc = 80.0, speed = 11.0, moved = 900.0))
        assertEquals(EnvContextKind.UNKNOWN, c.kind)
        assertFalse(c.isConfident)
        assertTrue(c.evidence.any { it.startsWith("Travelling at 40 km/h") })
    }

    @Test
    fun `in between - by a window, under a tree - is said to be unclear`() {
        val c = EnvContextClassifier.classify(snap(visible = 8, used = 5, cn0 = 31.0, acc = 25.0))
        assertEquals(EnvContextKind.UNKNOWN, c.kind)
        assertFalse(c.isConfident)
    }

    @Test
    fun `strong signal is only outdoors with a usable fix or enough satellites`() {
        // Strong top satellites but a single one in the fix and no accuracy: not enough.
        val c = EnvContextClassifier.classify(snap(visible = 3, used = 1, cn0 = 38.0, acc = null))
        assertEquals(EnvContextKind.UNKNOWN, c.kind)
    }

    // ── Reconciler ───────────────────────────────────────────────────────────

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(day: Int, hour: Int = 12) =
        LocalDate.of(2026, 9, 1).plusDays(day.toLong()).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun out(day: Int, uba: Int, aqi: Int, conf: Float = 0.8f) = AqiContextSample(at(day), EnvContextKind.OUTDOOR, conf, uba, aqi)
    private fun inn(day: Int, uba: Int, hour: Int = 20) = AqiContextSample(at(day, hour), EnvContextKind.INDOOR, 0.8f, uba, 150)

    @Test
    fun `outdoors, each band category learns the station AQI it comes with`() {
        val samples = listOf(120, 130, 140, 150, 160).mapIndexed { i, a -> out(i, 3, a) } +
            listOf(60, 70, 80, 90).mapIndexed { i, a -> out(i, 2, a) }   // one short of the minimum
        val rec = AqiReconciler.build(samples, zone)
        val three = rec.outdoor!!.byUba[3]!!
        assertEquals(140.0, three.median, 1e-9)
        assertEquals(130.0, three.q1, 1e-9)
        assertEquals(150.0, three.q3, 1e-9)
        assertEquals(5, three.n)
        assertNull(rec.outdoor!!.byUba[2])
    }

    @Test
    fun `indoors, the user's own usual range - and nothing from unconfident or unknown checks`() {
        val indoors = (0 until 20).map { inn(it, if (it < 17) 2 else 3) }
        val noise = listOf(
            AqiContextSample(at(1), EnvContextKind.INDOOR, 0.4f, 5, 150),      // not confident
            AqiContextSample(at(2), EnvContextKind.UNKNOWN, 0.3f, 5, 150)      // unknown
        )
        val rec = AqiReconciler.build(indoors + noise, zone)
        assertEquals(2.0, rec.indoor!!.medianUba, 1e-9)
        assertEquals(3.0, rec.indoor!!.p90Uba, 1e-9)
        assertEquals(20, rec.indoor!!.samples)
    }

    @Test
    fun `the baseline day count is the shared schedule, days with a confident band check`() {
        val rec = AqiReconciler.build((0 until 16).map { inn(it, 2) } + inn(3, 2, hour = 9), zone)
        assertEquals(16, rec.dayN)
        assertEquals(BaselineStage.PROVISIONAL, rec.stage)
        assertEquals(BaselineStage.CALIBRATING, AqiReconciler.build(emptyList(), zone).stage)
        // Checks without a band reading do not count.
        assertEquals(0, AqiReconciler.build(listOf(AqiContextSample(at(0), EnvContextKind.OUTDOOR, 0.8f, null, 120)), zone).dayN)
    }

    // ── Display ──────────────────────────────────────────────────────────────

    private val outdoors = EnvContext(EnvContextKind.OUTDOOR, 0.8f, emptyList())
    private val indoors = EnvContext(EnvContextKind.INDOOR, 0.8f, emptyList())

    @Test
    fun `without a band reading it is the station, as before`() {
        assertEquals(AqiDisplay.StationOnly(142), AqiDisplay.decide(outdoors, 60_000L, null, 142, Reconciliation.EMPTY))
    }

    @Test
    fun `the context decides which reading leads - the readings never decide the context`() {
        val rec = AqiReconciler.build(listOf(120, 130, 140, 150, 160).mapIndexed { i, a -> out(i, 3, a) }, zone)
        val o = AqiDisplay.decide(outdoors, 60_000L, 3, 142, rec)
        assertTrue(o is AqiDisplay.Outdoor)
        assertEquals(140.0, (o as AqiDisplay.Outdoor).expected!!.median, 1e-9)

        val i = AqiDisplay.decide(indoors, 60_000L, 2, 142, rec)
        assertTrue(i is AqiDisplay.Indoor)
        assertEquals(142, (i as AqiDisplay.Indoor).cpcbAqi)
        assertNull(i.usual)   // not learned yet

        // Unclear, unconfident or stale: both, side by side.
        assertTrue(AqiDisplay.decide(EnvContext(EnvContextKind.UNKNOWN, 0.3f, emptyList()), 60_000L, 3, 142, rec) is AqiDisplay.Unclear)
        assertTrue(AqiDisplay.decide(EnvContext(EnvContextKind.INDOOR, 0.5f, emptyList()), 60_000L, 3, 142, rec) is AqiDisplay.Unclear)
        assertTrue(AqiDisplay.decide(outdoors, 25 * 60_000L, 3, 142, rec) is AqiDisplay.Unclear)
        assertTrue(AqiDisplay.decide(null, null, 3, 142, rec) is AqiDisplay.Unclear)
    }

    @Test
    fun `quantiles interpolate`() {
        assertEquals(2.5, AqiReconciler.quantile(listOf(1.0, 2.0, 3.0, 4.0), 0.5), 1e-9)
        assertEquals(7.0, AqiReconciler.quantile(listOf(7.0), 0.9), 1e-9)
        assertNotNull(AqiDisplay.ubaLabel(3))
    }
}
