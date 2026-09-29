package com.bipss.app.domain.environmental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelledAqiTest {

    private val hourMs = 3_600_000L
    private val t0 = 1_790_000_000_000L

    private fun hourly(hours: Int = 48, pm25: Float? = 84.7f, pm10: Float? = 91.4f, coUg: Float? = 1750f) =
        ModelledAqi.Hourly(
            timesMs = List(hours) { t0 + it * hourMs },
            pm25 = List(hours) { pm25 },
            pm10 = List(hours) { pm10 },
            no2  = List(hours) { 25f },
            so2  = List(hours) { 30f },
            coUg = List(hours) { coUg },
            o3   = List(hours) { 59f }
        )

    @Test
    fun `the index is the largest sub-index, from 24- and 8-hour means`() {
        val now = t0 + 30 * hourMs + 20 * 60_000L
        val r = ModelledAqi.compute(hourly(), now)!!
        // PM2.5 84.7 ug/m3 sits in CPCB's 61-90 band (101-200).
        assertEquals(182, r.computation.aqi)
        assertEquals(AqiIndex.ID_PM25, r.computation.dominantPollutant)
        // CO arrives in ug/m3 and is converted: 1.75 mg/m3 is in the 1.1-2 band.
        assertEquals(86, r.computation.subIndices[AqiIndex.ID_CO])
        // The latest hour that has started, not a forecast hour.
        assertEquals(t0 + 30 * hourMs, r.observedAtMs)
    }

    @Test
    fun `a mean needs three quarters of its hours`() {
        val h = hourly()
        val sparse = h.copy(pm25 = h.pm25.mapIndexed { i, v -> if (i % 2 == 0) v else null })
        val r = ModelledAqi.compute(sparse, t0 + 30 * hourMs)!!
        // Half the PM2.5 hours: no PM2.5 index; PM10 still qualifies the result.
        assertNull(r.computation.subIndices[AqiIndex.ID_PM25])
        assertEquals(AqiIndex.ID_PM10, r.computation.dominantPollutant)
    }

    @Test
    fun `no particulate reading means no index, as with CPCB`() {
        assertNull(ModelledAqi.compute(hourly(pm25 = null, pm10 = null), t0 + 30 * hourMs))
    }

    @Test
    fun `nothing before the first hour`() {
        assertNull(ModelledAqi.compute(hourly(), t0 - 1))
    }
}
