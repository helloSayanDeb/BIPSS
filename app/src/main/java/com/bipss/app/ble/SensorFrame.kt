package com.bipss.app.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One decoded 1 Hz frame from the band.
 *
 * Every measured field is nullable because the band reports validity per sensor
 * and a frame is emitted every second whether or not the sensors agreed to
 * cooperate. There is deliberately no fallback value anywhere in this file: a
 * substituted reading is indistinguishable from a real one once it reaches the
 * scoring layer.
 *
 * [quality] carries the per-sensor validity bitmask exactly as the firmware sent
 * it, so a caller that needs more nuance than the nulled-out fields provide can
 * still ask which sensor dropped out.
 */
data class SensorFrame(
    val seq:          Long,
    /** Milliseconds since band boot. The band's own clock is the timestamp source. */
    val bandUptimeMs: Long,
    val hr:           Int?,
    val hrvRmssd:     Int?,
    val spo2:         Int?,
    val skinTempC:    Float?,
    val ambTempC:     Float?,
    val humidityPct:  Float?,
    val aqiRaw:       Int?,
    val batteryPct:   Int?,
    val quality:      Int
) {
    val hrValid:        Boolean get() = (quality and BIT_HR)        != 0
    val spo2Valid:      Boolean get() = (quality and BIT_SPO2)      != 0
    val skinTempValid:  Boolean get() = (quality and BIT_SKIN_TEMP) != 0
    val humidityValid:  Boolean get() = (quality and BIT_AMBIENT)   != 0
    val aqiValid:       Boolean get() = (quality and BIT_AQI)       != 0
    val motionValid:    Boolean get() = (quality and BIT_MOTION)    != 0

    companion object {
        /** MAX30102 photoplethysmogram gave a usable pulse this second. */
        const val BIT_HR        = 1 shl 0

        /** MAX30102 red/IR ratio was usable - i.e. the sensor had skin contact. */
        const val BIT_SPO2      = 1 shl 1

        /** TMP117 skin thermistor settled. */
        const val BIT_SKIN_TEMP = 1 shl 2

        /** AHT25 ambient temperature and humidity. One sensor, one bit, both fields. */
        const val BIT_AMBIENT   = 1 shl 3

        /** ENS160 gas heater stabilised - the index is meaningless before that. */
        const val BIT_AQI       = 1 shl 4

        /** MPU6050 accelerometer responded. Carried through for motion-artefact gating. */
        const val BIT_MOTION    = 1 shl 5
    }
}

/**
 * Decoder for the band's packed 24-byte frame.
 *
 * Kept apart from the GATT plumbing and free of Android imports so it can be
 * unit-tested on the JVM. A byte-order or offset mistake here does not fail
 * loudly - it produces plausible-looking numbers, which is the worst failure
 * this app can have, so the layout is pinned by tests rather than by inspection.
 *
 * The quality bitmask is the reason this decoder exists rather than a handful of
 * `getShort` calls at the call site. A SpO2 of 0 with the SpO2 bit clear means
 * the sensor lost skin contact; a SpO2 of 0 with the bit set would mean the
 * reading is real. Treating those alike is the difference between a false alarm
 * and a genuine one, so a field whose bit is clear is dropped to null and never
 * reaches the scoring layer as a number.
 */
object SensorFrameParser {

    const val FRAME_SIZE = 24

    /** Firmware sends -1 in place of a reading it could not take. */
    private const val INVALID = -1

    // Physiological and sensor limits. A reading outside these is a fault, not a
    // measurement, so it is dropped rather than clamped - clamping would invent a
    // plausible value at the edge of the range and present it as observed.
    private val HR_RANGE       = 20..250
    private val SPO2_RANGE     = 50..100
    private val BATTERY_RANGE  = 0..100
    private val SKIN_TEMP_C    = 20f..45f
    private val HUMIDITY_PCT   = 0f..100f

    /**
     * Returns null for a truncated frame rather than throwing: a short notification
     * is a transport hiccup the BLE layer should log and move past, not a crash.
     */
    fun parse(bytes: ByteArray): SensorFrame? {
        if (bytes.size < FRAME_SIZE) return null

        val buf = ByteBuffer.wrap(bytes, 0, FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        // uint32 via toUInt() so seq and uptime keep counting past 2^31 instead of
        // wrapping negative and looking like a clock that ran backwards.
        val seq          = buf.int.toUInt().toLong()
        val bandUptimeMs = buf.int.toUInt().toLong()

        val rawHr        = buf.short.toInt()
        val rawHrv       = buf.short.toInt()
        val rawSpo2      = buf.short.toInt()
        val rawSkinTemp  = buf.short.toInt()
        val rawAmbTemp   = buf.short.toInt()
        val rawHumidity  = buf.short.toInt()
        val rawAqi       = buf.short.toInt()
        val rawBattery   = buf.get().toInt() and 0xFF
        val quality      = buf.get().toInt() and 0xFF

        return SensorFrame(
            seq          = seq,
            bandUptimeMs = bandUptimeMs,
            hr           = gated(rawHr, quality, SensorFrame.BIT_HR)?.takeIf { it in HR_RANGE },
            // RMSSD is derived from the same photoplethysmogram as the pulse, so it
            // stands or falls with the HR bit.
            hrvRmssd     = gated(rawHrv, quality, SensorFrame.BIT_HR),
            spo2         = gated(rawSpo2, quality, SensorFrame.BIT_SPO2)?.takeIf { it in SPO2_RANGE },
            skinTempC    = scaled(rawSkinTemp, quality, SensorFrame.BIT_SKIN_TEMP)
                ?.takeIf { it in SKIN_TEMP_C },
            ambTempC     = scaled(rawAmbTemp, quality, SensorFrame.BIT_AMBIENT),
            humidityPct  = scaled(rawHumidity, quality, SensorFrame.BIT_AMBIENT)
                ?.takeIf { it in HUMIDITY_PCT },
            aqiRaw       = gated(rawAqi, quality, SensorFrame.BIT_AQI)?.takeIf { it >= 0 },
            // Battery is not a sensor, so it has no quality bit - only a range guard.
            batteryPct   = rawBattery.takeIf { it in BATTERY_RANGE },
            quality      = quality
        )
    }

    /** Raw int16, or null when the firmware flagged it invalid or its sensor bit is clear. */
    private fun gated(raw: Int, quality: Int, bit: Int): Int? =
        if (raw == INVALID || (quality and bit) == 0) null else raw

    /** As [gated], for the x100 fixed-point fields. */
    private fun scaled(raw: Int, quality: Int, bit: Int): Float? =
        gated(raw, quality, bit)?.let { it / 100f }
}
