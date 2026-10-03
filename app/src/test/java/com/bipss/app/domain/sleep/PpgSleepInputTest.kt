package com.bipss.app.domain.sleep

import com.bipss.app.ble.WaveformChunk
import org.junit.Assert.*
import org.junit.Test

class PpgSleepInputTest {
    private fun chunk(at: Long, contact: Boolean = true, gap: Boolean = false) = WaveformChunk(
        at / 20, List(4) { 50000 + ((at / 20 + it) % 50).toInt() * 20 }, null,
        contact, false, gap, null, at, 20.0)
    @Test fun contactLossAndGapsCannotBecomeUsableEpochs() {
        val builder = PpgEpochBuilder()
        val output = (0L..60_080L step 80).flatMap { builder.accept(chunk(it, it < 30_000)) }
        assertEquals(2, output.size)
        assertTrue(output[0].valid)
        assertFalse(output[1].valid)
        assertEquals(1024, output[0].samples.size)
    }
    @Test fun doesNotInterpolateAcrossMissingMinutes() {
        val builder = PpgEpochBuilder()
        (0L..29_920L step 80).forEach { builder.accept(chunk(it)) }
        val first = builder.accept(chunk(90_000, gap = true))
        assertEquals(1, first.size)
        val partial = builder.accept(chunk(120_000, gap = true)).single()
        assertFalse(partial.valid)
    }
    @Test fun normalizationUsesSampleDeviationAndRejectsFlatInput() {
        val normalized = PpgSleepInput.normalize(floatArrayOf(2f, 4f, 6f))!!
        assertArrayEquals(floatArrayOf(-1f, 0f, 1f), normalized, 0.00001f)
        assertNull(PpgSleepInput.normalize(floatArrayOf(4f, 4f)))
        assertNull(PpgSleepInput.normalize(floatArrayOf(Float.NaN, 1f)))
    }
    @Test fun missingUncertainAndNonfiniteResultsStayUnknown() {
        assertEquals(-1, PpgSleepInput.stage(floatArrayOf(1f,1f,1f,1f), true))
        assertEquals(-1, PpgSleepInput.stage(floatArrayOf(9f,1f,1f,1f), false))
        assertEquals(-1, PpgSleepInput.stage(floatArrayOf(Float.NaN,1f,1f,1f), true))
        assertEquals(3, PpgSleepInput.stage(floatArrayOf(-8f,-8f,-8f,8f), true))
    }
}
