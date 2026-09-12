package com.bipss.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

// NOTE: app/build.gradle.kts has no testImplementation for JUnit yet - it needs
// adding before this suite will run.

/**
 * Pins the wire layout.
 *
 * The failure mode this guards against is silent: a wrong offset or the wrong
 * byte order still yields numbers in a believable range, which then flow into
 * scoring as if they were measurements. Hand-built frames with known values are
 * the only way to catch that.
 */
class SensorFrameParserTest {

    @Test
    fun `valid frame decodes every field`() {
        val frame = SensorFrameParser.parse(frameBytes())

        requireNotNull(frame)

        assertEquals(1000L,  frame.seq)
        assertEquals(50_000L, frame.bandUptimeMs)
        assertEquals(72,  frame.hr)
        assertEquals(45,  frame.hrvRmssd)
        assertEquals(98,  frame.spo2)
        assertEquals(85,  frame.aqiRaw)
        assertEquals(78,  frame.batteryPct)
        assertEquals(0xFF, frame.quality)

        assertEquals(36.5f, frame.skinTempC!!,   0.0001f)
        assertEquals(28.1f, frame.ambTempC!!,    0.0001f)
        assertEquals(72.0f, frame.humidityPct!!, 0.0001f)
    }

    @Test
    fun `short array returns null rather than throwing`() {
        assertNull(SensorFrameParser.parse(ByteArray(SensorFrameParser.FRAME_SIZE - 1)))
        assertNull(SensorFrameParser.parse(ByteArray(0)))
    }

    @Test
    fun `sentinel heart rate becomes null`() {
        val frame = SensorFrameParser.parse(frameBytes(hr = -1))

        requireNotNull(frame)
        assertNull(frame.hr)
    }

    @Test
    fun `clear spo2 quality bit discards an otherwise valid reading`() {
        // 98 is a perfectly plausible SpO2 - only the bitmask says the sensor
        // was off the skin, which is exactly the case this parser exists for.
        val quality = 0xFF and SensorFrame.BIT_SPO2.inv()
        val frame   = SensorFrameParser.parse(frameBytes(spo2 = 98, quality = quality))

        requireNotNull(frame)
        assertNull(frame.spo2)
        assertEquals(72, frame.hr)
    }

    @Test
    fun `out of range heart rate is dropped not clamped`() {
        val frame = SensorFrameParser.parse(frameBytes(hr = 400))

        requireNotNull(frame)
        assertNull(frame.hr)
    }

    @Test
    fun `uptime past two to the thirty-one stays positive`() {
        val frame = SensorFrameParser.parse(frameBytes(uptimeMs = 0xFFFFFF00L))

        requireNotNull(frame)
        assertEquals(4_294_967_040L, frame.bandUptimeMs)
    }

    private fun frameBytes(
        seq:        Long = 1000L,
        uptimeMs:   Long = 50_000L,
        hr:         Int  = 72,
        hrvRmssd:   Int  = 45,
        spo2:       Int  = 98,
        skinTempX100: Int = 3650,
        ambTempX100:  Int = 2810,
        humidityX100: Int = 7200,
        aqiRaw:     Int  = 85,
        batteryPct: Int  = 78,
        quality:    Int  = 0xFF
    ): ByteArray = ByteBuffer
        .allocate(SensorFrameParser.FRAME_SIZE)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(seq.toInt())
        .putInt(uptimeMs.toInt())
        .putShort(hr.toShort())
        .putShort(hrvRmssd.toShort())
        .putShort(spo2.toShort())
        .putShort(skinTempX100.toShort())
        .putShort(ambTempX100.toShort())
        .putShort(humidityX100.toShort())
        .putShort(aqiRaw.toShort())
        .put(batteryPct.toByte())
        .put(quality.toByte())
        .array()
}
