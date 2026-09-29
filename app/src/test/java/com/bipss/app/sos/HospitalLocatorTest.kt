package com.bipss.app.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HospitalLocatorTest {

    // Central Kolkata, where the coverage check was run.
    private val lat = 22.5726
    private val lon = 88.3639

    /**
     * Seven real elements from the Overpass answer for Kolkata (29 Sep 2026),
     * tags trimmed to the ones read: the nearest hospital (no number), three
     * landline-only ones, one with a mobile, and one campus mapped twice.
     */
    private val kolkata = """
    {"elements": [
     {"type": "node", "id": 1189036837, "lat": 22.5722242, "lon": 88.3614363,
      "tags": {"amenity": "hospital", "name": "Calcutta Medical College & Hospital", "name:en": "Calcutta Medical College & Hospital"}},
     {"type": "node", "id": 7065265207, "lat": 22.5819362, "lon": 88.355944,
      "tags": {"amenity": "hospital", "name": "Shree Vishudhanand Hospital and Research Institute", "phone": "033 40233030"}},
     {"type": "node", "id": 7071458833, "lat": 22.5478931, "lon": 88.3602614,
      "tags": {"amenity": "hospital", "name": "Mission Of Mercy Hospital And Research Centre", "phone": "+91 983 600 8484"}},
     {"type": "way", "id": 103477674, "center": {"lat": 22.5755725, "lon": 88.3681251},
      "tags": {"amenity": "hospital", "contact:phone": "03330612200;22414896", "name": "SVS Marwari Hospital"}},
     {"type": "way", "id": 637058205, "center": {"lat": 22.5470051, "lon": 88.3620768},
      "tags": {"amenity": "hospital", "name": "Institute Of Neurosciences Kolkata", "phone": "+913340309999;+913322867094;+919830864646;+919830964646"}},
     {"type": "way", "id": 656173034, "center": {"lat": 22.5532667, "lon": 88.3543494},
      "tags": {"amenity": "hospital", "contact:phone": "3322271666", "name": "Royd Nursing Home and Healthcare Limited", "phone": "+91-33-3354-7000;+91-33-3354-7001;+91-33-3354-7002;+91-33-3354-7003"}},
     {"type": "way", "id": 1490199793, "center": {"lat": 22.5420163, "lon": 88.3591358},
      "tags": {"amenity": "hospital", "name": "Institute of Neurosciences OPD Building", "phone": "+913340309999;+913322867094;+919830864646;+919830964646"}}
    ]}
    """.trimIndent()

    @Test
    fun `the nearest three with a usable number, nearest first - the closest hospital lists none`() {
        val lookup = HospitalParser.parse(kolkata, lat, lon)!!
        assertEquals(HospitalLookup.Status.FOUND, lookup.status)
        assertEquals(7, lookup.found)
        assertEquals(
            listOf("SVS Marwari Hospital", "Shree Vishudhanand Hospital and Research Institute",
                   "Royd Nursing Home and Healthcare Limited"),
            lookup.hospitals.map { it.name }
        )
        assertTrue(lookup.hospitals.zipWithNext().all { (a, b) -> a.distanceKm <= b.distanceKm })
        // None of the three nearest can receive a text: all landlines.
        assertTrue(lookup.hospitals.all { it.mobile == null && it.landline != null })
        assertEquals("+913330612200", lookup.hospitals[0].landline)
    }

    @Test
    fun `mobiles are recognised and a campus mapped twice counts once`() {
        val lookup = HospitalParser.parse(kolkata, lat, lon, limit = 10)!!
        val names = lookup.hospitals.map { it.name }
        assertEquals(5, names.size)
        assertFalse("Institute of Neurosciences OPD Building" in names)
        val mercy = lookup.hospitals.single { it.name.startsWith("Mission Of Mercy") }
        assertEquals("+919836008484", mercy.mobile)
        assertEquals("+919836008484", mercy.callNumber)
        val ins = lookup.hospitals.single { it.name == "Institute Of Neurosciences Kolkata" }
        assertEquals("+919830864646", ins.mobile)
        assertEquals("+913340309999", ins.callNumber)   // the switchboard, for a call
    }

    @Test
    fun `places that cannot take an emergency are left out`() {
        val answer = """{"elements": [
            {"type": "node", "id": 1, "lat": 22.548, "lon": 88.3603, "tags": {"name": "Priyamvada Birla Aravind Eye Hospital", "phone": "+91 33 2289 4343"}},
            {"type": "node", "id": 2, "lat": 22.5481, "lon": 88.3604, "tags": {"name": "Probal Institute", "healthcare:speciality": "ophthalmology", "phone": "+91 33 2200 0001"}},
            {"type": "node", "id": 3, "lat": 22.5482, "lon": 88.3605, "tags": {"name": "Some Clinic", "emergency": "no", "phone": "+91 33 2200 0002"}},
            {"type": "node", "id": 4, "lat": 22.5483, "lon": 88.3606, "tags": {"name": "Netaji Netra Niketan", "phone": "+91 33 2200 0003"}},
            {"type": "node", "id": 5, "lat": 22.5484, "lon": 88.3607, "tags": {"name": "City General Hospital", "healthcare:speciality": "general;ophthalmology;emergency", "phone": "+91 33 2200 0004"}},
            {"type": "node", "id": 6, "lat": 22.5485, "lon": 88.3608, "tags": {"name": "Institute of Neurosciences", "healthcare:speciality": "neurology", "phone": "+91 33 2200 0005"}}
        ]}"""
        val lookup = HospitalParser.parse(answer, 22.5478931, 88.3602614, limit = 10)!!
        assertEquals(listOf("City General Hospital", "Institute of Neurosciences"), lookup.hospitals.map { it.name })
    }

    @Test
    fun `a busy server's HTML page is a failed read, not an empty map`() {
        assertNull(HospitalParser.parse("<html><body>Dispatcher_Client::request_read_and_idx::timeout</body></html>", lat, lon))
        assertEquals(HospitalLookup.Status.NONE_NEARBY, HospitalParser.parse("""{"elements": []}""", lat, lon)!!.status)
        val noNumbers = """{"elements": [{"type": "node", "id": 1, "lat": 22.57, "lon": 88.36, "tags": {"name": "A"}}]}"""
        val lookup = HospitalParser.parse(noNumbers, lat, lon)!!
        assertEquals(HospitalLookup.Status.NONE_WITH_NUMBER, lookup.status)
        assertEquals(1, lookup.found)
    }

    @Test
    fun `the query covers nodes, ways and relations tagged either way, and counts them all`() {
        val q = HospitalParser.query(lat, lon, 15_000)
        assertTrue(q.contains("nwr[\"amenity\"=\"hospital\"](around:15000,22.572600,88.363900)"))
        assertTrue(q.contains("nwr[\"healthcare\"=\"hospital\"](around:15000,22.572600,88.363900)"))
        assertTrue(q.contains(".h out count;"))
        assertTrue(q.endsWith("out center tags;"))
    }

    @Test
    fun `the count element gives the total, including hospitals listing no number`() {
        val answer = """{"elements": [
            {"type": "count", "id": 0, "tags": {"nodes": "244", "ways": "78", "relations": "1", "total": "323"}},
            {"type": "node", "id": 2, "lat": 22.5478931, "lon": 88.3602614, "tags": {"name": "Mission Of Mercy", "phone": "+91 983 600 8484"}}
        ]}"""
        val lookup = HospitalParser.parse(answer, lat, lon)!!
        assertEquals(323, lookup.found)
        assertEquals(listOf("Mission Of Mercy"), lookup.hospitals.map { it.name })
        // Counted but none listed: mapped, just without numbers.
        val none = HospitalParser.parse("""{"elements": [{"type": "count", "id": 0, "tags": {"total": "12"}}]}""", lat, lon)!!
        assertEquals(HospitalLookup.Status.NONE_WITH_NUMBER, none.status)
        assertEquals(12, none.found)
        val empty = HospitalParser.parse("""{"elements": [{"type": "count", "id": 0, "tags": {"total": "0"}}]}""", lat, lon)!!
        assertEquals(HospitalLookup.Status.NONE_NEARBY, empty.status)
    }

    @Test
    fun `Indian number formats as mapped`() {
        fun parse(v: String, codes: IndianPhone.AreaCodes? = null) = IndianPhone.parseTag(v, codes).map { it.e164 to it.isMobile }
        assertEquals(listOf("+919836008484" to true), parse("+91 983 600 8484"))
        assertEquals(listOf("+919830012345" to true), parse("0091 98300 12345"))
        assertEquals(listOf("+919830012345" to true), parse("919830012345"))
        assertEquals(listOf("+913333547000" to false), parse("+91-33-3354-7000"))
        // "/39" and "/05" are alternate endings, not numbers.
        assertEquals(listOf("+913324320043" to false, "+919874210007" to true), parse("033 2432 0043/39;9874210007/05"))
        // The local number takes the area code of the full number beside it.
        assertEquals(listOf("+913324628677" to false, "+913324628678" to false), parse("03324628677;24628678"))
        // Another country's number is not treated as Indian.
        assertTrue(parse("+44 20 7946 0000").isEmpty())
    }

    @Test
    fun `a bare local number gets the area code nearby numbers clearly agree on, else nothing`() {
        fun n(e164: String) = IndianPhone.Number(e164, isMobile = false)
        val kolkataCodes = IndianPhone.AreaCodes(listOf(n("+913340233030"), n("+913330612200"), n("+913322271666"), n("+919830864646").copy(isMobile = true)))
        assertEquals(listOf("+913322651808"), IndianPhone.parseTag("22651808", kolkataCodes).map { it.e164 })
        // Without evidence the number is dropped - a guessed area code is a wrong number.
        assertTrue(IndianPhone.parseTag("22651808", null).isEmpty())
        assertTrue(IndianPhone.parseTag("22651808", IndianPhone.AreaCodes(listOf(n("+913340233030"), n("+913330612200")))).isEmpty())
        val split = IndianPhone.AreaCodes(listOf(n("+913340233030"), n("+913330612200"), n("+913322271666"),
            n("+911123456789"), n("+911198765432"), n("+911155555555")))
        assertNull(split.forLocal("22651808"))
        // A 7-digit local number needs a 3-digit code; the Kolkata numbers do not agree on one.
        assertNull(kolkataCodes.forLocal("2265180"))
    }

    // ── Messages ─────────────────────────────────────────────────────────────

    private val loc = SosLocation(22.5726, 88.3639)
    private val mercy = Hospital("Mission Of Mercy Hospital And Research Centre", 2.8, "+919836008484", null)
    private val svs = Hospital("SVS Marwari Hospital", 0.5, null, "+913330612200")

    @Test
    fun `contacts are told a hospital was sent the alert only when the network confirmed it`() {
        val confirmed = SosMessageBuilder.contactMessage("Test User", "29 Sep 2026, 17:40 IST", loc,
            SosMessageBuilder.HospitalNotice(alerted = listOf(mercy), toCall = listOf(svs)))
        assertTrue(confirmed, confirmed.contains("Nearby hospitals were also sent this alert: Mission Of Mercy Hospital And Research Centre +919836008484."))
        assertTrue(confirmed, confirmed.contains("Also near them: SVS Marwari Hospital +913330612200."))
        assertTrue(confirmed, confirmed.contains("If you're not nearby, please call them directly to confirm and coordinate help."))

        val pending = SosMessageBuilder.contactMessage("Test User", "t", loc, SosMessageBuilder.HospitalNotice(beingAlerted = listOf(mercy)))
        assertTrue(pending, pending.contains("Nearby hospitals are also being alerted: Mission Of Mercy"))
        assertFalse(pending, pending.contains("were also sent"))

        val callOnly = SosMessageBuilder.contactMessage("Test User", "t", loc, SosMessageBuilder.HospitalNotice(toCall = listOf(svs)))
        assertTrue(callOnly, callOnly.contains("Nearest hospitals: SVS Marwari Hospital +913330612200."))
        assertFalse(callOnly, callOnly.contains("alert:") || callOnly.contains("being alerted"))

        // No hospitals: the message is exactly what it was before.
        assertEquals(
            SosMessageBuilder.contactMessage("Test User", "t", loc),
            SosMessageBuilder.contactMessage("Test User", "t", loc, SosMessageBuilder.HospitalNotice())
        )
    }

    @Test
    fun `hospitals get the alert plus the emergency contacts`() {
        val msg = SosMessageBuilder.hospitalMessage("Test User", "29 Sep 2026, 17:40 IST", loc,
            listOf(SmsRecipient("Asha Test", "+919800000001")))
        assertTrue(msg, msg.startsWith("EMERGENCY: Test User triggered an SOS alert at 29 Sep 2026, 17:40 IST. Location: https://maps.google.com/?q=22.572600,88.363900."))
        assertTrue(msg, msg.contains("This number is listed for a hospital near them."))
        assertTrue(msg, msg.contains("Their emergency contacts: Asha Test +919800000001. Please also contact these people if you're able to assist, as the user may not be able to respond by phone."))
        val none = SosMessageBuilder.hospitalMessage("Test User", "t", loc, emptyList())
        assertFalse(none, none.contains("emergency contacts"))
    }

    @Test
    fun `messages stay in the GSM alphabet, so a part holds 160 characters`() {
        val msg = SosMessageBuilder.contactMessage("Test User", "29 Sep 2026, 17:40 IST", loc,
            SosMessageBuilder.HospitalNotice(alerted = listOf(mercy), toCall = listOf(svs)))
        assertTrue(SosMessageBuilder.isGsm7(msg))
        // Curly quotes and dashes from a map name would switch the whole text to UCS-2 (70 per part).
        val curly = Hospital("St. Mary’s – Park Street", 1.0, null, "+913312345678")
        assertTrue(SosMessageBuilder.isGsm7(SosMessageBuilder.contactMessage("A", "t", loc, SosMessageBuilder.HospitalNotice(toCall = listOf(curly)))))
        // A name in another script is kept, not transliterated; the message is then UCS-2.
        assertFalse(SosMessageBuilder.isGsm7("কলকাতা"))
    }
}
