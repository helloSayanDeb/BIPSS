package com.bipss.app.ble

import com.bipss.app.ble.protocol.BandProtocol
import com.bipss.app.ble.protocol.WaveformPacket

/**
 * 80 ms of band signal, placed on the unwrapped sample axis.
 *
 * [gapBefore] is the contract with the signal pipeline: when it is set, samples
 * were lost immediately before this chunk (a dropped packet, a band FIFO
 * overflow, or the first chunk of a connection), so no interbeat interval may
 * be measured across it. An interval that silently spans a gap is one or more
 * beats long and reads as a sudden, fictitious drop in heart rate.
 */
data class WaveformChunk(
    /** Unwrapped index of [ppgIr]`[0]`; consecutive chunks without a gap differ by 4. */
    val firstSampleIndex:  Long,
    val ppgIr:             List<Int>,
    /** x0, y0, z0, x1, y1, z1 in g - sample j is held against PPG sample 2j. Null if invalid. */
    val accelG:            List<Float>?,
    val skinContact:       Boolean,
    val saturated:         Boolean,
    val gapBefore:         Boolean,
    val bandHr:            Int?,
    /** Wall time of the first sample; null until the first status packet anchors the clock. */
    val firstSampleWallMs: Long?,
    /** Fitted (or, early on, nominal) PPG sample period for this connection. */
    val samplePeriodMs:    Double
)

/** Turns raw waveform packets into [WaveformChunk]s, detecting loss. Pure; one per connection. */
class WaveformAssembler(private val clock: BandClock) {

    private var expectedIndex: Long? = null
    private var lastCounter: Int? = null

    /** Number of chunks flagged with [WaveformChunk.gapBefore] since the last reset (diagnostics). */
    var gapCount: Int = 0
        private set

    /** Returns null for a duplicate or out-of-order packet, which carries nothing new. */
    fun accept(packet: WaveformPacket): WaveformChunk? {
        val index = clock.unwrapWaveformIndex(packet.firstSampleIndex)
        val expected = expectedIndex
        if (expected != null && index < expected) return null

        val counterGap = lastCounter?.let { ((it + 1) and 0xFF) != packet.counter } ?: false
        val gap = expected == null || index != expected || counterGap || packet.fifoOverflow
        if (gap) gapCount++

        expectedIndex = index + BandProtocol.PPG_SAMPLES_PER_PACKET
        lastCounter = packet.counter

        return WaveformChunk(
            firstSampleIndex  = index,
            ppgIr             = packet.ppgIr,
            accelG            = packet.accelG,
            skinContact       = packet.skinContact,
            saturated         = packet.saturated,
            gapBefore         = gap,
            bandHr            = packet.bandHr,
            firstSampleWallMs = clock.sampleWallTimeMs(index),
            samplePeriodMs    = clock.samplePeriodMs
        )
    }

    fun reset() {
        expectedIndex = null
        lastCounter = null
        gapCount = 0
    }
}
