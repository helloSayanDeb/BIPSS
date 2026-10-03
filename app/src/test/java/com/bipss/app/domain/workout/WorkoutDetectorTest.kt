package com.bipss.app.domain.workout

import com.bipss.app.domain.strain.TrimpSex
import org.junit.Assert.*
import org.junit.Test

class WorkoutDetectorTest {
    private val t0 = 1_700_000_000_000L / 60_000L * 60_000L
    private fun minute(i: Int, hr: Double?, act: Double?, steps: Int? = null, gps: Double? = null) =
        WorkMinute(t0 + i * 60_000L, hr, act, steps, gps)

    private fun block(from: Int, count: Int, hr: Double, act: Double = 6000.0, steps: Int? = 160) =
        (from until from + count).map { minute(it, hr, act, steps) }

    @Test fun aSustainedRunIsOneEffortAndLooksLikeARun() {
        val e = WorkoutDetector.detect(block(0, 30, 150.0), 60.0, 190.0, TrimpSex.MALE)
        assertEquals(1, e.size)
        assertEquals("RUN", e[0].guessedType)
        assertEquals(30, e[0].minutes)
        assertTrue(e[0].trimp > 0.0)
        assertEquals(150.0, e[0].meanHr, 1e-9)
    }

    @Test fun steadyWalkingAtMidCadenceIsAWalk() {
        val e = WorkoutDetector.detect(block(0, 20, 125.0, 4000.0, 105), 60.0, 190.0, TrimpSex.FEMALE)
        assertEquals("WALK", e.single().guessedType)
    }

    @Test fun effortWithoutStepsIsAWorkout() {
        val e = WorkoutDetector.detect(block(0, 20, 140.0, 5000.0, null), 60.0, 190.0, TrimpSex.MALE)
        assertEquals("WORKOUT", e.single().guessedType)
    }

    @Test fun shortBurstsAreNotWorkouts() {
        assertTrue(WorkoutDetector.detect(block(0, 6, 160.0), 60.0, 190.0, TrimpSex.MALE).isEmpty())
    }

    @Test fun raisedHeartRateWhileStillIsNotAWorkout() {
        assertTrue(WorkoutDetector.detect(block(0, 30, 130.0, 100.0), 60.0, 190.0, TrimpSex.MALE).isEmpty())
    }

    @Test fun easyMovementBelowAThirdOfReserveIsNotAWorkout() {
        assertTrue(WorkoutDetector.detect(block(0, 30, 90.0), 60.0, 190.0, TrimpSex.MALE).isEmpty())
    }

    @Test fun aShortPauseDoesNotSplitAnEffort() {
        val minutes = block(0, 10, 150.0) + block(10, 2, 80.0, 200.0) + block(12, 10, 150.0)
        val e = WorkoutDetector.detect(minutes, 60.0, 190.0, TrimpSex.MALE)
        assertEquals(1, e.size)
        assertEquals(22, e[0].minutes)
    }

    @Test fun aLongPauseDoes() {
        val minutes = block(0, 12, 150.0) + block(12, 8, 70.0, 100.0) + block(20, 12, 150.0)
        assertEquals(2, WorkoutDetector.detect(minutes, 60.0, 190.0, TrimpSex.MALE).size)
    }

    @Test fun steadyCadenceWalkGivesASegment() {
        val minutes = (0 until 10).map { minute(it, 125.0 + (it % 2), 4000.0, 108) }
        val segs = WorkoutDetector.steadySegments(minutes, 172.0)
        assertEquals(1, segs.size)
        assertFalse(segs[0].fromGps)
        // 108 spm x (0.414 x 1.72 m) = 1.28 m/s
        assertEquals(1.28, segs[0].speedMps, 0.01)
    }

    @Test fun driftingHeartRateIsNotSteady() {
        val minutes = (0 until 10).map { minute(it, 110.0 + it * 4.0, 4000.0, 108) }
        assertTrue(WorkoutDetector.steadySegments(minutes, 172.0).isEmpty())
    }

    @Test fun variableCadenceIsNotSteady() {
        val minutes = (0 until 10).map { minute(it, 125.0, 4000.0, if (it % 2 == 0) 90 else 130) }
        assertTrue(WorkoutDetector.steadySegments(minutes, 172.0).isEmpty())
    }

    @Test fun runningCadenceNeedsGpsForSpeed() {
        val noGps = (0 until 10).map { minute(it, 160.0, 7000.0, 165) }
        assertTrue(WorkoutDetector.steadySegments(noGps, 172.0).isEmpty())
        val gps = (0 until 10).map { minute(it, 160.0, 7000.0, 165, 3.0) }
        val seg = WorkoutDetector.steadySegments(gps, 172.0).single()
        assertTrue(seg.fromGps)
        assertEquals(3.0, seg.speedMps, 1e-9)
    }

    @Test fun missingHeartRateBreaksASegment() {
        val minutes = (0 until 10).map { minute(it, if (it == 4) null else 125.0, 4000.0, 108) }
        assertTrue(WorkoutDetector.steadySegments(minutes, 172.0).isEmpty())
    }
}
