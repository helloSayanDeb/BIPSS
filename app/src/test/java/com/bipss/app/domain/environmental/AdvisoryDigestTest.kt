package com.bipss.app.domain.environmental

import com.bipss.app.data.environmental.AdvisoryFeeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Runs on real answers saved from WHO, CDC and ECDC on 30 Sep 2026. */
class AdvisoryDigestTest {

    private fun resource(name: String) =
        requireNotNull(javaClass.classLoader?.getResource("advisories/$name")) { name }.readText()

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-09-30T00:00:00Z").toEpochMilli()
    private val dayMs = 24 * 60 * 60 * 1000L

    private val who by lazy { requireNotNull(AdvisoryFeeds.parseWho(resource("who_don.json"))) }
    private val cdc by lazy { requireNotNull(AdvisoryFeeds.parseRss(resource("cdc_notices.xml"), AdvisorySource.CDC)) }
    private val ecdc by lazy { requireNotNull(AdvisoryFeeds.parseRss(resource("ecdc_updates.xml"), AdvisorySource.ECDC)) }
    private val all by lazy { who + cdc + ecdc }

    @Test
    fun `the three feeds parse`() {
        assertEquals(6, who.size)
        assertTrue(cdc.size >= 20)
        assertTrue(ecdc.size >= 8)
        assertTrue(who.all { it.text.isNotBlank() && !it.text.contains("<") })
        assertEquals(4, cdc.first { it.title.contains("Ituri") }.travelLevel)
        assertNull(AdvisoryFeeds.parseRss("<html><body>502</body></html>", AdvisorySource.CDC))
        assertNull(AdvisoryFeeds.parseWho("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun `one card per outbreak, and nothing older than 30 days`() {
        val cards = AdvisoryDigest.build(all, now, zone)
        cards.forEach { println(it) }
        assertTrue(cards.size <= AdvisoryDigest.MAX_CARDS)
        assertTrue(cards.all { now - it.publishedMs <= 30 * dayMs })
        val ebola = cards.filter { it.title.contains("Ebola") }
        assertEquals(1, ebola.size)
        // WHO on 25 and 10 Sep and ECDC on 28 Sep; WHO's 28 Aug report and CDC's August notices are too old.
        assertEquals(3, ebola.single().updateCount)
        assertEquals("DR Congo", ebola.single().place)
        assertEquals("WHO, ECDC", ebola.single().source)
        assertEquals(AdvisoryScope.ABROAD, ebola.single().scope)
    }

    @Test
    fun `the summary carries the numbers and none of the boilerplate`() {
        val ebola = AdvisoryDigest.build(all, now, zone).single { it.title.contains("Ebola") }
        println(ebola.summary)
        assertFalse(ebola.summary.contains("Disease Outbreak News"))
        assertTrue(ebola.summary, ebola.summary.contains("7890"))
        assertTrue(ebola.summary.length <= AdvisorySummarizer.MAX_CHARS)
        assertEquals("WHO risk: very high in DR Congo, low globally.", ebola.riskNote)
        assertEquals("Ebola disease caused by Bundibugyo virus", ebola.title)
    }

    @Test
    fun `India comes first`() {
        val july = Instant.parse("2026-07-05T00:00:00Z").toEpochMilli()
        val cards = AdvisoryDigest.build(all, july, zone)
        cards.forEach { println(it) }
        val first = cards.first()
        assertEquals(AdvisoryScope.NATIONAL, first.scope)
        assertEquals("Nipah virus disease", first.title)
        assertEquals("India", first.place)
        assertTrue(first.summary, first.summary.contains("Kozhikode"))
        assertNotNull(first.riskNote)
    }

    @Test
    fun `places and scopes`() {
        fun topic(source: AdvisorySource, title: String) =
            OutbreakTopics.of(AdvisoryItem(source, title, title, "", 0L))
        assertEquals(OutbreakTopic("Dengue", "Worldwide", AdvisoryScope.GLOBAL), topic(AdvisorySource.CDC, "Level 1 - Global Dengue"))
        assertEquals(OutbreakTopic("Chikungunya", "Nicaragua", AdvisoryScope.ABROAD), topic(AdvisorySource.CDC, "Level 2 - Chikungunya in Nicaragua"))
        assertEquals("DR Congo", topic(AdvisorySource.CDC,
            "Level 4 - Ebola Bundibugyo Virus Disease in Ituri and Nord-Kivu Provinces of the Democratic Republic of the Congo").place)
        assertEquals(OutbreakTopic("Yellow fever", "Worldwide", AdvisoryScope.GLOBAL), topic(AdvisorySource.WHO, "Yellow fever - Global"))
        assertEquals(AdvisoryScope.GLOBAL, topic(AdvisorySource.WHO, "Hantavirus outbreak linked to cruise ship travel, Multi-locations").scope)
        assertEquals(OutbreakTopic("Nipah", "India", AdvisoryScope.NATIONAL), topic(AdvisorySource.WHO, "Nipah virus disease - India"))
        assertEquals(OutbreakTopic("MERS", "Worldwide", AdvisoryScope.GLOBAL), topic(AdvisorySource.ECDC, "MERS-CoV worldwide overview"))
        assertEquals("Europe", topic(AdvisorySource.ECDC,
            "Epidemiological update – 11 August 2026: Imported case of Andes hantavirus in the EU/EEA").place)
        // A US state is not India.
        assertEquals(AdvisoryScope.ABROAD, topic(AdvisorySource.CDC, "Level 1 - Measles in Indiana").scope)
    }

    @Test
    fun `display titles drop levels, places and prefixes`() {
        fun title(source: AdvisorySource, t: String) = OutbreakTopics.displayTitle(AdvisoryItem(source, t, t, "", 0L))
        assertEquals("Chikungunya", title(AdvisorySource.CDC, "Level 2 - Chikungunya in Nicaragua"))
        assertEquals("Polio", title(AdvisorySource.CDC, "Level 2 - Global Polio"))
        assertEquals("MERS-CoV", title(AdvisorySource.ECDC, "MERS-CoV worldwide overview"))
        assertEquals("Nipah virus disease", title(AdvisorySource.WHO, "Nipah virus disease - India"))
    }

    @Test
    fun `risk notes`() {
        val yellowFever = who.single { it.title.startsWith("Yellow fever") }
        assertEquals("WHO risk: low globally.", AdvisorySummarizer.riskNote(yellowFever.assessment, "Worldwide"))
        assertEquals(
            "WHO risk: very high nationally, low globally.",
            AdvisorySummarizer.riskNote("WHO assessed the risk to be very high at the national level, high at the regional level, and low at the global level.", "DR Congo")
        )
        assertNull(AdvisorySummarizer.riskNote("Contact tracing is challenging.", null))
    }

    @Test
    fun `lead-ins, dates and long phrases are cleaned`() {
        assertEquals(
            "The outbreak has expanded to one additional health zone.",
            AdvisorySummarizer.clean("Since the last Disease Outbreak News was published on 28 August 2026, the outbreak has expanded to one additional health zone.")
        )
        assertEquals(
            "As of 23 Sep, DR Congo has reported 7890 confirmed cases, including 3799 deaths (fatality ratio 48.1%).",
            AdvisorySummarizer.clean("As of 23 September 2026, the Democratic Republic of the Congo has reported 7890 confirmed cases, including 3799 deaths, resulting in a crude case fatality ratio (CFR) of 48.1%.", 2026)
        )
        assertEquals("On 11 Jun 2025, one case.", AdvisorySummarizer.clean("On 11 June 2025, one case.", 2026))
    }

    @Test
    fun `boilerplate alone gives no summary rather than a copy of it`() {
        assertEquals("", AdvisorySummarizer.summarize(
            "Every month ECDC provides detailed epidemiological overview of the worldwide transmission of dengue in its weekly threat report."
        ))
    }

    @Test
    fun `a long sentence is cut at a clause, never mid-word`() {
        val s = "Cases rose in " + (1..40).joinToString(", ") { "district $it" } + "."
        val cut = AdvisorySummarizer.fit(s, 120)
        assertTrue(cut.length <= 120)
        assertTrue(cut.endsWith("…"))
        assertFalse(cut.contains("distr…"))
    }
}
