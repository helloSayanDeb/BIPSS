package com.bipss.app.domain.environmental

/**
 * India's National AQI computed from modelled pollutant concentrations - the
 * fallback when no CPCB station reading is available.
 *
 * The concentrations come from the CAMS global atmospheric-composition model
 * (via Open-Meteo), about 40 km across per grid cell: an estimate of the air
 * over the area, not a measurement at a station, and always labelled so.
 *
 * Averaging follows the CPCB index: 24-hour means for PM2.5, PM10, NO2 and
 * SO2, 8-hour means for CO and ozone, each ending at the latest hour that is
 * not in the future. A mean needs three quarters of its hours (CPCB's own
 * completeness rule: 16 of 24, 6 of 8), and the index needs three pollutants
 * including a particulate one, as CPCB's does.
 */
object ModelledAqi {

    /** Hourly series on one shared time axis. Concentrations in ug/m3, CO included. */
    data class Hourly(
        val timesMs: List<Long>,
        val pm25:    List<Float?>,
        val pm10:    List<Float?>,
        val no2:     List<Float?>,
        val so2:     List<Float?>,
        val coUg:    List<Float?>,
        val o3:      List<Float?>
    )

    data class Result(val computation: AqiIndex.AqiComputation, val observedAtMs: Long)

    fun compute(h: Hourly, nowMs: Long): Result? {
        val i = h.timesMs.indexOfLast { it <= nowMs }
        if (i < 0) return null
        fun mean(series: List<Float?>, hours: Int): Float? {
            val from = maxOf(0, i - hours + 1)
            val to = minOf(series.size, i + 1)
            if (from >= to) return null
            val window = series.subList(from, to).filterNotNull()
            return if (window.size * 4 >= hours * 3) window.average().toFloat() else null
        }
        val readings = listOf(
            AqiIndex.PollutantReading(AqiIndex.ID_PM25, mean(h.pm25, 24)),
            AqiIndex.PollutantReading(AqiIndex.ID_PM10, mean(h.pm10, 24)),
            AqiIndex.PollutantReading(AqiIndex.ID_NO2, mean(h.no2, 24)),
            AqiIndex.PollutantReading(AqiIndex.ID_SO2, mean(h.so2, 24)),
            // The CO breakpoints are in mg/m3.
            AqiIndex.PollutantReading(AqiIndex.ID_CO, mean(h.coUg, 8)?.div(1000f)),
            AqiIndex.PollutantReading(AqiIndex.ID_OZONE, mean(h.o3, 8))
        )
        val computed = AqiIndex.compute(readings)?.takeIf { it.meetsCpcbMinimum } ?: return null
        return Result(computed, h.timesMs[i])
    }
}
