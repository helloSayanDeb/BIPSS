package com.bipss.app.domain.environmental

import kotlin.math.roundToInt

/**
 * CPCB National Air Quality Index.
 *
 * **What the data.gov.in feed actually reports.** Resource 3b01bcb8 ("Real time
 * Air Quality Index") returns one row per pollutant per station, and its
 * `min_value` / `avg_value` / `max_value` are that pollutant's **AQI
 * sub-index**, not a concentration. This was established from the feed
 * itself: CO arrives around 20-35 at ordinary Kolkata stations, which is
 * impossible as mg/m3 (34 is the top of the "severe" band) and far too low as
 * ug/m3, but is exactly the CO sub-index of ~0.4-0.7 mg/m3 air; independent
 * comparisons against CPCB's archive show the same (feed CO 28.0 against an
 * archived 0.56 mg/m3). The previous code fed those sub-indices back through
 * the concentration tables below - converting every pollutant twice - and then
 * discarded almost every CO row as "implausible", which silently removed CO
 * even on the days it genuinely mattered.
 *
 * [fromSubIndices] is therefore what the feed path uses: the station AQI is the
 * largest sub-index, subject to CPCB's validity rule (at least three
 * pollutants, one of them PM2.5 or PM10). The published concentration
 * breakpoints are kept in [subIndex]/[compute] for sources that genuinely
 * report concentrations. In those tables CO is in mg/m3 and everything else
 * in ug/m3.
 *
 * Pure Kotlin, no Android dependencies - unit-testable.
 */
object AqiIndex {

    /** The scale is defined to 500. Beyond that the index is reported as 500, not extrapolated on. */
    const val MAX_INDEX = 500

    // -- Canonical pollutant ids ------------------------------------------------

    /** Spelt as data.gov.in spells them, so an id survives a round trip unchanged. */
    const val ID_PM25  = "PM2.5"
    const val ID_PM10  = "PM10"
    const val ID_NO2   = "NO2"
    const val ID_SO2   = "SO2"
    const val ID_CO    = "CO"
    const val ID_OZONE = "OZONE"
    const val ID_NH3   = "NH3"

    /** One breakpoint row: a concentration range mapped linearly onto an index range. */
    data class Band(
        val concLow:   Float,
        val concHigh:  Float,
        val indexLow:  Int,
        val indexHigh: Int
    )

    /*
     * CPCB publishes the top band open-ended - "250+" for PM2.5, and so on - so
     * there is no upper concentration to interpolate against. Each table closes
     * its top band by repeating the width of the band beneath it, which keeps
     * the slope continuous across the 400/401 boundary rather than inventing a
     * steeper or shallower one. Concentrations past that closing point stay on
     * the same slope and are clamped to MAX_INDEX.
     */

    /** PM2.5, 24-hour average, ug/m3. */
    private val PM25 = listOf(
        Band(    0f,    30f,   0,  50),
        Band(   31f,    60f,  51, 100),
        Band(   61f,    90f, 101, 200),
        Band(   91f,   120f, 201, 300),
        Band(  121f,   250f, 301, 400),
        Band(  250f,   379f, 401, 500)
    )

    /** PM10, 24-hour average, ug/m3. */
    private val PM10 = listOf(
        Band(    0f,    50f,   0,  50),
        Band(   51f,   100f,  51, 100),
        Band(  101f,   250f, 101, 200),
        Band(  251f,   350f, 201, 300),
        Band(  351f,   430f, 301, 400),
        Band(  430f,   509f, 401, 500)
    )

    /** NO2, 24-hour average, ug/m3. */
    private val NO2 = listOf(
        Band(    0f,    40f,   0,  50),
        Band(   41f,    80f,  51, 100),
        Band(   81f,   180f, 101, 200),
        Band(  181f,   280f, 201, 300),
        Band(  281f,   400f, 301, 400),
        Band(  400f,   519f, 401, 500)
    )

    /** SO2, 24-hour average, ug/m3. */
    private val SO2 = listOf(
        Band(    0f,    40f,   0,  50),
        Band(   41f,    80f,  51, 100),
        Band(   81f,   380f, 101, 200),
        Band(  381f,   800f, 201, 300),
        Band(  801f,  1600f, 301, 400),
        Band( 1600f,  2399f, 401, 500)
    )

    /** CO, 8-hour average, mg/m3 - not ug/m3. See the unit note at the top. */
    private val CO = listOf(
        Band(    0f,     1f,   0,  50),
        Band(  1.1f,     2f,  51, 100),
        Band(  2.1f,    10f, 101, 200),
        Band( 10.1f,    17f, 201, 300),
        Band( 17.1f,    34f, 301, 400),
        Band(   34f,  50.9f, 401, 500)
    )

    /** Ozone, 8-hour average, ug/m3. */
    private val OZONE = listOf(
        Band(    0f,    50f,   0,  50),
        Band(   51f,   100f,  51, 100),
        Band(  101f,   168f, 101, 200),
        Band(  169f,   208f, 201, 300),
        Band(  209f,   748f, 301, 400),
        Band(  748f,  1287f, 401, 500)
    )

    /** NH3, 24-hour average, ug/m3. */
    private val NH3 = listOf(
        Band(    0f,   200f,   0,  50),
        Band(  201f,   400f,  51, 100),
        Band(  401f,   800f, 101, 200),
        Band(  801f,  1200f, 201, 300),
        Band( 1201f,  1800f, 301, 400),
        Band( 1800f,  2399f, 401, 500)
    )

    private val TABLES: Map<String, List<Band>> = mapOf(
        ID_PM25  to PM25,
        ID_PM10  to PM10,
        ID_NO2   to NO2,
        ID_SO2   to SO2,
        ID_CO    to CO,
        ID_OZONE to OZONE,
        ID_NH3   to NH3
    )

    /**
     * Maps whatever the feed called a pollutant onto a canonical id.
     *
     * data.gov.in is inconsistent about case and spacing between stations, and
     * ozone appears as both "OZONE" and "O3". An id that does not resolve comes
     * back null so the caller drops that row, rather than guessing which
     * breakpoint table might have been meant.
     */
    private fun canonicalId(pollutantId: String): String? =
        when (pollutantId.filterNot { it.isWhitespace() }.uppercase()) {
            "PM2.5", "PM25" -> ID_PM25
            "PM10"          -> ID_PM10
            "NO2"           -> ID_NO2
            "SO2"           -> ID_SO2
            "CO"            -> ID_CO
            "O3", "OZONE"   -> ID_OZONE
            "NH3"           -> ID_NH3
            else            -> null
        }

    /**
     * Sub-index for one pollutant, or null when it cannot be computed.
     *
     * Null covers an unrecognised pollutant and a negative or non-finite
     * concentration. It is deliberately not 0, because 0 on this scale reads as
     * clean air, which would turn a parse failure into a reassuring measurement
     * the station never reported.
     */
    fun subIndex(pollutantId: String, concentration: Float): Int? {
        if (concentration.isNaN() || concentration < 0f) return null
        val table = TABLES[canonicalId(pollutantId) ?: return null] ?: return null

        // The published bands leave a one-unit gap at every boundary (30 then
        // 31). Selecting on the upper bound alone closes it: a reading of 30.4
        // lands in the 31-60 band and interpolates to 50 instead of matching
        // nothing at all.
        val band = table.firstOrNull { concentration <= it.concHigh } ?: table.last()
        val span = band.concHigh - band.concLow
        if (span <= 0f) return null

        val raw = band.indexLow +
                  (band.indexHigh - band.indexLow) * (concentration - band.concLow) / span
        return raw.roundToInt().coerceIn(0, MAX_INDEX)
    }

    /** One pollutant row as the feed publishes it. [avg] is null when the station reported none. */
    data class PollutantReading(
        val pollutantId: String,
        val avg:         Float?
    )

    /** CPCB validity rule: an index needs at least this many pollutants... */
    const val MIN_POLLUTANTS = 3

    /**
     * The computed station index and the sub-indices behind it, keyed by
     * canonical id. [meetsCpcbMinimum] is CPCB's own validity rule: at least
     * [MIN_POLLUTANTS] pollutants, one of them PM2.5 or PM10.
     */
    data class AqiComputation(
        val aqi:               Int,
        val dominantPollutant: String,
        val subIndices:        Map<String, Int>,
        val meetsCpcbMinimum:  Boolean = meetsMinimum(subIndices.keys)
    )

    private fun meetsMinimum(ids: Set<String>): Boolean =
        ids.size >= MIN_POLLUTANTS && (ID_PM25 in ids || ID_PM10 in ids)

    /**
     * Station AQI from rows whose values are **already sub-indices** - the
     * data.gov.in real-time feed. The AQI is the largest sub-index; the
     * pollutant that set it is dominant. Returns null when no row is usable.
     *
     * A value outside 0..500 is not a sub-index at all and is dropped, never
     * clamped into range. Where a station repeats a pollutant the higher value
     * wins, which is the cautious reading for a health screen.
     */
    fun fromSubIndices(readings: List<PollutantReading>): AqiComputation? {
        val subIndices = LinkedHashMap<String, Int>()
        for (reading in readings) {
            val value = reading.avg ?: continue
            if (value.isNaN() || value < 0f || value > MAX_INDEX) continue
            val id = canonicalId(reading.pollutantId) ?: continue
            val index = value.roundToInt()
            val existing = subIndices[id]
            if (existing == null || index > existing) subIndices[id] = index
        }
        val dominant = subIndices.maxByOrNull { it.value } ?: return null
        return AqiComputation(dominant.value, dominant.key, subIndices)
    }

    /**
     * Station AQI from per-pollutant **concentrations** (CO in mg/m3, the rest
     * in ug/m3), or null when none were usable. Not for the data.gov.in feed,
     * whose values are already sub-indices - see [fromSubIndices].
     *
     * Rows with no average, an unknown pollutant or an uncomputable sub-index
     * are dropped. If that leaves nothing the result is null rather than a
     * default index - an empty or malformed response is an absence of data, and
     * reporting it as a number would be reporting air quality nobody measured.
     *
     * Where a station repeats a pollutant the higher sub-index wins, which is
     * the cautious reading for a health screen. A tie for dominance goes to the
     * pollutant seen first in the input.
     *
     * CPCB's minimum-spread rule is reported in [AqiComputation.meetsCpcbMinimum]
     * rather than enforced, so the caller decides what to do with a thin station.
     */
    fun compute(readings: List<PollutantReading>): AqiComputation? {
        val subIndices = LinkedHashMap<String, Int>()
        for (reading in readings) {
            val avg      = reading.avg ?: continue
            val id       = canonicalId(reading.pollutantId) ?: continue
            val value    = subIndex(id, avg) ?: continue
            val existing = subIndices[id]
            if (existing == null || value > existing) subIndices[id] = value
        }
        val dominant = subIndices.maxByOrNull { it.value } ?: return null
        return AqiComputation(
            aqi               = dominant.value,
            dominantPollutant = dominant.key,
            subIndices        = subIndices
        )
    }

    /** The band name for an index, so callers never restate the boundaries themselves. */
    fun bandLabel(aqi: Int): String = AqiRiskLevel.fromAqi(aqi).label
}
