package com.bipss.app.ble

import com.bipss.app.ble.protocol.WaveformPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BandClockTest {

    /**
     * Drives the clock with status packets from a band whose PPG runs at
     * [periodMs] per sample, arriving with the given per-packet latencies.
     */
    private fun feed(
        clock: BandClock,
        seconds: Int,
        periodMs: Double,
        wallStart: Long = 1_700_000_000_000L,
        uptimeStart: Long = 10_000L,
        indexStart: Long = 0L,
        latency: (Int) -> Long = { 40L + (it * 37 % 90) }
    ): List<Long?> = (0 until seconds).map { s ->
        val uptime = uptimeStart + s * 1000L
        val index = indexStart + (s * 1000.0 / periodMs).toLong()
        clock.onStatus(uptime, (index and 0xFFFF).toInt(), wallStart + (uptime - uptimeStart) + latency(s))
    }

    @Test
    fun `offset converges on the minimum transport delay`() {
        val clock = BandClock()
        val wallStart = 1_700_000_000_000L
        val out = feed(clock, 30, 20.0, wallStart = wallStart, latency = { if (it == 7) 5L else 60L + it % 40 })
        // After packet 7 the minimum latency (5 ms) is in the window: times are
        // within 5 ms of the truth rather than 60+.
        val last = out.last()!!
        val truth = wallStart + 29_000L
        assertTrue("got ${last - truth}", last - truth in 0..5)
    }

    @Test
    fun `fitted period recovers a sensor oscillator running 3 percent slow`() {
        val clock = BandClock()
        feed(clock, 60, periodMs = 20.6)
        assertTrue(clock.isPeriodFitted)
        assertEquals(20.6, clock.samplePeriodMs, 0.01)
    }

    @Test
    fun `sample index unwraps across the 16 bit boundary`() {
        val clock = BandClock()
        // Start 10 s before the wrap and run 30 s past it.
        feed(clock, 40, periodMs = 20.0, indexStart = 65_536L - 500L)
        val wf = clock.unwrapWaveformIndex(100)
        assertEquals(65_536L + 100L, wf)
    }

    @Test
    fun `status unwrap survives a gap longer than half the index range`() {
        val clock = BandClock()
        feed(clock, 20, periodMs = 20.0)
        // 15 minutes of dropped packets = 45 000 samples, more than 32 768.
        val gapUptime = 10_000L + 19_000L + 15 * 60_000L
        val trueIndex = (gapUptime - 10_000L) / 20
        val wall = clock.onStatus(gapUptime, (trueIndex and 0xFFFF).toInt(), 1_700_000_000_000L + gapUptime)
        assertNotNull(wall)
        assertEquals(trueIndex + 4, clock.unwrapWaveformIndex(((trueIndex + 4) and 0xFFFF).toInt()))
    }

    @Test
    fun `uptime running backwards is a reboot and resets the mapping`() {
        val clock = BandClock()
        feed(clock, 20, periodMs = 20.0, uptimeStart = 100_000L)
        assertTrue(clock.isPeriodFitted)
        assertNull(clock.onStatus(500L, 25, 1_700_000_100_000L))
        assertFalse(clock.isPeriodFitted)
    }

    @Test
    fun `uptime u32 wrap is not mistaken for a reboot`() {
        val clock = BandClock()
        val top = 0xFFFFFFFFL - 1_500L
        assertNotNull(clock.onStatus(top, 0, 1_700_000_000_000L))
        assertNotNull(clock.onStatus(top + 1000, 50, 1_700_000_001_000L))
        // Next second wraps to near zero.
        val wrapped = (top + 2000) and 0xFFFFFFFFL
        val wall = clock.onStatus(wrapped, 100, 1_700_000_002_000L)
        assertNotNull(wall)
        assertEquals(1_700_000_002_000L, wall!!, 5.0)
    }

    @Test
    fun `sample wall time follows the fitted period, not the nominal one`() {
        val clock = BandClock()
        val wallStart = 1_700_000_000_000L
        feed(clock, 60, periodMs = 21.0, wallStart = wallStart, latency = { 0L })
        // Sample 1000 was taken 21 000 ms after sample 0. The status packet
        // reports the newest sample at its uptime, so the mapping can sit up to
        // one period late - irrelevant for intervals, which only use differences.
        val t = clock.sampleWallTimeMs(1000L)!!
        assertEquals((wallStart + 21_000L).toDouble(), t.toDouble(), 21.0)
        // With a nominal 20 ms period the same sample would be placed a full second early.
        assertTrue(t - (wallStart + 20_000L) > 900)
    }

    @Test
    fun `implausible period is rejected and nominal kept`() {
        val clock = BandClock()
        feed(clock, 60, periodMs = 30.0)
        assertFalse(clock.isPeriodFitted)
        assertTrue(clock.lastFitRejected)
        assertEquals(20.0, clock.samplePeriodMs, 1e-9)
    }

    // -- WaveformAssembler ------------------------------------------------------

    private fun wf(index: Int, counter: Int, flags: Int = WaveformPacket.FLAG_CONTACT) =
        WaveformPacket(index and 0xFFFF, flags, listOf(1, 2, 3, 4), null, null, counter and 0xFF)

    @Test
    fun `assembler flags the first chunk and any loss as a gap`() {
        val clock = BandClock()
        val asm = WaveformAssembler(clock)
        val a = asm.accept(wf(0, 0))!!
        val b = asm.accept(wf(4, 1))!!
        val c = asm.accept(wf(12, 3))!!          // one packet lost
        val d = asm.accept(wf(16, 4, WaveformPacket.FLAG_FIFO_OVERFLOW))!!
        val e = asm.accept(wf(20, 5))!!
        assertTrue(a.gapBefore)
        assertFalse(b.gapBefore)
        assertTrue(c.gapBefore)
        assertTrue(d.gapBefore)
        assertFalse(e.gapBefore)
        assertEquals(3, asm.gapCount)
    }

    @Test
    fun `assembler drops duplicates and keeps a continuous index across the wrap`() {
        val clock = BandClock()
        val asm = WaveformAssembler(clock)
        var idx = 65_528
        var counter = 250
        repeat(4) {
            val chunk = asm.accept(wf(idx, counter))!!
            assertEquals((65_528 + it * 4).toLong(), chunk.firstSampleIndex)
            idx += 4; counter++
        }
        assertNull(asm.accept(wf(65_532, 251)))
    }

    private fun assertEquals(expected: Long, actual: Long, tolerance: Double) =
        assertEquals(expected.toDouble(), actual.toDouble(), tolerance)
}
