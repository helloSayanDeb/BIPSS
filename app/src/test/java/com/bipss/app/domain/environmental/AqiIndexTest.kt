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
    fun `concentration path no longer drops high CO`() {
        // 20 mg/m3 of CO is genuinely severe air and must count, not be discarded.
        val result = AqiIndex.compute(listOf(AqiIndex.PollutantReading("CO", 20f)))
        assertNotNull(result)
        assertEquals("CO", result!!.dominantPollutant)
        assertTrue(result.aqi > 300)
    }

    @Test
    fun `plausible CO still counts`() {
        val result = AqiIndex.compute(listOf(AqiIndex.PollutantReading("CO", 1.5f)))
        assertNotNull(result)
        assertEquals("CO", result!!.dominantPollutant)
    }

    // -- The data.gov.in feed publishes sub-indices, not concentrations ----------

    @Test
    fun `feed values are taken as sub-indices, not converted again`() {
        // Kolkata-style row set: PM2.5 index 62, PM10 57, CO 28 (~0.56 mg/m3), NO2 20.
        val result = AqiIndex.fromSubIndices(listOf(
            AqiIndex.PollutantReading("PM2.5", 62f),
            AqiIndex.PollutantReading("PM10", 57f),
            AqiIndex.PollutantReading("CO", 28f),
            AqiIndex.PollutantReading("NO2", 20f)
        ))
        assertNotNull(result)
        // The old double conversion turned PM2.5 62 into 104 ("moderate").
        assertEquals(62, result!!.aqi)
        assertEquals("PM2.5", result.dominantPollutant)
        assertEquals(28, result.subIndices["CO"])
        assertTrue(result.meetsCpcbMinimum)
    }

    @Test
    fun `high CO sub-index is kept and can dominate`() {
        val result = AqiIndex.fromSubIndices(listOf(
            AqiIndex.PollutantReading("PM2.5", 80f),
            AqiIndex.PollutantReading("PM10", 70f),
            AqiIndex.PollutantReading("CO", 240f)
        ))
        assertEquals("CO", result!!.dominantPollutant)
        assertEquals(240, result.aqi)
    }

    @Test
    fun `fewer than three pollutants does not meet the CPCB minimum`() {
        val result = AqiIndex.fromSubIndices(listOf(
            AqiIndex.PollutantReading("PM2.5", 62f),
            AqiIndex.PollutantReading("CO", 28f)
        ))
        assertNotNull(result)
        assertTrue(!result!!.meetsCpcbMinimum)
    }

    @Test
    fun `three gases without particulate do not meet the CPCB minimum`() {
        val result = AqiIndex.fromSubIndices(listOf(
            AqiIndex.PollutantReading("NO2", 20f),
            AqiIndex.PollutantReading("SO2", 10f),
            AqiIndex.PollutantReading("CO", 28f)
        ))
        assertTrue(!result!!.meetsCpcbMinimum)
    }

    @Test
    fun `a value outside 0 to 500 is not a sub-index and is dropped`() {
        val result = AqiIndex.fromSubIndices(listOf(
            AqiIndex.PollutantReading("PM2.5", 62f),
            AqiIndex.PollutantReading("PM10", 9000f)
        ))
        assertEquals(1, result!!.subIndices.size)
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
