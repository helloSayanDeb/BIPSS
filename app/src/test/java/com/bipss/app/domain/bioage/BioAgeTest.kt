package com.bipss.app.domain.bioage

import org.junit.Assert.*
import org.junit.Test

class BioAgeTest {
    private val end = 1000L

    /** 60 worn days, 7.5 h sleep at steady times, rhr 60, 8 000 steps, zone minutes at the neutral points. */
    private fun day(i: Int, steps: Int = 8000, sleep: Int = 450, bed: Int = 23 * 60, rhr: Double = 60.0,
                    z13: Int = 15, z45: Int = 2, strength: Int = 0) = DayStats(
        epochDay = end - i, validMinutes = 900, sleepMinutes = sleep, sleepStartMin = bed, sleepEndMin = (bed + sleep) % 1440,
        restingHr = rhr, steps = steps, zoneMin1 = z13, zoneMin45 = z45, strengthMin = strength
    )

    private fun input(days: List<DayStats>, vo2: Double? = null, lean: Double? = null, age: Int = 30, sex: BioSex = BioSex.MALE) =
        BioAgeInput(age, sex, days, end, vo2, lean)

    @Test fun sleepDurationNeutralAndShort() {
        assertEquals(0.0, BioAge.sleepDurationYears(10.0), 1e-9)
        assertTrue(BioAge.sleepDurationYears(7.5) < 0.0)
        assertTrue(BioAge.sleepDurationYears(5.0) > BioAge.sleepDurationYears(6.0))
        assertTrue(BioAge.sleepDurationYears(6.0) > 0.0)
    }

    @Test fun stepsBelowNeutralAddYearsAndAreCapped() {
        assertEquals(0.0, BioAge.stepsYears(8000.0, 30), 1e-9)
        assertTrue(BioAge.stepsYears(5000.0, 30) > BioAge.stepsYears(7000.0, 30))
        assertTrue(BioAge.stepsYears(10000.0, 30) < 0.0)
        assertTrue(BioAge.stepsYears(0.0, 30) <= BioAge.CONTRIBUTOR_CAP_YEARS)
        // Fewer steps are needed from sixty.
        assertTrue(BioAge.stepsYears(6000.0, 65) < BioAge.stepsYears(6000.0, 30))
    }

    @Test fun restingHrIsNeutralAtTheSexTarget() {
        assertEquals(0.0, BioAge.restingHrYears(60.0, BioSex.MALE), 1e-9)
        assertEquals(0.0, BioAge.restingHrYears(64.0, BioSex.FEMALE), 1e-9)
        assertTrue(BioAge.restingHrYears(75.0, BioSex.MALE) > 0.0)
        assertTrue(BioAge.restingHrYears(50.0, BioSex.MALE) < 0.0)
    }

    @Test fun zonesAndStrengthNeutralPoints() {
        assertEquals(0.0, BioAge.zone13Years(100.0, 30), 1e-9)
        assertEquals(0.0, BioAge.zone45Years(10.0), 1e-9)
        assertEquals(0.0, BioAge.strengthYears(40.0), 1e-9)
        assertTrue(BioAge.zone13Years(0.0, 30) > 0.0 && BioAge.zone13Years(300.0, 30) < 0.0)
        assertTrue(BioAge.zone45Years(0.0) > 0.0 && BioAge.zone45Years(20.0) < 0.0)
        // More than two hours of strength adds no further benefit.
        assertEquals(BioAge.strengthYears(120.0), BioAge.strengthYears(300.0), 1e-9)
    }

    @Test fun vo2TargetFollowsTheWhoopTable() {
        assertEquals(46.0, BioAge.vo2Target(20, BioSex.MALE), 1e-9)
        assertEquals(44.0, BioAge.vo2Target(30, BioSex.MALE), 1e-9)
        assertEquals(26.0, BioAge.vo2Target(65, BioSex.FEMALE), 1e-9)
        assertEquals(40.8, BioAge.vo2Target(38, BioSex.MALE), 1e-9)
        assertEquals(41.0, BioAge.vo2Target(30, BioSex.OTHER), 1e-9)
        assertTrue(BioAge.vo2Years(30.0, 30, BioSex.MALE) > 0.0)
        assertTrue(BioAge.vo2Years(55.0, 30, BioSex.MALE) < 0.0)
        assertTrue(BioAge.vo2Years(5.0, 30, BioSex.MALE) <= BioAge.VO2_CAP_YEARS)
    }

    @Test fun leanMassOnlyHurtsBelowTheThreshold() {
        assertEquals(80.0, BioAge.leanMassThreshold(30, BioSex.MALE), 1e-9)
        assertEquals(67.0, BioAge.leanMassThreshold(30, BioSex.FEMALE), 1e-9)
        assertTrue(BioAge.leanMassYears(70.0, 30, BioSex.MALE) > 0.0)
        assertTrue(BioAge.leanMassYears(90.0, 30, BioSex.MALE) in -1.0..0.0)
    }

    @Test fun identicalBedtimesGiveFullConsistency() {
        val nights = (0 until 12).map { day(it) }
        assertEquals(100.0, BioAge.sleepConsistency(nights) ?: -1.0, 1e-6)
    }

    @Test fun erraticBedtimesLowerConsistency() {
        val nights = (0 until 12).map { day(it, bed = if (it % 2 == 0) 21 * 60 else 2 * 60) }
        assertTrue((BioAge.sleepConsistency(nights) ?: 100.0) < 20.0)
    }

    @Test fun bedtimesAcrossMidnightAreCloseNotFar() {
        val nights = (0 until 12).map { day(it, bed = if (it % 2 == 0) 23 * 60 + 50 else 10) }
        assertTrue((BioAge.sleepConsistency(nights) ?: 0.0) > 85.0)
    }

    @Test fun fewDaysMeansCalibrating() {
        val r = BioAge.compute(input((0 until 10).map { day(it) }))
        assertEquals(BioAgeStage.CALIBRATING, r.stage)
        assertNull(r.bioAge)
    }

    @Test fun stagesFollowTheDaysOfWear() {
        assertEquals(BioAgeStage.PROVISIONAL, BioAge.compute(input((0 until 20).map { day(it) })).stage)
        assertEquals(BioAgeStage.ESTABLISHED, BioAge.compute(input((0 until 60).map { day(it) })).stage)
    }

    @Test fun healthyHabitsAreYoungerThanCalendar() {
        val r = BioAge.compute(input((0 until 60).map { day(it, steps = 11000, z13 = 30, z45 = 5, rhr = 52.0) }, vo2 = 52.0))
        assertTrue((r.yearsDelta ?: 0.0) < -1.0)
        assertTrue((r.bioAge ?: 99.0) < 30.0)
    }

    @Test fun poorHabitsAreOlderThanCalendar() {
        val r = BioAge.compute(input((0 until 60).map { day(it, steps = 3000, sleep = 330, z13 = 0, z45 = 0, rhr = 78.0) }, vo2 = 28.0))
        assertTrue((r.yearsDelta ?: 0.0) > 3.0)
    }

    @Test fun strengthCountsOnlyForSomeoneWhoLogsIt() {
        val without = BioAge.compute(input((0 until 60).map { day(it) }))
        assertEquals(Skipped.NOT_ENTERED, without.contributors.first { it.contributor == Contributor.STRENGTH }.skipped)
        val with = BioAge.compute(input((0 until 60).map { day(it, strength = if (it % 3 == 0) 40 else 0) }))
        assertNotNull(with.contributors.first { it.contributor == Contributor.STRENGTH }.years)
    }

    @Test fun leanMassAndVo2AreSkippedWhenMissing() {
        val r = BioAge.compute(input((0 until 60).map { day(it) }))
        assertEquals(Skipped.NOT_ENTERED, r.contributors.first { it.contributor == Contributor.LEAN_MASS }.skipped)
        assertEquals(Skipped.NO_DATA, r.contributors.first { it.contributor == Contributor.VO2_MAX }.skipped)
    }

    @Test fun lowWearDaysDoNotCountForStepsOrZones() {
        val days = (0 until 60).map { day(it).copy(validMinutes = 200) }
        val r = BioAge.compute(input(days))
        assertEquals(BioAgeStage.CALIBRATING, r.stage)
    }

    @Test fun paceIsSteadyWhenHabitsDoNotChange() {
        val days = (0 until 120).map { day(it, steps = 6000) }
        assertEquals(1.0, BioAge.pace(input(days))?.pace ?: -9.0, 0.05)
        assertEquals(PaceLabel.STEADY, BioAge.pace(input(days))?.label)
    }

    @Test fun paceFallsWhenTheLastMonthImproved() {
        val days = (0 until 120).map { day(it, steps = if (it < 30) 11000 else 4000) }
        val p = BioAge.pace(input(days))
        assertNotNull(p)
        assertTrue((p?.pace ?: 9.0) < 1.0)
        val worse = (0 until 120).map { day(it, steps = if (it < 30) 3000 else 9000) }
        assertTrue((BioAge.pace(input(worse))?.pace ?: 0.0) > 1.0)
        assertEquals(PaceLabel.ACCELERATED, BioAge.pace(input(worse))?.label)
    }

    @Test fun paceNeedsAMonthOfData() {
        assertNull(BioAge.pace(input((0 until 20).map { day(it) })))
    }
}
