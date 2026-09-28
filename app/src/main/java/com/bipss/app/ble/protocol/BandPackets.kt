package com.bipss.app.ble.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BIPSS band wire protocol v2. The full contract, with byte tables and the
 * activity-count definition, is in docs/ble-protocol-v2.md; this file is the
 * phone-side decoder and the only place offsets are written down in code.
 *
 * Both packets are exactly [BandProtocol.PACKET_SIZE] bytes so they fit the
 * default 23-byte ATT MTU (20 bytes of payload) without negotiation.
 *
 * Free of Android imports so the layout can be pinned by JVM tests: a wrong
 * offset or byte order does not fail loudly, it produces believable numbers.
 */
object BandProtocol {
    const val PACKET_SIZE = 20
    const val LEGACY_FRAME_SIZE = 24

    const val HEADER_STATUS   = 0x21
    const val HEADER_WAVEFORM = 0x11

    /** MAX30102 at 100 Hz with hardware SMP_AVE=2. Nominal only - [com.bipss.app.ble.BandClock] fits the real period. */
    const val NOMINAL_PPG_HZ = 50
    const val NOMINAL_PPG_PERIOD_MS = 1000.0 / NOMINAL_PPG_HZ
    const val PPG_SAMPLES_PER_PACKET = 4
    const val ACCEL_SAMPLES_PER_PACKET = 2

    /** Accelerometer on the wire: signed 8-bit, 1 LSB = 1/64 g (so +-2 g). */
    const val ACCEL_G_PER_LSB = 1.0f / 64f

    /** The waveform sample index is a 16-bit counter; it wraps every ~21.8 min at 50 Hz. */
    const val SAMPLE_INDEX_MODULO = 1 shl 16
}

/** Per-sensor validity bits carried in byte 19 of the status packet (and byte 23 of the v1 frame). */
object QualityBits {
    /** MAX30102 gave a usable pulse this second. */
    const val HR        = 1 shl 0
    /** Red/IR ratio usable - the sensor had skin contact. */
    const val SPO2      = 1 shl 1
    /** TMP117 skin thermistor settled. */
    const val SKIN_TEMP = 1 shl 2
    /** AHT21 temperature and humidity (one sensor, one bit, both fields). */
    const val AMBIENT   = 1 shl 3
    /** ENS160 heater stabilised and a fresh AQI-UBA value was read. */
    const val AQI       = 1 shl 4
    /** MPU6050 responded; the activity count is meaningful. */
    const val MOTION    = 1 shl 5
    /** v2 only: the band is streaming waveform packets on 6E400004. */
    const val WAVEFORM  = 1 shl 6
}

/**
 * The 1 Hz status packet (header 0x21) on characteristic 6E400003.
 *
 * Every measured field is nullable: the band sends a sentinel for a reading it
 * could not take, and a field whose quality bit is clear is dropped here rather
 * than passed on. A substituted value is indistinguishable from a real one by
 * the time it reaches scoring, so there is no fallback anywhere in this file.
 */
data class StatusPacket(
    /** Band uptime in ms, from monotonic_ns on the band. Wraps at 2^32 (~49.7 days). */
    val bandUptimeMs: Long,
    /** Waveform sample index (16-bit, wrapping) of the newest PPG sample at [bandUptimeMs]. */
    val sampleIndex:  Int,
    val hr:           Int?,
    /** RMSSD computed on the band from pulse intervals: pulse rate variability. */
    val prvRmssdMs:   Int?,
    val spo2:         Int?,
    val skinTempC:    Float?,
    val ambientTempC: Float?,
    val humidityPct:  Float?,
    /** ENS160 air-quality index, UBA scale 1 (excellent) .. 5 (unhealthy). */
    val ensUba:       Int?,
    /** Integrated movement over the last second in milli-g seconds; see the protocol doc. */
    val activityCount: Int?,
    val batteryPct:   Int?,
    val quality:      Int
) {
    val waveformStreaming: Boolean get() = (quality and QualityBits.WAVEFORM) != 0
    val motionValid:       Boolean get() = (quality and QualityBits.MOTION) != 0
}

/**
 * One waveform packet (header 0x11) on characteristic 6E400004: four PPG
 * samples at 50 Hz and two accelerometer samples at 25 Hz, i.e. 80 ms of signal.
 */
data class WaveformPacket(
    /** 16-bit wrapping index of [ppgIr]`[0]`. */
    val firstSampleIndex: Int,
    val flags:            Int,
    /** IR channel, 18-bit ADC value shifted right by 2 on the band. */
    val ppgIr:            List<Int>,
    /** x0, y0, z0, x1, y1, z1 in g, or null when the accelerometer was not valid. */
    val accelG:           List<Float>?,
    /** The band's own instantaneous estimate - informational only. */
    val bandHr:           Int?,
    /** 8-bit wrapping packet counter, for loss detection. */
    val counter:          Int
) {
    val skinContact:  Boolean get() = (flags and FLAG_CONTACT) != 0
    val saturated:    Boolean get() = (flags and FLAG_SATURATED) != 0
    /** The band's FIFO overflowed before this packet: samples were lost ahead of it. */
    val fifoOverflow: Boolean get() = (flags and FLAG_FIFO_OVERFLOW) != 0

    companion object {
        const val FLAG_CONTACT       = 1 shl 0
        const val FLAG_SATURATED     = 1 shl 1
        const val FLAG_ACCEL_VALID   = 1 shl 2
        const val FLAG_FIFO_OVERFLOW = 1 shl 3
    }
}

/**
 * The original 24-byte frame (firmware v1), still decoded so a band that has
 * not been reflashed keeps working. v1 has no motion data and no waveform, so
 * anything that depends on either says it needs firmware v2.
 */
data class LegacyFrameV1(
    val seq:          Long,
    val bandUptimeMs: Long,
    val hr:           Int?,
    /** RMSSD of pulse intervals, i.e. PRV (v1 firmware used an older name for it). */
    val prvRmssdMs:   Int?,
    val spo2:         Int?,
    val skinTempC:    Float?,
    val ambientTempC: Float?,
    val humidityPct:  Float?,
    /** ENS160 DATA_AQI & 0x07 - the same 1..5 UBA scale v2 uses. */
    val ensUba:       Int?,
    val batteryPct:   Int?,
    val quality:      Int
)

object BandPacketParser {

    // Physiological and sensor limits. Outside them a reading is a fault, not a
    // measurement: it is dropped, never clamped, because clamping invents a
    // plausible value at the edge of the range and presents it as observed.
    private val HR_RANGE       = 20..250
    private val PRV_RANGE      = 1..254
    private val SPO2_RANGE     = 50..100
    private val UBA_RANGE      = 1..5
    private val BATTERY_RANGE  = 0..100
    private val SKIN_TEMP_C    = 20f..45f
    private val AMBIENT_C      = -40f..85f
    private val HUMIDITY_PCT   = 0f..100f

    private const val U8_INVALID  = 0xFF
    private const val U16_INVALID = 0xFFFF
    private const val I16_INVALID = -0x8000

    /** Returns null for anything that is not a well-formed v2 status packet. */
    fun parseStatus(bytes: ByteArray): StatusPacket? {
        if (bytes.size != BandProtocol.PACKET_SIZE) return null
        if ((bytes[0].toInt() and 0xFF) != BandProtocol.HEADER_STATUS) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(1)

        val uptime      = buf.int.toUInt().toLong()
        val index       = buf.short.toInt() and 0xFFFF
        val rawHr       = buf.get().toInt() and 0xFF
        val rawPrv      = buf.get().toInt() and 0xFF
        val rawSpo2     = buf.get().toInt() and 0xFF
        val rawSkin     = buf.short.toInt()
        val rawAmbient  = buf.short.toInt()
        val rawHumidity = buf.get().toInt() and 0xFF
        val rawUba      = buf.get().toInt() and 0xFF
        val rawActivity = buf.short.toInt() and 0xFFFF
        val rawBattery  = buf.get().toInt() and 0xFF
        val quality     = buf.get().toInt() and 0xFF

        fun has(bit: Int) = (quality and bit) != 0

        return StatusPacket(
            bandUptimeMs  = uptime,
            sampleIndex   = index,
            // 0 means "no valid pulse" for HR; the u8 fields use 0xFF.
            hr            = rawHr.takeIf { has(QualityBits.HR) && it != 0 && it in HR_RANGE },
            // Band RMSSD stands or falls with the pulse it was computed from.
            prvRmssdMs    = rawPrv.takeIf { has(QualityBits.HR) && it != U8_INVALID && it in PRV_RANGE },
            spo2          = rawSpo2.takeIf { has(QualityBits.SPO2) && it != U8_INVALID && it in SPO2_RANGE },
            skinTempC     = centi(rawSkin).takeIf { has(QualityBits.SKIN_TEMP) }?.takeIf { it in SKIN_TEMP_C },
            ambientTempC  = centi(rawAmbient).takeIf { has(QualityBits.AMBIENT) }?.takeIf { it in AMBIENT_C },
            humidityPct   = rawHumidity.takeIf { has(QualityBits.AMBIENT) && it != U8_INVALID }
                ?.toFloat()?.takeIf { it in HUMIDITY_PCT },
            ensUba        = rawUba.takeIf { has(QualityBits.AQI) && it in UBA_RANGE },
            activityCount = rawActivity.takeIf { has(QualityBits.MOTION) && it != U16_INVALID },
            // Battery is device telemetry, not a sensor: no quality bit, only a range guard.
            batteryPct    = rawBattery.takeIf { it != U8_INVALID && it in BATTERY_RANGE },
            quality       = quality
        )
    }

    /** Returns null for anything that is not a well-formed v2 waveform packet. */
    fun parseWaveform(bytes: ByteArray): WaveformPacket? {
        if (bytes.size != BandProtocol.PACKET_SIZE) return null
        if ((bytes[0].toInt() and 0xFF) != BandProtocol.HEADER_WAVEFORM) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(1)

        val index = buf.short.toInt() and 0xFFFF
        val flags = buf.get().toInt() and 0xFF
        val ppg   = List(BandProtocol.PPG_SAMPLES_PER_PACKET) { buf.short.toInt() and 0xFFFF }
        // Signed bytes: Byte.toInt() sign-extends, which is what we want here.
        val accel = List(BandProtocol.ACCEL_SAMPLES_PER_PACKET * 3) { buf.get() * BandProtocol.ACCEL_G_PER_LSB }
        val rawHr = buf.get().toInt() and 0xFF
        val count = buf.get().toInt() and 0xFF

        return WaveformPacket(
            firstSampleIndex = index,
            flags            = flags,
            ppgIr            = ppg,
            accelG           = accel.takeIf { (flags and WaveformPacket.FLAG_ACCEL_VALID) != 0 },
            bandHr           = rawHr.takeIf { it != 0 && it in HR_RANGE },
            counter          = count
        )
    }

    /** Decodes the firmware v1 frame. Anything shorter than 24 bytes is rejected. */
    fun parseLegacy(bytes: ByteArray): LegacyFrameV1? {
        if (bytes.size < BandProtocol.LEGACY_FRAME_SIZE) return null
        val buf = ByteBuffer.wrap(bytes, 0, BandProtocol.LEGACY_FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        // uint32 via toUInt() so seq and uptime keep counting past 2^31 instead
        // of wrapping negative and looking like a clock that ran backwards.
        val seq         = buf.int.toUInt().toLong()
        val uptime      = buf.int.toUInt().toLong()
        val rawHr       = buf.short.toInt()
        val rawPrv      = buf.short.toInt()
        val rawSpo2     = buf.short.toInt()
        val rawSkin     = buf.short.toInt()
        val rawAmbient  = buf.short.toInt()
        val rawHumidity = buf.short.toInt()
        val rawAqi      = buf.short.toInt()
        val rawBattery  = buf.get().toInt() and 0xFF
        val quality     = buf.get().toInt() and 0xFF

        // v1 sends -1 for a reading it could not take.
        fun gated(raw: Int, bit: Int): Int? = if (raw == -1 || (quality and bit) == 0) null else raw

        return LegacyFrameV1(
            seq           = seq,
            bandUptimeMs  = uptime,
            hr            = gated(rawHr, QualityBits.HR)?.takeIf { it in HR_RANGE },
            prvRmssdMs    = gated(rawPrv, QualityBits.HR)?.takeIf { it in PRV_RANGE },
            spo2          = gated(rawSpo2, QualityBits.SPO2)?.takeIf { it in SPO2_RANGE },
            skinTempC     = gated(rawSkin, QualityBits.SKIN_TEMP)?.let { it / 100f }?.takeIf { it in SKIN_TEMP_C },
            ambientTempC  = gated(rawAmbient, QualityBits.AMBIENT)?.let { it / 100f }?.takeIf { it in AMBIENT_C },
            humidityPct   = gated(rawHumidity, QualityBits.AMBIENT)?.let { it / 100f }?.takeIf { it in HUMIDITY_PCT },
            ensUba        = gated(rawAqi, QualityBits.AQI)?.takeIf { it in UBA_RANGE },
            // v1 firmware reports a hard-coded 100 when it cannot read the battery,
            // so a v1 value is only trusted when it is not exactly that fallback.
            batteryPct    = rawBattery.takeIf { it in BATTERY_RANGE && it != V1_BATTERY_FALLBACK },
            quality       = quality
        )
    }

    /**
     * The v1 firmware's read_battery_pct() returns 100 from its exception
     * handler, so 100 cannot be told apart from "unknown". Dropping it costs a
     * genuinely full battery reading; showing it would claim a full battery
     * on a band whose battery pin is not wired.
     */
    const val V1_BATTERY_FALLBACK = 100

    private fun centi(raw: Int): Float? = if (raw == I16_INVALID) null else raw / 100f
}
