package com.bipss.app.domain.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LocalDayTest {

    private val ist = ZoneId.of("Asia/Kolkata")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ist).toInstant().toEpochMilli()

    @Test
    fun `23-00 and 04-00 IST fall on different local days`() {
        // The UTC epoch-day bug put these on the same day (UTC day changes at 05:30 IST).
        assertNotEquals(LocalDay.epochDay(ms(2026, 9, 27, 23, 0), ist), LocalDay.epochDay(ms(2026, 9, 28, 4, 0), ist))
    }

    @Test
    fun `04-00 and 06-00 IST fall on the same local day`() {
        // ...and split these, which are the same local morning.
        assertEquals(LocalDay.epochDay(ms(2026, 9, 28, 4, 0), ist), LocalDay.epochDay(ms(2026, 9, 28, 6, 0), ist))
    }

    @Test
    fun `day range starts at local midnight and is 24h in IST`() {
        val range = LocalDay.dayRangeMs(ms(2026, 9, 28, 13, 45), ist)
        assertEquals(ms(2026, 9, 28, 0, 0), range.first)
        assertEquals(ms(2026, 9, 29, 0, 0), range.last + 1)
    }

    @Test
    fun `previous day range is yesterday's local midnight to today's`() {
        val range = LocalDay.previousDayRangeMs(ms(2026, 9, 28, 8, 0), ist)
        assertEquals(ms(2026, 9, 27, 0, 0), range.first)
        assertEquals(ms(2026, 9, 28, 0, 0), range.last + 1)
    }

    @Test
    fun `ms until next midnight`() {
        assertEquals(60_000L, LocalDay.msUntilNextMidnight(ms(2026, 9, 28, 23, 59), ist))
    }

    @Test
    fun `day length follows DST where the zone has it`() {
        val ny = ZoneId.of("America/New_York")
        val day = LocalDay.epochDay(LocalDateTime.of(2026, 3, 8, 12, 0).atZone(ny).toInstant().toEpochMilli(), ny)
        val length = LocalDay.startOfDayMs(day + 1, ny) - LocalDay.startOfDayMs(day, ny)
        assertEquals(23 * 3_600_000L, length)
    }
}
