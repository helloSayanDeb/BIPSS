package com.bipss.app.domain.sleep

import com.bipss.app.ble.protocol.BandPacketParser
import com.bipss.app.debug.sim.SimScenario
import com.bipss.app.debug.sim.SyntheticBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A whole simulated night through the real packet parser and the sleep and
 * oxygen-dip analysis. The scenario: 20 minutes of settling with fidgets,
 * sleep with five short movement bouts, seven desaturations (one of them
 * seconds after a movement bout), waking restlessness in the last 10 minutes.
 */
class SimulatedNightTest {

    private data class Night(val epochs: List<MinuteEpoch>, val spo2: List<Sample>, val activity: List<Sample>)

    private val night: Night by lazy {
        val band = SyntheticBand(SimScenario.OVERNIGHT, seed = 17)
        val activity = ArrayList<Sample>()
        val hr = ArrayList<Sample>()
        val spo2 = ArrayList<Sample>()
        while (band.elapsedSeconds < 8 * 3600) {
            for (p in band.step()) if (p.isStatus) {
                val s = BandPacketParser.parseStatus(p.bytes)!!
                val t = s.bandUptimeMs - 5_000
                s.activityCount?.let { activity += Sample(t, it.toFloat()) }
                s.hr?.let { hr += Sample(t, it.toFloat()) }
                s.spo2?.let { spo2 += Sample(t, it.toFloat()) }
            }
        }
        val actByMin = activity.groupBy { it.timestampMs / 60_000 }
        val hrByMin = hr.groupBy { it.timestampMs / 60_000 }
        val epochs = (0 until 8 * 60).map { m ->
            val a = actByMin[m.toLong()].orEmpty()
            MinuteEpoch(
                startMs = m * 60_000L,
                activity = a.sumOf { it.value.toDouble() },
                hr = hrByMin[m.toLong()]?.map { it.value.toDouble() }?.average(),
                onWrist = a.size >= 30
            )
        }
        Night(epochs, spo2, activity)
    }

    @Test
    fun `the night is detected from settling to waking`() {
        val s = SleepDetector.detect(night.epochs, sleepingHr = null, dayN = 0)!!
        // Onset during or just after the settling period; wake within the last half hour.
        assertTrue("onset ${s.startMs / 60_000} min", s.startMs / 60_000 in 0..30)
        assertTrue("wake ${s.endMs / 60_000} min", s.endMs / 60_000 in 450..480)
        assertTrue("sleep ${s.totalSleepMs / 3_600_000.0} h", s.totalSleepMs >= 6.5 * 3_600_000)
        assertTrue("awakenings ${s.awakenings}", s.awakenings in 1..12)
    }

    @Test
    fun `the seven dips are found and the one beside a movement bout is not counted`() {
        val s = SleepDetector.detect(night.epochs, null, 0)!!
        val events = DesaturationScreening.detectDesaturations(
            night.spo2.filter { it.timestampMs in s.startMs..s.endMs }, night.activity
        )
        val counted = DesaturationScreening.validEvents(events)
        assertEquals("counted ${counted.map { it.startMs / 1000 }}", 6, counted.size)
        // The dip that starts 5 s after the 2-hour movement bout.
        assertTrue(events.any { it.motionArtefact && it.startMs / 1000 in 7200..7260 })
    }
}
