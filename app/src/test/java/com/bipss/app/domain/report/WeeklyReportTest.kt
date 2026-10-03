package com.bipss.app.domain.report

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WeeklyReportTest {
    private val monday = LocalDate.of(2026, 9, 28).toEpochDay()

    private fun day(offset: Int, sleep: Int? = null, rec: Int? = null, steps: Int? = null, strain: Double? = null,
                    active: Int = 0, workouts: Int = 0, water: Int = 0, rhr: Double? = null) =
        WeekDay(monday + offset, sleep, null, rec, strain, steps, rhr, active, workouts, water)

    @Test fun weekStartIsMonday() {
        assertEquals(monday, WeeklyReport.weekStart(monday))
        assertEquals(monday, WeeklyReport.weekStart(monday + 6))
        assertEquals(monday + 7, WeeklyReport.weekStart(monday + 7))
    }

    @Test fun averagesAndTotalsAreOverTheSevenDays() {
        val days = listOf(day(0, 420, 60, 8000, 8.0, 30, 1), day(1, 480, 80, 10000, 12.0, 20, 0), day(9, 300, 20, 2000))
        val s = WeeklyReport.summarize(days, monday)
        assertEquals(450.0, s.sleepMin.value ?: 0.0, 1e-9)
        assertEquals(70.0, s.recovery.value ?: 0.0, 1e-9)
        assertEquals(9000.0, s.steps.value ?: 0.0, 1e-9)
        assertEquals(50.0, s.activeMin.value ?: 0.0, 1e-9)
        assertEquals(1.0, s.workouts.value ?: 0.0, 1e-9)
        assertEquals(2, s.nights)
        assertEquals(2, s.daysWithData)
    }

    @Test fun changeIsAgainstThePreviousWeek() {
        val days = listOf(day(-7, steps = 6000), day(-6, steps = 8000), day(0, steps = 9000), day(1, steps = 11000))
        val s = WeeklyReport.summarize(days, monday)
        assertEquals(10000.0, s.steps.value ?: 0.0, 1e-9)
        assertEquals(7000.0, s.steps.previous ?: 0.0, 1e-9)
        assertEquals(3000.0, s.steps.change ?: 0.0, 1e-9)
    }

    @Test fun anEmptyWeekHasNothingToAverage() {
        val s = WeeklyReport.summarize(emptyList(), monday)
        assertNull(s.sleepMin.value); assertNull(s.activeMin.value)
        assertEquals(0, s.daysWithData)
        assertTrue(s.highlights.isEmpty())
    }

    @Test fun highlightsPickTheBestDays() {
        val days = listOf(day(0, 400, 55, 5000, 6.0), day(2, 520, 88, 12000, 15.5), day(4, 460, 70, 9000, 9.0))
        val h = WeeklyReport.summarize(days, monday).highlights.associateBy { it.kind }
        assertEquals(monday + 2, h[HighlightKind.BEST_RECOVERY]?.epochDay)
        assertEquals(88.0, h[HighlightKind.BEST_RECOVERY]?.value ?: 0.0, 1e-9)
        assertEquals(monday + 2, h[HighlightKind.LONGEST_SLEEP]?.epochDay)
        assertEquals(monday + 2, h[HighlightKind.MOST_STEPS]?.epochDay)
        assertEquals(15.5, h[HighlightKind.HARDEST_DAY]?.value ?: 0.0, 1e-9)
    }

    @Test fun waterOnlyAveragesDaysThatLoggedAny() {
        val days = listOf(day(0, water = 2000), day(1, water = 0), day(2, water = 1000))
        assertEquals(1500.0, WeeklyReport.summarize(days, monday).waterMl.value ?: 0.0, 1e-9)
    }
}
