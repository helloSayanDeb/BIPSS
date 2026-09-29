package com.bipss.app.domain.cycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CyclePredictorTest {

    private fun p(start: Long, end: Long? = start + 4, id: Long = start) = Period(id, start, end)

    @Test
    fun `a typical 28-day cycle until two periods are logged`() {
        val s = CyclePredictor.stats(listOf(p(100)))
        assertTrue(s.usedDefault)
        assertEquals(28, s.cycleLengthDays)
        assertEquals(5, s.periodLengthDays)
        assertTrue(CyclePredictor.stats(emptyList()).usedDefault)
    }

    @Test
    fun `cycle length averages the gaps between logged starts`() {
        val s = CyclePredictor.stats(listOf(p(100), p(130), p(160), p(189)))   // 30, 30, 29
        assertFalse(s.usedDefault)
        assertEquals(30, s.cycleLengthDays)   // 29.67 rounds to 30
        assertEquals(3, s.cyclesUsed)
    }

    @Test
    fun `implausible gaps are left out and reported - usually a missed log`() {
        val s = CyclePredictor.stats(listOf(p(100), p(128), p(184), p(212)))   // 28, 56 ok, 28
        assertEquals(listOf<Int>(), s.excludedGaps)
        val missed = CyclePredictor.stats(listOf(p(100), p(128), p(200), p(228)))   // 28, 72 (missed), 28
        assertEquals(listOf(72), missed.excludedGaps)
        assertEquals(28, missed.cycleLengthDays)
        assertEquals(2, missed.cyclesUsed)
    }

    @Test
    fun `only the last six cycles count`() {
        // Six recent 30-day cycles after one old 40-day gap.
        val starts = listOf(0L, 40L) + (1..6).map { 40L + it * 30 }
        val s = CyclePredictor.stats(starts.map { p(it) })
        assertEquals(30, s.cycleLengthDays)
        assertEquals(6, s.cyclesUsed)
    }

    @Test
    fun `period length averages logged periods with an end`() {
        val s = CyclePredictor.stats(listOf(p(100, 103), p(128, 133), p(156, null)))   // 4, 6; ongoing ignored
        assertEquals(5, s.periodLengthDays)
    }

    @Test
    fun `today's cycle day and phase`() {
        val periods = listOf(p(100, 104), p(128, 132))   // 28-day cycle, 5-day periods
        fun at(day: Long) = CyclePredictor.today(periods, day)!!
        assertEquals(1, at(128).cycleDay)
        assertEquals(CyclePhase.MENSTRUAL, at(130).phase)
        assertEquals(CyclePhase.FOLLICULAR, at(135).phase)
        // Next start 156, ovulation 142, fertile 137..143.
        assertEquals(156L, at(135).nextStartDay)
        assertEquals(137L, at(135).fertileStartDay)
        assertEquals(143L, at(135).fertileEndDay)
        assertEquals(CyclePhase.FERTILE, at(140).phase)
        assertEquals(CyclePhase.LUTEAL, at(150).phase)
        assertEquals(6, at(150).daysUntilNext)
        assertTrue(at(159).isLate)
        assertNull(CyclePredictor.today(emptyList(), 150))
    }

    @Test
    fun `an ongoing period counts as menstrual for its usual length`() {
        val periods = listOf(p(100, 104), p(128, null))
        assertEquals(CyclePhase.MENSTRUAL, CyclePredictor.today(periods, 131)!!.phase)
        assertEquals(CyclePhase.FOLLICULAR, CyclePredictor.today(periods, 134)!!.phase)
    }

    @Test
    fun `calendar days - logged, predicted from today on, fertile`() {
        val periods = listOf(p(100, 104), p(128, 132))
        val kinds = CyclePredictor.dayKinds(periods, fromDay = 95, toDay = 190, todayDay = 135)
        assertEquals(DayKind.LOGGED_PERIOD, kinds[100])
        assertEquals(DayKind.LOGGED_PERIOD, kinds[132])
        assertEquals(DayKind.FERTILE, kinds[140])
        assertEquals(DayKind.PREDICTED_PERIOD, kinds[156])
        assertEquals(DayKind.PREDICTED_PERIOD, kinds[160])
        assertNull(kinds[161])
        assertEquals(DayKind.PREDICTED_PERIOD, kinds[184])   // the cycle after
        // Before today nothing is predicted: the past is what was logged.
        val later = CyclePredictor.dayKinds(periods, 95, 190, todayDay = 170)
        assertEquals(DayKind.PREDICTED_PERIOD, later[184])
        assertNull(later[156])
    }

    @Test
    fun `logging is checked against what makes sense`() {
        val periods = listOf(p(100, 104), p(128, null))
        assertNotNull(CyclePredictor.startConflict(periods, 200, todayDay = 150))   // future
        assertNotNull(CyclePredictor.startConflict(periods, 102, todayDay = 150))   // inside a period
        assertNotNull(CyclePredictor.startConflict(periods, 135, todayDay = 150))   // too close to another start
        assertNull(CyclePredictor.startConflict(periods, 150, todayDay = 150))
        assertEquals(128L, CyclePredictor.periodToEnd(periods, 132)!!.startDay)
        assertNull(CyclePredictor.endConflict(periods, 132, todayDay = 150))
        assertNotNull(CyclePredictor.endConflict(periods, 90, todayDay = 150))      // no period to end
    }
}
