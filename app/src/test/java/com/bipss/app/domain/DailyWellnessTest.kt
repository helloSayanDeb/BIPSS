package com.bipss.app.domain

import org.junit.Assert.*
import org.junit.Test

class DailyWellnessTest {
    @Test fun `counter starts without importing steps before permission and handles reboot and midnight`() {
        assertEquals(0, DailyWellness.stepDelta(null, 4000, true, true))
        assertEquals(125, DailyWellness.stepDelta(4000, 4125, true, true))
        assertEquals(0, DailyWellness.stepDelta(4125, 20, true, false))
        assertEquals(0, DailyWellness.stepDelta(4125, 5000, false, true))
        assertEquals(0, DailyWellness.stepDelta(4125, 0, true, true))
    }
    @Test fun `BMI needs plausible adult measurements and uses kilograms over squared metres`() {
        assertEquals(25f, DailyWellness.bmi(180f, 81f, 30)!!, 0.001f)
        assertNull(DailyWellness.bmi(180f, 81f, 19))
        assertNull(DailyWellness.bmi(0f, 81f, 30))
        assertNull(DailyWellness.bmi(Float.NaN, 81f, 30))
    }
    @Test fun `water starting goal is bounded and does not prescribe goals outside supported range`() {
        assertEquals(1500, DailyWellness.waterStartingGoal(40f, 30))
        assertEquals(2000, DailyWellness.waterStartingGoal(80f, 30))
        assertEquals(3000, DailyWellness.waterStartingGoal(150f, 30))
        assertNull(DailyWellness.waterStartingGoal(80f, 15))
        assertNull(DailyWellness.waterStartingGoal(80f, 75))
    }
    @Test fun `daily windows handle a night shift and missing environmental readings`() {
        val night = DailyWindows.build(60, 9 * 60, 17 * 60, null, null, null, 250)
        assertEquals("Earlier is better", night.first { it.id == "caffeine" }.state)
        assertEquals("Check outside", night.first { it.id == "sun" }.state)
        val morning = DailyWindows.build(8 * 60 + 30, 9 * 60, 17 * 60, 5f, 250, 30, 0)
        assertEquals("Skip for now", morning.first { it.id == "caffeine" }.state)
        assertEquals("Wind down", morning.first { it.id == "screens" }.state)
        assertEquals("Choose indoors", morning.first { it.id == "move" }.state)
        assertEquals("Use protection", morning.first { it.id == "sun" }.state)
    }
}
