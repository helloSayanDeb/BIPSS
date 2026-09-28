package com.bipss.app.ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the v2 wire layout byte by byte.
 *
 * Packets are written out as literal hex rather than built with an encoder, so
 * an offset or endianness mistake in the parser cannot be mirrored by the same
 * mistake in the test. The literals match the tables in docs/ble-protocol-v2.md.
 */
class BandPacketParserTest {

    private fun hex(s: String): ByteArray =
        s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    // header 21 | uptime 0x0001E240 = 123456 | index 0x1234 | hr 72 (0x48) | prv 45 (0x2D)
    // | spo2 97 (0x61) | skin 3650 (0x0E42) | ambient -525 (0xFDF3) | rh 61 (0x3D)
    // | uba 3 | activity 1500 (0x05DC) | battery 83 (0x53) | quality 0x7F
    private val fullStatus = hex("21 40 E2 01 00 34 12 48 2D 61 42 0E F3 FD 3D 03 DC 05 53 7F")

    @Test
    fun `status packet decodes every field at its documented offset`() {
        val p = BandPacketParser.parseStatus(fullStatus)
        assertNotNull(p); p!!
        assertEquals(123_456L, p.bandUptimeMs)
        assertEquals(0x1234, p.sampleIndex)
        assertEquals(72, p.hr)
        assertEquals(45, p.prvRmssdMs)
        assertEquals(97, p.spo2)
        assertEquals(36.50f, p.skinTempC!!, 1e-4f)
        assertEquals(-5.25f, p.ambientTempC!!, 1e-4f)
        assertEquals(61f, p.humidityPct!!, 1e-4f)
        assertEquals(3, p.ensUba)
        assertEquals(1500, p.activityCount)
        assertEquals(83, p.batteryPct)
        assertEquals(0x7F, p.quality)
        assertTrue(p.waveformStreaming)
        assertTrue(p.motionValid)
    }

    @Test
    fun `status sentinels become null`() {
        // hr 0, prv FF, spo2 FF, skin 8000, ambient 8000, rh FF, uba 0, activity FFFF, battery FF
        val p = BandPacketParser.parseStatus(hex("21 00 00 00 00 00 00 00 FF FF 00 80 00 80 FF 00 FF FF FF 7F"))!!
        assertNull(p.hr); assertNull(p.prvRmssdMs); assertNull(p.spo2)
        assertNull(p.skinTempC); assertNull(p.ambientTempC); assertNull(p.humidityPct)
        assertNull(p.ensUba); assertNull(p.activityCount); assertNull(p.batteryPct)
    }

    @Test
    fun `clear quality bit drops an otherwise plausible value`() {
        // Same bytes as fullStatus but quality 0x40: only the waveform bit set.
        val bytes = fullStatus.copyOf().also { it[19] = 0x40 }
        val p = BandPacketParser.parseStatus(bytes)!!
        assertNull(p.hr); assertNull(p.prvRmssdMs); assertNull(p.spo2)
        assertNull(p.skinTempC); assertNull(p.ambientTempC); assertNull(p.humidityPct)
        assertNull(p.ensUba); assertNull(p.activityCount)
        // Battery has no quality bit.
        assertEquals(83, p.batteryPct)
        assertFalse(p.motionValid)
    }

    @Test
    fun `uptime above 2^31 stays positive`() {
        val p = BandPacketParser.parseStatus(hex("21 00 FF FF FF 00 00 00 FF FF 00 80 00 80 FF 00 FF FF FF 00"))!!
        assertEquals(0xFFFFFF00L, p.bandUptimeMs)
    }

    @Test
    fun `out of range values are dropped not clamped`() {
        val bytes = fullStatus.copyOf()
        bytes[7] = 0xFC.toByte()   // hr 252 > 250
        bytes[9] = 40              // spo2 40 < 50
        bytes[15] = 7              // uba 7 > 5
        val p = BandPacketParser.parseStatus(bytes)!!
        assertNull(p.hr); assertNull(p.spo2); assertNull(p.ensUba)
    }

    @Test
    fun `wrong size or header is rejected`() {
        assertNull(BandPacketParser.parseStatus(fullStatus.copyOf(19)))
        assertNull(BandPacketParser.parseStatus(fullStatus.copyOf(24)))
        assertNull(BandPacketParser.parseStatus(fullStatus.copyOf().also { it[0] = 0x11 }))
        assertNull(BandPacketParser.parseWaveform(fullStatus))
    }

    // header 11 | index 0xFFFE | flags 0x05 (contact + accel valid) | ppg 1000, 1010, 1020, 65535
    // | accel (64, 0, -64) (-128, 127, 1) | band hr 70 | counter 200
    private val waveform = hex("11 FE FF 05 E8 03 F2 03 FC 03 FF FF 40 00 C0 80 7F 01 46 C8")

    @Test
    fun `waveform packet decodes samples and signed accel`() {
        val w = BandPacketParser.parseWaveform(waveform)
        assertNotNull(w); w!!
        assertEquals(0xFFFE, w.firstSampleIndex)
        assertEquals(listOf(1000, 1010, 1020, 65535), w.ppgIr)
        val a = w.accelG!!
        assertEquals(1.0f, a[0], 1e-6f)
        assertEquals(0.0f, a[1], 1e-6f)
        assertEquals(-1.0f, a[2], 1e-6f)
        assertEquals(-2.0f, a[3], 1e-6f)
        assertEquals(127 / 64f, a[4], 1e-6f)
        assertEquals(1 / 64f, a[5], 1e-6f)
        assertEquals(70, w.bandHr)
        assertEquals(200, w.counter)
        assertTrue(w.skinContact)
        assertFalse(w.saturated)
        assertFalse(w.fifoOverflow)
    }

    @Test
    fun `accel is null when its flag is clear`() {
        val w = BandPacketParser.parseWaveform(waveform.copyOf().also { it[3] = 0x09 })!!
        assertNull(w.accelG)
        assertTrue(w.fifoOverflow)
    }

    // seq 1000 | uptime 50000 | hr 72 | prv 45 | spo2 98 | skin 3650 | amb 2810 | rh 7200
    // | aqi 2 | battery 78 | quality 0x3F
    private val legacy = hex(
        "E8 03 00 00 50 C3 00 00 48 00 2D 00 62 00 42 0E FA 0A 20 1C 02 00 4E 3F"
    )

    @Test
    fun `legacy v1 frame still decodes with prv naming`() {
        val f = BandPacketParser.parseLegacy(legacy)
        assertNotNull(f); f!!
        assertEquals(1000L, f.seq)
        assertEquals(50_000L, f.bandUptimeMs)
        assertEquals(72, f.hr)
        assertEquals(45, f.prvRmssdMs)
        assertEquals(98, f.spo2)
        assertEquals(36.5f, f.skinTempC!!, 1e-4f)
        assertEquals(28.1f, f.ambientTempC!!, 1e-4f)
        assertEquals(72.0f, f.humidityPct!!, 1e-4f)
        assertEquals(2, f.ensUba)
        assertEquals(78, f.batteryPct)
    }

    @Test
    fun `legacy frame truncated to the default MTU payload is rejected`() {
        assertNull(BandPacketParser.parseLegacy(legacy.copyOf(20)))
    }

    @Test
    fun `legacy battery fallback of exactly 100 is treated as unknown`() {
        val f = BandPacketParser.parseLegacy(legacy.copyOf().also { it[22] = 100 })!!
        assertNull(f.batteryPct)
    }

    @Test
    fun `legacy minus one sentinel becomes null`() {
        val f = BandPacketParser.parseLegacy(legacy.copyOf().also { it[8] = 0xFF.toByte(); it[9] = 0xFF.toByte() })!!
        assertNull(f.hr)
    }
}
