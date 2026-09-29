package com.bipss.app.ui.metric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TimeTicksTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val end = ZonedDateTime.of(2026, 9, 30, 3, 28, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `a day is labelled every six hours on the clock, ending at Now`() {
        val ticks = timeTicks(TimeRange.DAY, end, zone).map { it.second }
        assertEquals(listOf("06:00", "12:00", "18:00", "00:00", "Now"), ticks)
    }

    @Test
    fun `a week is labelled by weekday, a month by date`() {
        val week = timeTicks(TimeRange.WEEK, end, zone).map { it.second }
        assertEquals(listOf("Thu", "Sat", "Mon", "Now"), week)
        val month = timeTicks(TimeRange.MONTH, end, zone).map { it.second }
        assertEquals("1 Sep", month.first())
        assertEquals("Now", month.last())
    }

    @Test
    fun `every tick lies inside the window, in order`() {
        TimeRange.entries.forEach { range ->
            val ticks = timeTicks(range, end, zone).map { it.first }
            assertTrue(ticks.all { it in (end - range.durationMs)..end })
            assertEquals(ticks.sorted(), ticks)
        }
    }
}
