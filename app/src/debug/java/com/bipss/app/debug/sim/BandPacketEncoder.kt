package com.bipss.app.debug.sim

import com.bipss.app.ble.protocol.BandProtocol
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Builds v2 packets exactly as firmware/bipss_band_v2/code.py packs them, so
 * simulated data goes through the real parser byte for byte. Debug builds only.
 */
object BandPacketEncoder {

    fun status(
        uptimeMs: Long,
        sampleIndex: Long,
        hr: Int?,
        prvRmssdMs: Int?,
        spo2: Int?,
        skinTempC: Double?,
        ambientTempC: Double?,
        humidityPct: Double?,
        ensUba: Int?,
        activityCount: Int?,
        batteryPct: Int?,
        quality: Int
    ): ByteArray {
        val b = ByteBuffer.allocate(BandProtocol.PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put(BandProtocol.HEADER_STATUS.toByte())
        b.putInt((uptimeMs and 0xFFFFFFFFL).toInt())
        b.putShort((sampleIndex and 0xFFFF).toInt().toShort())
        b.put((hr ?: 0).coerceIn(0, 255).toByte())
        b.put((prvRmssdMs?.takeIf { it in 1..254 } ?: 0xFF).toByte())
        b.put((spo2 ?: 0xFF).toByte())
        b.putShort(centi(skinTempC))
        b.putShort(centi(ambientTempC))
        b.put((humidityPct?.roundToInt()?.coerceIn(0, 100) ?: 0xFF).toByte())
        b.put((ensUba ?: 0).toByte())
        b.putShort((activityCount?.coerceIn(0, 0xFFFE) ?: 0xFFFF).toShort())
        b.put((batteryPct ?: 0xFF).toByte())
        b.put(quality.toByte())
        return b.array()
    }

    fun waveform(
        firstSampleIndex: Long,
        flags: Int,
        ppg: IntArray,
        accelG: FloatArray,
        bandHr: Int?,
        counter: Int
    ): ByteArray {
        require(ppg.size == BandProtocol.PPG_SAMPLES_PER_PACKET)
        require(accelG.size == BandProtocol.ACCEL_SAMPLES_PER_PACKET * 3)
        val b = ByteBuffer.allocate(BandProtocol.PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put(BandProtocol.HEADER_WAVEFORM.toByte())
        b.putShort((firstSampleIndex and 0xFFFF).toInt().toShort())
        b.put(flags.toByte())
        ppg.forEach { b.putShort(it.coerceIn(0, 0xFFFF).toShort()) }
        accelG.forEach { b.put((it / BandProtocol.ACCEL_G_PER_LSB).roundToInt().coerceIn(-128, 127).toByte()) }
        b.put((bandHr ?: 0).coerceIn(0, 255).toByte())
        b.put((counter and 0xFF).toByte())
        return b.array()
    }

    private fun centi(v: Double?): Short =
        if (v == null) Short.MIN_VALUE else (v * 100).roundToInt().coerceIn(-0x7FFF, 0x7FFF).toShort()
}
