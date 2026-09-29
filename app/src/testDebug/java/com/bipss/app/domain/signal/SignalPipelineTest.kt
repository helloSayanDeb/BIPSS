package com.bipss.app.domain.signal

import com.bipss.app.ble.BandClock
import com.bipss.app.ble.WaveformAssembler
import com.bipss.app.ble.protocol.BandPacketParser
import com.bipss.app.debug.sim.ScenarioModel
import com.bipss.app.debug.sim.SimScenario
import com.bipss.app.debug.sim.SyntheticBand
import com.bipss.app.debug.sim.TrueBeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The pipeline against ground truth. Every test runs the simulator's bytes
 * through the production parser, clock and assembler first, so what is tested
 * is the whole phone path, not the algorithm in isolation.
 */
class SignalPipelineTest {

    private class Run(
        val pulse: List<PulseReading>,
        val epochs: List<SignalEpoch>,
        val beats: List<Long>,
        val truth: List<TrueBeat>,
        /** wall ms = sensor ms + this, for the simulated clock mapping */
        val wallOffsetMs: Long
    )

    private fun run(band: SyntheticBand, seconds: Int, pipeline: SignalPipeline = SignalPipeline()): Run {
        val clock = BandClock()
        val asm = WaveformAssembler(clock)
        val pulse = ArrayList<PulseReading>()
        val epochs = ArrayList<SignalEpoch>()
        val wall0 = 1_700_000_000_000L
        while (band.elapsedSeconds < seconds) {
            for (p in band.step()) {
                val arrival = wall0 + p.bandUptimeMs + 20
                if (p.isStatus) {
                    val s = BandPacketParser.parseStatus(p.bytes)!!
                    clock.onStatus(s.bandUptimeMs, s.sampleIndex, arrival)
                } else {
                    val chunk = asm.accept(BandPacketParser.parseWaveform(p.bytes)!!) ?: continue
                    val out = pipeline.accept(chunk)
                    pulse += out.pulse
                    epochs += out.epochs
                }
            }
        }
        return Run(pulse, epochs, pipeline.beatLog.toList(), band.truth.toList(), wall0 + 20 + 5_000)
    }

    /** True HR at a wall time: mean interval of true beats in the preceding 5 s, a typical display average. */
    private fun trueHr(r: Run, wallMs: Long): Double? {
        val sensorMs = (wallMs - r.wallOffsetMs).toDouble()
        val ibis = r.truth.filter { it.onsetMs in (sensorMs - 5_000)..sensorMs }.map { it.ibiMs }
        return if (ibis.size < 2) null else 60_000.0 / ibis.average()
    }

    private fun f1(r: Run, fromS: Int): Double {
        // The fiducial is the steepest upstroke point, a fixed lag after the
        // simulated onset; estimate that lag as the median nearest offset.
        val truth = r.truth.map { it.onsetMs + r.wallOffsetMs }.filter { it > r.wallOffsetMs + fromS * 1000 }
        val beats = r.beats.filter { it > r.wallOffsetMs + fromS * 1000 && it < (truth.lastOrNull() ?: 0.0) + 500 }
        val offsets = beats.map { b -> truth.minBy { abs(it - b) }.let { b - it } }.sorted()
        val lag = offsets[offsets.size / 2]
        val used = HashSet<Int>()
        var tp = 0
        for (b in beats) {
            val i = truth.indices.filter { it !in used }.minByOrNull { abs(truth[it] + lag - b) } ?: continue
            if (abs(truth[i] + lag - b) <= 60) { used += i; tp++ }
        }
        val fp = beats.size - tp
        val fn = truth.size - tp
        return 2.0 * tp / (2.0 * tp + fp + fn)
    }

    private fun trueRmssd(r: Run, endWall: Long): Double {
        val end = (endWall - r.wallOffsetMs).toDouble()
        val ibis = r.truth.filter { it.onsetMs in (end - 60_000)..end }.map { it.ibiMs }
        val d = (1 until ibis.size).map { ibis[it] - ibis[it - 1] }
        return sqrt(d.map { it * it }.average())
    }

    @Test
    fun `clean resting signal - beats, heart rate and PRV match the truth`() {
        val r = run(SyntheticBand(SimScenario.CLEAN, seed = 21, recordTruth = true), 300)

        assertTrue("F1 ${f1(r, 10)}", f1(r, 10) >= 0.95)

        val measured = r.pulse.filter { it.isMeasured && it.timeMs > r.wallOffsetMs + 20_000 }
        assertTrue("measured seconds ${measured.size}", measured.size > 250)
        val errors = measured.mapNotNull { p -> trueHr(r, p.timeMs)?.let { abs(it - p.bpm) } }
        val within = errors.count { it <= 2.0 }.toDouble() / errors.size
        assertTrue("HR within 2 bpm for ${within * 100}% of seconds", within >= 0.95)

        val prvEpochs = r.epochs.filter { it.rmssdMs != null && it.startMs > r.wallOffsetMs + 60_000 }
        // Five minutes of clean signal: every epoch after the first minute should carry PRV.
        assertTrue("epochs with PRV: ${prvEpochs.size}", prvEpochs.size >= 6)
        for (e in prvEpochs) {
            val truth = trueRmssd(r, e.startMs + e.durationMs)
            assertEquals("RMSSD at ${e.startMs}", truth, e.rmssdMs!!, 5.0)
        }
        assertTrue(r.epochs.drop(1).all { it.quality == SignalQuality.GOOD })
    }

    @Test
    fun `motion cancellation beats the unfiltered signal while walking`() {
        // The scenario's brisk walk: steps ~1.55 Hz, pulse ~118 bpm - overlapping
        // bands, separable only with the accelerometer reference.
        val withNlms = run(SyntheticBand(SimScenario.WALKING, seed = 5, recordTruth = true), 240)
        val without = run(
            SyntheticBand(SimScenario.WALKING, seed = 5, recordTruth = true), 240,
            SignalPipeline(cancelMotion = false)
        )
        val fWith = f1(withNlms, 120)
        val fWithout = f1(without, 120)
        assertTrue("with NLMS $fWith vs without $fWithout", fWith > fWithout)
        assertTrue("with NLMS F1 $fWith", fWith >= 0.9)
    }

    @Test
    fun `step rate equal to pulse rate is flagged degraded`() {
        val model = { t: Double ->
            ScenarioModel.stateAt(SimScenario.WALKING, t).let { s ->
                if (t < 60) s else s.copy(hrBpm = 108.0, cadenceHz = 1.8)
            }
        }
        val r = run(SyntheticBand(SimScenario.WALKING, seed = 9, recordTruth = true, model = model), 180)
        val walking = r.epochs.filter { it.startMs > r.wallOffsetMs + 90_000 }
        assertTrue(walking.isNotEmpty())
        assertTrue(walking.map { it.quality }.toString(), walking.all { it.quality != SignalQuality.GOOD })
    }

    @Test
    fun `no interval spans lost signal and off-skin seconds are never measured`() {
        val band = SyntheticBand(SimScenario.DROPOUT, seed = 13, recordTruth = true)
        val r = run(band, 180)
        // Off the skin for seconds 30-38 of every minute (sensor time).
        val offSkin = r.pulse.filter { p ->
            val s = ((p.timeMs - r.wallOffsetMs) / 1000) % 60
            s in 31..37
        }
        assertTrue("measured while off skin: $offSkin", offSkin.none { it.isMeasured })
        // Epochs containing a contact loss are not rated GOOD.
        val lossEpochs = r.epochs.filter { e ->
            val s0 = (e.startMs - r.wallOffsetMs) / 1000
            (s0 until s0 + 30).any { it % 60 in 30..38 }
        }
        assertTrue(lossEpochs.isNotEmpty())
        assertTrue(lossEpochs.map { it.quality }.toString(), lossEpochs.none { it.quality == SignalQuality.GOOD })
        // And measurement resumes after every contact loss - a pipeline that
        // stopped measuring altogether would pass the two checks above.
        for (minute in 0 until 3) {
            val resumed = r.pulse.any { p ->
                val s = (p.timeMs - r.wallOffsetMs) / 1000
                p.isMeasured && s in (minute * 60 + 45)..(minute * 60 + 58)
            }
            assertTrue("no measured HR after contact returned in minute $minute", resumed)
        }
    }

    @Test
    fun `after movement stops, still epochs are clean and PRV matches the truth`() {
        // The night starts with fidgets every two minutes for 20 minutes, then lies still.
        val r = run(SyntheticBand(SimScenario.OVERNIGHT, seed = 7, recordTruth = true), 1_500)
        val still = r.epochs.filter { it.startMs > r.wallOffsetMs + 1_260_000 }
        assertTrue(still.isNotEmpty())
        assertTrue(still.map { it.quality }.toString(), still.all { it.quality == SignalQuality.GOOD })
        val withPrv = still.filter { it.rmssdMs != null && !it.prvLowConfidence }
        assertTrue("still epochs with PRV: ${withPrv.size}", withPrv.size >= still.size - 1)
        for (e in withPrv) {
            val truth = trueRmssd(r, e.startMs + e.durationMs)
            assertEquals("RMSSD at ${e.startMs}", truth, e.rmssdMs!!, 8.0)
        }
    }

    @Test
    fun `PRV uses only real successive pairs`() {
        val ibis = (0 until 60).map { Ibi(it * 1000L, if (it == 30) 1600.0 else 1000.0 + (it % 2) * 40, 0) }
        val r = PrvCalculator.compute(ibis)
        // Alternating 1000/1040 gives successive differences of 40 ms; the
        // artefact at 30 is dropped with both of its pairs rather than
        // contributing a 560-600 ms difference.
        assertEquals(40.0, r.rmssdMs!!, 0.01)
        assertEquals(1.0 / 60, r.interpolatedFraction, 1e-9)
        assertEquals(57, r.rmssdPairs)
    }
}
