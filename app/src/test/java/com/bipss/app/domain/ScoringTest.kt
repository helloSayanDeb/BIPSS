package com.bipss.app.domain

import com.bipss.app.domain.ali.AliLevel
import com.bipss.app.domain.ali.AutonomicLoad
import com.bipss.app.domain.ali.StillEpoch
import com.bipss.app.domain.baseline.Ewma
import com.bipss.app.domain.baseline.EwmaState
import com.bipss.app.domain.baseline.normalCdf
import com.bipss.app.domain.recovery.NightInputs
import com.bipss.app.domain.recovery.RecoveryEngine
import com.bipss.app.domain.recovery.RecoveryInput
import com.bipss.app.domain.strain.StrainMinute
import com.bipss.app.domain.strain.StrainScale
import com.bipss.app.domain.strain.Trimp
import com.bipss.app.domain.strain.TrimpSex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.exp

class ScoringTest {

    // -- Baseline schedule ------------------------------------------------------

    @Test
    fun `baseline stages and confidence follow the milestones`() {
        assertEquals(BaselineStage.CALIBRATING, BaselineSchedule.stage(13))
        assertEquals(BaselineStage.PROVISIONAL, BaselineSchedule.stage(14))
        assertEquals(BaselineStage.ESTABLISHED, BaselineSchedule.stage(30))
        assertEquals(0f, BaselineSchedule.confidenceFor(13), 0f)
        assertEquals(0.5f, BaselineSchedule.confidenceFor(14), 1e-6f)
        assertEquals(1.0f, BaselineSchedule.confidenceFor(90), 1e-6f)
        assertTrue(BaselineSchedule.confidenceFor(45) > BaselineSchedule.confidenceFor(30))
        assertEquals(45, BaselineSchedule.nextMilestone(30))
        assertNull(BaselineSchedule.nextMilestone(90))
        assertEquals("Still calibrating · Day 5 of 14", BaselineSchedule.stageLabel(5))
    }

    // -- EWMA and the normal CDF --------------------------------------------------

    @Test
    fun `ewma converges to a new level and reports no z while young`() {
        var s: EwmaState? = null
        repeat(2) { s = Ewma.update(s, 50.0) }
        assertNull(Ewma.z(s, 60.0, 5.0))
        repeat(100) { s = Ewma.update(s, 40.0) }
        assertEquals(40.0, s!!.mean, 0.01)
        assertEquals(-4.0, Ewma.z(s, 20.0, 5.0)!!, 0.01)   // sd floored at 5
    }

    @Test
    fun `normal cdf matches known values`() {
        assertEquals(0.5, normalCdf(0.0), 1e-7)
        assertEquals(0.841344746, normalCdf(1.0), 1e-6)
        assertEquals(0.022750132, normalCdf(-2.0), 1e-6)
    }

    // -- TRIMP ---------------------------------------------------------------------

    private fun minutes(start: Long, n: Int, hr: Double?, activity: Double) =
        (0 until n).map { StrainMinute(start + it * 60_000L, hr, activity) }

    @Test
    fun `trimp of one steady bout matches Banister's formula`() {
        // 30 min at HRr 0.6 (HR 138 with rest 60 / max 190), male coefficients.
        val r = Trimp.compute(minutes(0, 30, 138.0, 9_000.0), 60.0, 190.0, TrimpSex.MALE)
        val expected = 30 * 0.6 * 0.64 * exp(1.92 * 0.6)
        assertEquals(expected, r.trimp, 1e-6)
        assertEquals(1, r.bouts.size)
        assertEquals(30, r.activeMinutes)
    }

    @Test
    fun `raised heart rate without movement is not strain`() {
        val r = Trimp.compute(minutes(0, 60, 120.0, 200.0), 60.0, 190.0, TrimpSex.FEMALE)
        assertEquals(0.0, r.trimp, 0.0)
        assertEquals(0, r.activeMinutes)
    }

    @Test
    fun `separate bouts are scored separately and a gap splits them`() {
        val a = minutes(0, 10, 150.0, 9_000.0)
        val b = minutes(20 * 60_000L, 10, 110.0, 9_000.0)
        val r = Trimp.compute(a + b, 60.0, 190.0, TrimpSex.UNSPECIFIED)
        assertEquals(2, r.bouts.size)
        // Per-bout scoring: a hard and an easy bout are not averaged into one medium one.
        val merged = Trimp.compute(
            (a + b).mapIndexed { i, m -> m.copy(minuteStartMs = i * 60_000L) }, 60.0, 190.0, TrimpSex.UNSPECIFIED
        )
        assertTrue(r.trimp > merged.trimp)
    }

    @Test
    fun `active minutes without heart rate are counted but not scored`() {
        val r = Trimp.compute(minutes(0, 10, null, 9_000.0) + minutes(600_000L, 10, 150.0, 9_000.0), 60.0, 190.0, TrimpSex.MALE)
        assertEquals(20, r.activeMinutes)
        assertEquals(10, r.scoredMinutes)
    }

    @Test
    fun `strain scale is logarithmic, bounded and pinned at its reference point`() {
        assertEquals(0.0, StrainScale.toDisplay(0.0), 0.0)
        assertEquals(21.0, StrainScale.toDisplay(400.0), 1e-9)
        assertEquals(21.0, StrainScale.toDisplay(5_000.0), 1e-9)
        assertTrue(StrainScale.toDisplay(50.0) > 21.0 * 50 / 400)   // everyday load is not squeezed to zero
        assertEquals(186.8, Trimp.gellishHrMax(30), 1e-9)   // 206.9 - 0.67 * 30
    }

    // -- Recovery ------------------------------------------------------------------

    private fun baselines(rng: Random, nights: Int): Map<RecoveryInput, EwmaState> {
        var b = emptyMap<RecoveryInput, EwmaState>()
        repeat(nights) {
            b = RecoveryEngine.updatedBaselines(
                NightInputs(0, 50 + rng.nextGaussian() * 6, 55 + rng.nextGaussian() * 2,
                            450 + rng.nextGaussian() * 30, 33.5 + rng.nextGaussian() * 0.2), b
            )
        }
        return b
    }

    @Test
    fun `recovery is withheld while calibrating`() {
        val r = RecoveryEngine.evaluate(NightInputs(0, 50.0, 55.0, 450.0, 33.5), baselines(Random(1), 10), dayN = 10)
        assertNull(r.score)
        assertEquals("Still calibrating · Day 10 of 14", r.reason)
        // Values are reported, but nothing is compared with the baseline yet.
        assertTrue(r.components.isNotEmpty())
        assertTrue(r.components.all { it.z == null && it.signedZ == null })
    }

    @Test
    fun `an ordinary night scores near 50 and better signals score higher`() {
        val b = baselines(Random(2), 30)
        val ordinary = RecoveryEngine.evaluate(NightInputs(0, b.getValue(RecoveryInput.PRV).mean,
            b.getValue(RecoveryInput.RESTING_HR).mean, b.getValue(RecoveryInput.SLEEP_DURATION).mean,
            b.getValue(RecoveryInput.SKIN_TEMP).mean + 0.2 * 0.8), b, dayN = 40)
        assertEquals(50.0, ordinary.score!!.toDouble(), 6.0)
        val good = RecoveryEngine.evaluate(NightInputs(0, 65.0, 51.0, 480.0, 33.5), b, 40)
        val poor = RecoveryEngine.evaluate(NightInputs(0, 35.0, 60.0, 360.0, 34.3), b, 40)
        assertTrue("good ${good.score}", good.score!! > 80)
        assertTrue("poor ${poor.score}", poor.score!! < 20)
    }

    @Test
    fun `recovery needs pulse inputs, not sleep length alone`() {
        val b = baselines(Random(3), 20)
        val r = RecoveryEngine.evaluate(NightInputs(0, null, null, 480.0, 33.5), b, 20)
        assertNull(r.score)
        assertNotNull(r.reason)
    }

    // -- Autonomic Load Indicator ------------------------------------------------------

    private fun epochs(n: Int, hr: Double, prv: Double, rng: Random, start: Long = 0) =
        (0 until n).map { StillEpoch(start + it * 30_000L, hr + rng.nextGaussian() * 2, prv + rng.nextGaussian() * 5) }

    @Test
    fun `ALI is low at the usual level and high when PRV falls and still HR rises`() {
        val rng = Random(4)
        val ref = epochs(600, 62.0, 45.0, rng)
        assertEquals(AliLevel.LOW, AutonomicLoad.evaluate(epochs(20, 62.0, 45.0, rng), ref, 30).level)
        assertEquals(AliLevel.HIGH, AutonomicLoad.evaluate(epochs(20, 74.0, 28.0, rng), ref, 30).level)
    }

    @Test
    fun `ALI says nothing while calibrating or without still signal`() {
        val rng = Random(5)
        val ref = epochs(600, 62.0, 45.0, rng)
        assertNull(AutonomicLoad.evaluate(epochs(20, 80.0, 20.0, rng), ref, 10).level)
        assertNull(AutonomicLoad.evaluate(epochs(4, 80.0, 20.0, rng), ref, 30).level)
    }
}
