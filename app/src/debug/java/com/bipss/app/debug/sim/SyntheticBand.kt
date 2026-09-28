package com.bipss.app.debug.sim

import com.bipss.app.ble.protocol.BandProtocol
import com.bipss.app.ble.protocol.QualityBits
import com.bipss.app.ble.protocol.WaveformPacket
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Debug-only synthetic band. Nothing here ever reaches a release build.
 *
 * Produces the same 20-byte v2 packets the firmware sends, from a simple
 * physiological model, so the whole phone path - parser, clock, pipeline,
 * persistence, screens - can be exercised without hardware. Every value it
 * produces is simulated, and the app shows a persistent banner saying so
 * while simulation mode is on.
 *
 * Deliberately not "easy" data: beat intervals carry respiratory modulation
 * and noise, the walking artefact couples to the accelerometer non-linearly
 * with slowly varying gain plus noise the accelerometer cannot see, and the
 * PPG oscillator can be set off-nominal to exercise the fitted clock.
 */
enum class SimScenario(val key: String, val label: String, val defaultMinutes: Int) {
    CLEAN("clean", "Resting, clean signal", 30),
    WALKING("walking", "Walking with motion artefact", 20),
    DROPOUT("dropout", "Contact loss and dropped packets", 20),
    SITTING_ELEVATED_HR("sitting_elevated_hr", "Sitting still, heart rate raised", 30),
    OVERNIGHT("overnight", "Night: movement bouts and desaturations", 8 * 60);

    companion object {
        fun fromKey(key: String?): SimScenario? = entries.firstOrNull { it.key == key }
    }
}

/** What the simulated wearer is doing at one moment. */
data class SimState(
    val hrBpm: Double,
    val rmssdMs: Double,
    /** Step or movement frequency; 0 when still. */
    val cadenceHz: Double,
    val motionAmpG: Double,
    val spo2: Double,
    val skinTempC: Double,
    val contact: Boolean,
    /** Gravity in the band frame - changes when the wearer rolls over. */
    val gravity: DoubleArray
)

/** A true beat onset and the interval that led to it, for tests. */
data class TrueBeat(val onsetMs: Double, val ibiMs: Double)

/** One packet as it leaves the band. */
data class EmittedPacket(val isStatus: Boolean, val bytes: ByteArray, val bandUptimeMs: Long)

object ScenarioModel {

    private val FLAT = doubleArrayOf(0.0, 0.0, 1.0)
    private val ON_SIDE = doubleArrayOf(0.71, 0.0, 0.71)
    private val PALM_UP = doubleArrayOf(0.0, 0.2, -0.98)

    /** Night timeline, seconds from start. */
    private val NIGHT_BOUTS = listOf(1800, 7200, 12600, 18000, 23400).map { it.toDouble() }
    private val NIGHT_DESATS = listOf(5400.0, 7205.0, 7920.0, 11160.0, 16200.0, 18720.0, 24480.0)
    private const val NIGHT_LENGTH_S = 8 * 3600.0

    fun stateAt(scenario: SimScenario, t: Double): SimState = when (scenario) {
        SimScenario.CLEAN -> SimState(
            hrBpm = 62 + 2 * sin(2 * PI * t / 300), rmssdMs = 45.0,
            cadenceHz = 0.0, motionAmpG = 0.0, spo2 = 97.6, skinTempC = 33.6,
            contact = true, gravity = FLAT
        )
        SimScenario.WALKING -> {
            val walking = t >= 60
            val ramp = ((t - 60) / 60).coerceIn(0.0, 1.0)
            SimState(
                hrBpm = 70 + 32 * ramp + 2 * sin(2 * PI * t / 90), rmssdMs = 38 - 23 * ramp,
                cadenceHz = if (walking) 1.8 + 0.05 * sin(2 * PI * t / 40) else 0.0,
                motionAmpG = if (walking) 0.35 else 0.0,
                spo2 = 97.0, skinTempC = 33.2 - 0.3 * ramp, contact = true, gravity = PALM_UP
            )
        }
        SimScenario.DROPOUT -> SimState(
            hrBpm = 66 + 2 * sin(2 * PI * t / 240), rmssdMs = 40.0,
            cadenceHz = 0.0, motionAmpG = 0.0, spo2 = 97.2, skinTempC = 33.4,
            // 8 s off the skin every minute.
            contact = (t % 60) !in 30.0..38.0, gravity = FLAT
        )
        SimScenario.SITTING_ELEVATED_HR -> SimState(
            hrBpm = 96 + 2 * sin(2 * PI * t / 200), rmssdMs = 16.0,
            cadenceHz = 0.0, motionAmpG = 0.0, spo2 = 97.4, skinTempC = 33.9,
            contact = true, gravity = FLAT
        )
        SimScenario.OVERNIGHT -> night(t)
    }

    private fun night(t: Double): SimState {
        val settling = t < 1200
        val waking = t > NIGHT_LENGTH_S - 600
        val inBout = NIGHT_BOUTS.any { t in it..(it + 40) }
        // Settling and waking: brief fidgets every two minutes.
        val fidget = (settling || waking) && (t % 120) < 10
        val moving = inBout || fidget

        val boutsSoFar = NIGHT_BOUTS.count { t > it + 40 }
        val gravity = if (boutsSoFar % 2 == 0) FLAT else ON_SIDE

        val hr = 52 + 10 * exp(-t / 3600) + 3 * sin(2 * PI * t / 5400) + (if (moving) 12.0 else 0.0)
        return SimState(
            hrBpm = hr, rmssdMs = 55 + 10 * sin(2 * PI * t / 5400),
            cadenceHz = if (moving) 0.7 else 0.0, motionAmpG = if (inBout) 0.25 else if (fidget) 0.12 else 0.0,
            spo2 = 97.2 - NIGHT_DESATS.sumOf { desatDepth(t - it) },
            skinTempC = 33.0 + 0.8 * (1 - exp(-t / 3600)),
            contact = true, gravity = gravity
        )
    }

    /** 15 s fall, 20 s hold, 15 s recovery; depth varies by event. */
    private fun desatDepth(dt: Double): Double {
        val depth = 5.0
        return when {
            dt < 0 || dt > 50 -> 0.0
            dt < 15 -> depth * dt / 15
            dt < 35 -> depth
            else -> depth * (50 - dt) / 15
        }
    }
}

/**
 * Steps a scenario forward one waveform packet (80 ms) at a time.
 *
 * @param periodScale PPG oscillator error: 1.02 makes the sensor 2 % slow.
 */
class SyntheticBand(
    val scenario: SimScenario,
    seed: Long = 1L,
    private val periodScale: Double = 1.0,
    private val startUptimeMs: Long = 5_000L,
    private val recordTruth: Boolean = false
) {
    private val rng = Random(seed)
    private val periodMs = BandProtocol.NOMINAL_PPG_PERIOD_MS * periodScale

    /** Next PPG sample index to produce. */
    var sampleIndex: Long = 0L
        private set
    private var counter = 0
    private var nextStatusUptime = startUptimeMs + 1000L

    // Beat model.
    private val beats = ArrayDeque<TrueBeat>()
    private var nextOnsetMs = 300.0
    private val recentIbis = ArrayDeque<Double>()
    val truth = mutableListOf<TrueBeat>()

    // Activity counter, mirroring the firmware.
    private val hpPrevIn = DoubleArray(3)
    private val hpPrevOut = DoubleArray(3)
    private var hpPrimed = false
    private var activityAcc = 0.0

    private var contactSinceMs = 0.0
    private var lastContact = true
    private var pendingOverflow = false

    val elapsedSeconds: Double get() = sampleIndex * periodMs / 1000.0

    fun step(): List<EmittedPacket> {
        val out = ArrayList<EmittedPacket>(2)

        if (scenario == SimScenario.DROPOUT && sampleIndex > 0 && (sampleIndex % 2250L) < 4) {
            // Every ~45 s the band's FIFO overflows and 12 samples are lost.
            sampleIndex += 12
            pendingOverflow = true
        }

        val first = sampleIndex
        val ppg = IntArray(BandProtocol.PPG_SAMPLES_PER_PACKET)
        val accel = FloatArray(BandProtocol.ACCEL_SAMPLES_PER_PACKET * 3)
        var flags = WaveformPacket.FLAG_ACCEL_VALID
        var anyContact = false

        for (i in 0 until BandProtocol.PPG_SAMPLES_PER_PACKET) {
            val tMs = sampleIndex * periodMs
            val state = ScenarioModel.stateAt(scenario, tMs / 1000.0)
            advanceBeats(tMs, state)
            val a = accelAt(tMs, state)
            if (i % 2 == 0) {
                val j = (i / 2) * 3
                accel[j] = a[0].toFloat(); accel[j + 1] = a[1].toFloat(); accel[j + 2] = a[2].toFloat()
                addActivity(a, 2 * periodMs / 1000.0)
            }
            ppg[i] = ppgAt(tMs, state, a)
            if (state.contact) anyContact = true
            if (state.contact != lastContact) { lastContact = state.contact; contactSinceMs = tMs }
            sampleIndex++
        }
        if (anyContact) flags = flags or WaveformPacket.FLAG_CONTACT
        if (pendingOverflow) { flags = flags or WaveformPacket.FLAG_FIFO_OVERFLOW; pendingOverflow = false }

        val packetEndUptime = startUptimeMs + (sampleIndex * periodMs).toLong()
        val dropped = scenario == SimScenario.DROPOUT && rng.nextDouble() < 0.03
        if (!dropped) {
            out += EmittedPacket(
                isStatus = false,
                bytes = BandPacketEncoder.waveform(first, flags, ppg, accel, bandHr(), counter),
                bandUptimeMs = packetEndUptime
            )
        }
        counter = (counter + 1) and 0xFF

        while (packetEndUptime >= nextStatusUptime) {
            out += EmittedPacket(isStatus = true, bytes = status(nextStatusUptime), bandUptimeMs = nextStatusUptime)
            nextStatusUptime += 1000L
        }
        return out
    }

    private fun advanceBeats(tMs: Double, s: SimState) {
        while (tMs >= nextOnsetMs) {
            val meanIbi = 60_000.0 / s.hrBpm
            // Respiratory sinus arrhythmia plus white noise, both scaled so the
            // interval series has roughly the scenario's RMSSD.
            val rsa = 0.5 * s.rmssdMs * sin(2 * PI * 0.25 * nextOnsetMs / 1000.0)
            val ibi = max(300.0, meanIbi + rsa + rng.nextGaussian() * s.rmssdMs * 0.55)
            val beat = TrueBeat(nextOnsetMs, ibi)
            beats.addLast(beat)
            while (beats.size > 4) beats.removeFirst()
            if (s.contact) {
                recentIbis.addLast(ibi)
                while (recentIbis.size > 12) recentIbis.removeFirst()
                if (recordTruth) truth += beat
            } else {
                recentIbis.clear()
            }
            nextOnsetMs += ibi
        }
    }

    private fun accelAt(tMs: Double, s: SimState): DoubleArray {
        val t = tMs / 1000.0
        val a = s.motionAmpG
        val c = s.cadenceHz
        val v = if (a > 0) a * (sin(2 * PI * c * t) + 0.3 * sin(4 * PI * c * t + 0.7)) else 0.0
        val lat = if (a > 0) 0.4 * a * sin(PI * c * t + 0.3) else 0.0
        val fwd = if (a > 0) 0.2 * a * sin(2 * PI * c * t + 1.1) else 0.0
        return doubleArrayOf(
            s.gravity[0] + lat + rng.nextGaussian() * 0.008,
            s.gravity[1] + fwd + rng.nextGaussian() * 0.008,
            s.gravity[2] + v + rng.nextGaussian() * 0.008
        )
    }

    private fun ppgAt(tMs: Double, s: SimState, a: DoubleArray): Int {
        if (!s.contact) return ((3_000 + rng.nextGaussian() * 200) / 4).roundToInt()
        val t = tMs / 1000.0
        val dc = 120_000.0 * (1 + 0.004 * sin(2 * PI * 0.25 * t))
        var pulse = 0.0
        for (b in beats) {
            val tau = (tMs - b.onsetMs) / 1000.0
            if (tau >= 0) pulse += template(tau)
        }
        // Motion artefact: non-linear in the vertical axis, with gains that
        // drift over tens of seconds, plus a lateral term.
        val vert = a[2] - s.gravity[2]
        val lat = a[0] - s.gravity[0]
        val k1 = 1500 * (1 + 0.35 * sin(2 * PI * t / 17))
        val k2 = 600 * (1 + 0.5 * sin(2 * PI * t / 23 + 1))
        val artefact = if (s.motionAmpG > 0) k1 * (vert + 0.5 * vert * vert) + k2 * lat else 0.0
        val raw18 = dc - 1_440 * pulse - artefact + rng.nextGaussian() * 40
        return (raw18.coerceIn(0.0, 262_143.0) / 4).roundToInt()
    }

    /** Pulse shape in seconds from onset: systolic wave plus a smaller dicrotic wave. */
    private fun template(tau: Double): Double {
        if (tau > 1.5) return 0.0
        val sys = exp(-0.5 * ((tau - 0.15) / 0.055).let { it * it })
        val dic = 0.4 * exp(-0.5 * ((tau - 0.40) / 0.09).let { it * it })
        return sys + dic
    }

    private fun addActivity(a: DoubleArray, dtS: Double) {
        val mg = DoubleArray(3) { a[it] * 1000 }
        if (!hpPrimed) { mg.copyInto(hpPrevIn); hpPrimed = true; return }
        var mag2 = 0.0
        for (i in 0..2) {
            val o = 0.94 * (hpPrevOut[i] + mg[i] - hpPrevIn[i])
            hpPrevIn[i] = mg[i]; hpPrevOut[i] = o
            mag2 += o * o
        }
        val m = sqrt(mag2) - 10.0
        if (m > 0) activityAcc += m * dtS
    }

    private fun bandHr(): Int? {
        if (recentIbis.size < 4) return null
        return (60_000.0 / recentIbis.average()).roundToInt()
    }

    private fun status(uptime: Long): ByteArray {
        val t = (uptime - startUptimeMs) / 1000.0
        val s = ScenarioModel.stateAt(scenario, t)
        val newest = floor((uptime - startUptimeMs) / periodMs).toLong()
        val settled = s.contact && (t * 1000 - contactSinceMs) > 3000 && recentIbis.size >= 4
        val hr = if (settled) bandHr() else null
        val prv = if (settled) rmssd(recentIbis) else null
        val spo2 = if (settled) (s.spo2 + rng.nextGaussian() * 0.4).roundToInt().coerceIn(70, 100) else null
        val activity = (activityAcc + 0.5).toInt(); activityAcc = 0.0
        val battery = (85 - t / 1200).roundToInt().coerceIn(1, 100)

        var q = QualityBits.SKIN_TEMP or QualityBits.AMBIENT or QualityBits.AQI or
            QualityBits.MOTION or QualityBits.WAVEFORM
        if (hr != null) q = q or QualityBits.HR
        if (spo2 != null) q = q or QualityBits.SPO2

        return BandPacketEncoder.status(
            uptimeMs = uptime, sampleIndex = newest,
            hr = hr, prvRmssdMs = prv, spo2 = spo2,
            skinTempC = s.skinTempC + rng.nextGaussian() * 0.02,
            ambientTempC = if (scenario == SimScenario.OVERNIGHT) 26.0 else 27.5 + 0.5 * sin(t / 7200),
            humidityPct = 58.0,
            ensUba = if (scenario == SimScenario.OVERNIGHT) 1 else 2,
            activityCount = activity, batteryPct = battery, quality = q
        )
    }

    private fun rmssd(ibis: Collection<Double>): Int? {
        if (ibis.size < 3) return null
        val l = ibis.toList()
        val sq = (1 until l.size).map { (l[it] - l[it - 1]).let { d -> d * d } }
        return sqrt(sq.average()).roundToInt().takeIf { it in 1..254 }
    }
}
