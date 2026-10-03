package com.bipss.app.domain.sleep

import org.junit.Assert.*
import org.junit.Test

class SleepPlannerTest {
    private fun nights(vararg minutes: Int) = minutes.mapIndexed { i, m -> PlannerNight(100L + i, m) }

    @Test fun fewNightsUseTheDefaultEightHours() {
        val (base, isDefault) = SleepPlanner.baseline(nights(400, 420))
        assertEquals(480, base); assertTrue(isDefault)
    }

    @Test fun baselineIsHeldBetweenSevenAndNineHours() {
        assertEquals(420, SleepPlanner.baseline(nights(*IntArray(10) { 300 })).first)
        assertEquals(540, SleepPlanner.baseline(nights(*IntArray(10) { 600 })).first)
    }

    @Test fun baselineIsAHighPercentileNotTheAverage() {
        val b = SleepPlanner.baseline(nights(360, 360, 360, 360, 480, 480, 480, 480)).first
        assertEquals(480, b)
    }

    @Test fun strainOnlyAddsFromTen() {
        assertEquals(0, SleepPlanner.strainExtra(null))
        assertEquals(0, SleepPlanner.strainExtra(10.0))
        assertEquals(45, SleepPlanner.strainExtra(18.0))
        assertEquals(45, SleepPlanner.strainExtra(21.0))
        assertTrue(SleepPlanner.strainExtra(14.0) in 20..25)
    }

    @Test fun debtAddsAQuarterUpToAnHour() {
        // Eight rested nights at 8 h set the baseline, then a week of 5 h nights.
        val list = nights(*IntArray(7) { 480 }) + (0 until 7).map { PlannerNight(200L + it, 300) }
        val plan = SleepPlanner.plan(list, null, 7 * 60)
        assertTrue(plan.debtMin > 0)
        assertEquals(SleepPlanner.MAX_DEBT_EXTRA_MIN, plan.debtExtraMin)
        assertEquals(plan.baselineNeedMin + plan.debtExtraMin, plan.needMin)
    }

    @Test fun bedtimeIsWakeMinusNeedMinusLatency() {
        val plan = SleepPlanner.plan(emptyList(), null, 7 * 60)
        assertEquals(480, plan.needMin)
        // 07:00 - 8 h - 15 min = 22:45
        assertEquals(22 * 60 + 45, plan.bedtimeMin)
        assertTrue(plan.baselineIsDefault)
    }

    @Test fun bedtimeWrapsPastMidnight() {
        val plan = SleepPlanner.plan(emptyList(), null, 5 * 60)
        assertEquals(20 * 60 + 45, plan.bedtimeMin)
        val late = SleepPlanner.plan(emptyList(), null, 9 * 60)
        assertEquals(0 * 60 + 45, late.bedtimeMin)
    }
}
