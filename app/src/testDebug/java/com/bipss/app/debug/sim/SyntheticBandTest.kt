package com.bipss.app.debug.sim

import com.bipss.app.ble.BandClock
import com.bipss.app.ble.WaveformAssembler
import com.bipss.app.ble.protocol.BandPacketParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The simulator is only useful if it speaks the real protocol, so every test
 * here decodes its bytes with the production parser, clock and assembler.
 */
class SyntheticBandTest {

    private class Run(val statuses: Int, val chunks: Int, val gaps: Int, val clock: BandClock, val lastStatus: com.bipss.app.ble.protocol.StatusPacket?)

    private fun run(band: SyntheticBand, seconds: Int): Run {
        val clock = BandClock()
        val asm = WaveformAssembler(clock)
        var statuses = 0; var chunks = 0; var gaps = 0
        var last: com.bipss.app.ble.protocol.StatusPacket? = null
        val wall0 = 1_700_000_000_000L
        while (band.elapsedSeconds < seconds) {
            for (p in band.step()) {
                val arrival = wall0 + p.bandUptimeMs + 20
                if (p.isStatus) {
                    val s = BandPacketParser.parseStatus(p.bytes)
                    assertNotNull("status must parse", s)
                    assertNotNull(clock.onStatus(s!!.bandUptimeMs, s.sampleIndex, arrival))
                    statuses++; last = s
                } else {
                    val w = BandPacketParser.parseWaveform(p.bytes)
                    assertNotNull("waveform must parse", w)
                    val c = asm.accept(w!!) ?: continue
                    chunks++
                    if (c.gapBefore) gaps++
                }
            }
        }
        return Run(statuses, chunks, gaps, clock, last)
    }

    @Test
    fun `clean scenario streams every packet with no gaps after the first`() {
        val r = run(SyntheticBand(SimScenario.CLEAN, seed = 7), 120)
        assertEquals(120.0, r.statuses.toDouble(), 1.0)
        assertEquals(1500.0, r.chunks.toDouble(), 2.0)   // 12.5 per second
        assertEquals(1, r.gaps)                          // only the first chunk
        val s = r.lastStatus!!
        assertNotNull(s.hr)
        assertTrue("hr ${s.hr}", s.hr!! in 55..70)
        assertTrue(s.waveformStreaming)
        // At rest only accelerometer noise remains, most of it under the 10 mg dead-band.
        assertTrue("rest activity ${s.activityCount}", s.activityCount!! in 0..20)
    }

    @Test
    fun `clock fits a sensor oscillator running 2 percent slow from simulated packets`() {
        val r = run(SyntheticBand(SimScenario.CLEAN, seed = 3, periodScale = 1.02), 90)
        assertTrue(r.clock.isPeriodFitted)
        assertEquals(20.4, r.clock.samplePeriodMs, 0.02)
    }

    @Test
    fun `dropout scenario produces gaps and invalid pulse while off the skin`() {
        val band = SyntheticBand(SimScenario.DROPOUT, seed = 11)
        val r = run(band, 150)
        // ~3 % packet loss plus a FIFO overflow every ~45 s.
        assertTrue("gaps ${r.gaps}", r.gaps > 20)
        // Second 30..38 of each minute is off the skin.
        val offSkin = SyntheticBand(SimScenario.DROPOUT, seed = 11)
        var sawNullHr = false
        while (offSkin.elapsedSeconds < 40) {
            offSkin.step().filter { it.isStatus }.forEach {
                val s = BandPacketParser.parseStatus(it.bytes)!!
                val t = (s.bandUptimeMs - 5000) / 1000
                if (t in 32..37) { assertNull(s.hr); sawNullHr = true }
            }
        }
        assertTrue(sawNullHr)
    }

    @Test
    fun `walking scenario reports activity in the documented range`() {
        val band = SyntheticBand(SimScenario.WALKING, seed = 5)
        val activity = mutableListOf<Int>()
        while (band.elapsedSeconds < 180) {
            band.step().filter { it.isStatus }.forEach {
                val s = BandPacketParser.parseStatus(it.bytes)!!
                if ((s.bandUptimeMs - 5000) / 1000 > 90) activity += s.activityCount!!
            }
        }
        val mean = activity.average()
        assertTrue("walking activity $mean mg*s", mean in 100.0..400.0)
    }

    @Test
    fun `truth beats are recorded for pipeline tests`() {
        val band = SyntheticBand(SimScenario.CLEAN, seed = 2, recordTruth = true)
        while (band.elapsedSeconds < 60) band.step()
        val hr = 60_000.0 / band.truth.map { it.ibiMs }.average()
        assertEquals(62.0, hr, 3.0)
    }
}
