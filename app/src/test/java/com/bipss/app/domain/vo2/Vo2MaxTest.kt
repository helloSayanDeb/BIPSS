package com.bipss.app.domain.vo2

import com.bipss.app.domain.bioage.BioSex
import org.junit.Assert.*
import org.junit.Test

class Vo2MaxTest {
    @Test fun jacksonMatchesTheHandCalculation() {
        // 56.363 + 1.921*3 - 0.381*30 - 0.754*24 + 10.987 = 43.587
        assertEquals(43.587, Vo2Max.jackson(30, 24.0, 3, BioSex.MALE), 1e-3)
        assertEquals(32.6, Vo2Max.jackson(30, 24.0, 3, BioSex.FEMALE), 0.1)
    }

    @Test fun ratingComesFromMeasuredMinutes() {
        assertEquals(0, Vo2Max.activityRating(0.0, 0.0, 1500.0))
        assertEquals(1, Vo2Max.activityRating(5.0, 0.0, 6000.0))
        assertEquals(2, Vo2Max.activityRating(30.0, 0.0, 6000.0))
        assertEquals(3, Vo2Max.activityRating(90.0, 0.0, 6000.0))
        assertEquals(4, Vo2Max.activityRating(90.0, 15.0, 6000.0))
        assertEquals(5, Vo2Max.activityRating(0.0, 45.0, 6000.0))
        assertEquals(6, Vo2Max.activityRating(0.0, 120.0, 6000.0))
        assertEquals(7, Vo2Max.activityRating(0.0, 240.0, 6000.0))
    }

    @Test fun uthIsTheHeartRateRatio() {
        assertEquals(48.45, Vo2Max.uth(190.0, 60.0), 1e-9)
    }

    @Test fun passiveBlendsOnlyUnderFiftyAndOnlyWithARestingRate() {
        val young = Vo2Max.passive(30, BioSex.MALE, 175.0, 75.0, 3, 186.8, 58.0)
        assertNotNull(young); assertTrue(young?.blended == true)
        val noRest = Vo2Max.passive(30, BioSex.MALE, 175.0, 75.0, 3, 186.8, null)
        assertTrue(noRest?.blended == false)
        assertEquals(Vo2Max.JACKSON_SEE, noRest?.sigma ?: 0.0, 1e-9)
        val older = Vo2Max.passive(60, BioSex.MALE, 175.0, 75.0, 3, 166.7, 58.0)
        assertTrue(older?.blended == false)
    }

    @Test fun passiveRefusesWhatTheEquationDoesNotCover() {
        assertNull(Vo2Max.passive(19, BioSex.MALE, 175.0, 75.0, 3, 190.0, 60.0))
        assertNull(Vo2Max.passive(30, BioSex.MALE, 175.0, 140.0, 3, 190.0, 60.0))
        assertNull(Vo2Max.passive(30, BioSex.MALE, 100.0, 75.0, 3, 190.0, 60.0))
    }

    @Test fun acsmOxygenCost() {
        assertEquals(12.5, Vo2Max.oxygenCost(1.5) ?: 0.0, 1e-9)    // 90 m/min walking
        assertEquals(39.5, Vo2Max.oxygenCost(3.0) ?: 0.0, 1e-9)    // 180 m/min running
        assertNull(Vo2Max.oxygenCost(1.8))                         // between walking and running
        assertNull(Vo2Max.oxygenCost(0.5))
    }

    @Test fun segmentScalesCostByReserveUsed() {
        // 1.4 m/s = 84 m/min: cost 11.9. At 50 % of reserve: 3.5 + 8.4 / 0.5 = 20.3.
        val s = Vo2Max.Segment(0, 10, 125.0, 1.4, false)
        assertEquals(20.3, Vo2Max.fromSegment(s, 60.0, 190.0) ?: 0.0, 1e-9)
    }

    @Test fun easyOrImplausibleSegmentsAreIgnored() {
        assertNull(Vo2Max.fromSegment(Vo2Max.Segment(0, 10, 85.0, 1.4, false), 60.0, 190.0))   // 19 % of reserve
        assertNull(Vo2Max.fromSegment(Vo2Max.Segment(0, 5, 125.0, 1.4, false), 60.0, 190.0))   // too short
        assertNull(Vo2Max.fromSegment(Vo2Max.Segment(0, 10, 125.0, 1.8, false), 60.0, 190.0))  // speed outside both equations
    }

    @Test fun combineWeightsTheMorePreciseEstimate() {
        val passive = Vo2Max.Passive(40.0, 5.6, false)
        val seg = Vo2Max.Segment(0, 10, 125.0, 1.4, true)
        val actives = Vo2Max.segmentEstimates(listOf(seg), 60.0, 190.0)
        assertEquals(1, actives.size)
        val one = Vo2Max.combine(passive, actives)
        assertNotNull(one)
        assertEquals(1, one?.activeSegments)
        assertTrue((one?.sigma ?: 99.0) < 5.6)
        assertNull(Vo2Max.combine(null, emptyList()))
        val noActive = Vo2Max.combine(passive, emptyList())
        assertEquals(40.0, noActive?.value ?: 0.0, 1e-9)
        assertNull(noActive?.active)
        val activeOnly = Vo2Max.combine(null, actives)
        assertEquals(actives[0].first, activeOnly?.value ?: 0.0, 1e-9)
    }

    @Test fun noRestingRateMeansNoActiveEstimates() {
        val seg = Vo2Max.Segment(0, 10, 125.0, 1.4, true)
        assertTrue(Vo2Max.segmentEstimates(listOf(seg), null, 190.0).isEmpty())
    }
}
