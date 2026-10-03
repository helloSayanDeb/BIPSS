package com.bipss.app.domain.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepAnalysisTest {

    private fun night(minutes: Int, activity: (Int) -> Double = { 60.0 }, onWrist: (Int) -> Boolean = { true },
                      hr: (Int) -> Double? = { 55.0 }) =
        (0 until minutes).map { MinuteEpoch(it * 60_000L, activity(it), hr(it), onWrist(it)) }

    @Test
    fun `cole-kripke weights are centred on the current minute`() {
        // One minute of movement at 100: the published window is A-4..A+2, so
        // it can only wake minutes 98..104 - and alone at the centre (230 of
        // 665) it takes ~4.3 counts = ~1 300 mg*s.
        val epochs = night(200, activity = { if (it == 100) 1_400.0 else 0.0 })
        val asleep = SleepDetector.score(epochs, null, dayN = 0)
        assertFalse(asleep[100])
        assertTrue((0 until 200).filter { !asleep[it] }.all { it in 98..104 })
        val below = SleepDetector.score(night(200, activity = { if (it == 100) 1_200.0 else 0.0 }), null, 0)
        assertTrue(below[100])
    }

    @Test
    fun `off-wrist minutes are never sleep and split the night`() {
        // 8 h still, but the band came off for 20 minutes in the middle.
        val epochs = night(480, onWrist = { it !in 200 until 220 })
        val asleep = SleepDetector.score(epochs, null, 0)
        assertTrue((200 until 220).none { asleep[it] })
        val s = SleepDetector.detect(epochs, null, 0)!!
        // The longer on-wrist stretch wins; the session never spans the gap.
        assertEquals(220 * 60_000L, s.startMs)
        assertEquals(260 * 60_000L, s.totalSleepMs)
    }

    @Test
    fun `no data is not sleep`() {
        assertNull(SleepDetector.detect(night(480, onWrist = { false }), null, 0))
    }

    @Test
    fun `heart rate gate waits for shared day 14 baseline`() {
        // Lying still but with a racing pulse: awake, once a sleeping rate is known.
        val epochs = night(120, hr = { 90.0 })
        assertTrue(SleepDetector.score(epochs, sleepingHr = 55.0, dayN = 13).all { it })
        assertTrue(SleepDetector.score(epochs, sleepingHr = 55.0, dayN = 14).none { it })
    }

    @Test
    fun `efficiency, waso and awakenings`() {
        // Two restless periods, each long enough to wake two or more consecutive minutes.
        val epochs = night(300, activity = { if (it in 100..104 || it in 200..201) 3_000.0 else 0.0 })
        val s = SleepDetector.detect(epochs, null, 0)!!
        assertTrue(s.efficiencyPct in 90..99)
        assertEquals(2, s.awakenings)
        assertTrue(s.wakeAfterOnsetMs > 0)
    }

    // -- oxygen dips ---------------------------------------------------------------------

    private fun spo2(seconds: Int, dip: (Int) -> Float) = (0 until seconds).map { Sample(it * 1000L, 97f - dip(it)) }

    @Test
    fun `a 20 second 5 point dip is one counted event`() {
        val events = DesaturationScreening.detectDesaturations(
            spo2(600) { if (it in 300 until 320) 5f else 0f }, emptyList()
        )
        assertEquals(1, events.size)
        assertEquals(1, DesaturationScreening.validEvents(events).size)
        assertEquals(5f, events[0].dropPercent, 0.01f)
    }

    @Test
    fun `short dips and dips near movement are kept but not counted`() {
        val short = DesaturationScreening.detectDesaturations(spo2(600) { if (it in 300 until 306) 5f else 0f }, emptyList())
        assertEquals(1, short.size)
        assertTrue(DesaturationScreening.validEvents(short).isEmpty())

        val moved = DesaturationScreening.detectDesaturations(
            spo2(600) { if (it in 300 until 320) 5f else 0f },
            listOf(Sample(292_000L, 80f))   // movement 8 s before the drop
        )
        assertTrue(moved.single().motionArtefact)
        assertTrue(DesaturationScreening.validEvents(moved).isEmpty())
    }

    @Test
    fun `summary reports a number, never a grade`() {
        val nights = (0 until 14).map { NightScreening(0, 1, 30, 2, 7.2f) }
        val s = DesaturationScreening.screeningSummary(nights)!!
        assertTrue(s.contains("7.2 oxygen dips per hour"))
        assertTrue(s.contains("This is not a diagnosis"))
        listOf("mild", "moderate", "severe", "apnea", "apnoea").forEach { assertFalse(it, s.lowercase().contains(it)) }
        assertNull(DesaturationScreening.screeningSummary(nights.take(13)))
    }
}
