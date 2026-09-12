package com.bipss.app.domain.environmental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the CPCB sub-index maths against readings taken from the live
 * data.gov.in feed for Kolkata on 8 Sep 2026, so a regression in the
 * breakpoint tables shows up as a failing test rather than a wrong number on
 * stage.
 */
class AqiIndexTest {

    @Test
    fun `pm25 of 62 lands in the moderate band`() {
        // Jadavpur, Kolkata. 61-90 ug/m3 maps to index 101-200, so 62 sits
        // just inside "moderate" - the interesting boundary case.
        val index = AqiIndex.subIndex("PM2.5", 62f)
        assertNotNull(index)
        assertEquals(104, index)
        assertEquals(AqiRiskLevel.MODERATE, AqiRiskLevel.fromAqi(index!!))
    }

    @Test
    fun `band boundaries map exactly`() {
        assertEquals(50, AqiIndex.subIndex("PM2.5", 30f))
        assertEquals(100, AqiIndex.subIndex("PM2.5", 60f))
        assertEquals(50, AqiIndex.subIndex("PM10", 50f))
        assertEquals(100, AqiIndex.subIndex("PM10", 100f))
    }

    @Test
    fun `aqi is the maximum sub-index and names the pollutant that set it`() {
        // Ballygunge, Kolkata: PM10 57, NO2 38.
        val result = AqiIndex.compute(
            listOf(
                AqiIndex.PollutantReading("PM10", 57f),
                AqiIndex.PollutantReading("NO2", 38f)
            )
        )
        assertNotNull(result)
        assertEquals("PM10", result!!.dominantPollutant)
        assertTrue(result.aqi > 0)
        assertEquals(result.aqi, result.subIndices.values.max())
    }

    @Test
    fun `ozone is accepted under both spellings`() {
        assertEquals(AqiIndex.subIndex("O3", 90f), AqiIndex.subIndex("OZONE", 90f))
    }

    @Test
    fun `unknown pollutant yields null rather than a clean-air zero`() {
        // Returning 0 would read as "good air" for a pollutant we cannot score.
        assertNull(AqiIndex.subIndex("BENZENE", 12f))
    }

    @Test
    fun `negative concentration is rejected`() {
        assertNull(AqiIndex.subIndex("PM2.5", -1f))
    }

    @Test
    fun `implausible CO is dropped instead of dominating the index`() {
        // A Kolkata station reported CO 31 on 8 Sep 2026. Read as mg/m3 that is
        // a "very poor" 380-ish and would take over the maximum; the PM2.5
        // beside it says moderate. The row must be discarded, not converted.
        val result = AqiIndex.compute(
            listOf(
                AqiIndex.PollutantReading("PM2.5", 62f),
                AqiIndex.PollutantReading("CO", 31f)
            )
        )
        assertNotNull(result)
        assertEquals("PM2.5", result!!.dominantPollutant)
        assertEquals(104, result.aqi)
        assertTrue("CO must not contribute", !result.subIndices.containsKey("CO"))
    }

    @Test
    fun `plausible CO still counts`() {
        val result = AqiIndex.compute(listOf(AqiIndex.PollutantReading("CO", 1.5f)))
        assertNotNull(result)
        assertEquals("CO", result!!.dominantPollutant)
    }

    @Test
    fun `no usable rows yields null rather than a default AQI`() {
        assertNull(AqiIndex.compute(emptyList()))
        assertNull(AqiIndex.compute(listOf(AqiIndex.PollutantReading("PM2.5", null))))
    }

    @Test
    fun `index is clamped at 500`() {
        val index = AqiIndex.subIndex("PM2.5", 100_000f)
        assertNotNull(index)
        assertEquals(500, index)
    }
}
