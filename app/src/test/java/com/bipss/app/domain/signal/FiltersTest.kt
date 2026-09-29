package com.bipss.app.domain.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class FiltersTest {

    private fun rms(xs: List<Double>) = sqrt(xs.sumOf { it * it } / xs.size)

    /** Steady-state RMS gain of the band-pass for a sinusoid. */
    private fun gainAt(hz: Double): Double {
        val bp = BandPass()
        val n = 50 * 40
        val input = (0 until n).map { sin(2 * PI * hz * it / 50.0) }
        val out = input.map { bp.process(it) }
        return rms(out.drop(n / 2)) / rms(input.drop(n / 2))
    }

    @Test
    fun `band-pass keeps the pulse band and rejects what is outside it`() {
        assertEquals(1.0, gainAt(1.5), 0.1)     // 90 bpm
        assertEquals(1.0, gainAt(1.0), 0.15)    // 60 bpm
        assertTrue("0.1 Hz ${gainAt(0.1)}", gainAt(0.1) < 0.1)
        assertTrue("12 Hz ${gainAt(12.0)}", gainAt(12.0) < 0.15)
    }

    @Test
    fun `band-pass starts without a step transient from the DC level`() {
        val bp = BandPass()
        val out = (0 until 100).map { bp.process(30_000.0 + sin(2 * PI * 1.2 * it / 50.0) * 300) }
        assertTrue("max ${out.maxOf { kotlin.math.abs(it) }}", out.all { kotlin.math.abs(it) < 600 })
    }

    /**
     * Pulse at 1.3 Hz plus an artefact driven by a 1.7 Hz motion the reference
     * sees, with a delay and a non-linear term. After convergence the residual
     * artefact must be well below the unfiltered one.
     */
    @Test
    fun `NLMS removes most of an artefact it has a reference for, within seconds`() {
        val rng = Random(3)
        val nlms = NlmsFilter()
        val fs = 50.0
        val n = (fs * 60).toInt()
        val residual = ArrayList<Double>()
        val raw = ArrayList<Double>()
        val secondResidual = ArrayList<Double>()
        val secondRaw = ArrayList<Double>()
        var convergedAtS = -1
        for (k in 0 until n) {
            val t = k / fs
            val motion = 0.35 * sin(2 * PI * 1.7 * t)
            val lagged = 0.35 * sin(2 * PI * 1.7 * (t - 0.06))
            val artefact = 900 * (lagged + 0.4 * lagged * lagged) + rng.nextGaussian() * 5
            val pulse = 150 * sin(2 * PI * 1.3 * t)
            val refs = doubleArrayOf(motion, 0.3 * motion + rng.nextGaussian() * 0.005, rng.nextGaussian() * 0.005)
            val e = nlms.process(pulse + artefact, refs, adapt = true)
            secondResidual += e - pulse
            secondRaw += artefact
            if (secondResidual.size == fs.toInt()) {
                // First whole second in which less than a third of the artefact remains.
                if (convergedAtS < 0 && rms(secondResidual) < rms(secondRaw) / 3) convergedAtS = (k / fs).toInt()
                secondResidual.clear(); secondRaw.clear()
            }
            if (k > fs * 30) {
                residual += e - pulse
                raw += artefact
            }
        }
        val reduction = rms(residual) / rms(raw)
        assertTrue("artefact left: ${reduction * 100} %", reduction < 0.35)
        assertTrue("converged after $convergedAtS s", convergedAtS in 0..10)
    }

    @Test
    fun `frozen NLMS does not learn to cancel the pulse`() {
        val nlms = NlmsFilter()
        val rng = Random(1)
        var last = 0.0
        for (k in 0 until 3000) {
            val pulse = 150 * sin(2 * PI * 1.2 * k / 50.0)
            last = nlms.process(pulse, doubleArrayOf(rng.nextGaussian() * 0.005, rng.nextGaussian() * 0.005, rng.nextGaussian() * 0.005), adapt = false)
        }
        assertEquals(0.0, nlms.weightNorm(), 1e-12)
        assertTrue(kotlin.math.abs(last) <= 150.0)
    }

    /** A 4000-count step (about 11x the pulse) must not silence the detector for good. */
    @Test
    fun `detector recovers after one large transient`() {
        val bp = BandPass()
        val det = PpgPeakDetector()
        val beatsPer10s = IntArray(12)
        for (k in 0 until 50 * 120) {
            val t = k / 50.0
            val phase = (t * 1.1) % 1.0      // 66 bpm
            val pulse = 360 * kotlin.math.exp(-0.5 * ((phase - 0.15) / 0.055).let { it * it })
            val spike = if (t in 20.0..20.1) 4000.0 else 0.0
            val raw = 30_000 - pulse + spike
            for (b in det.process(k.toLong(), bp.process(raw), 20.0)) beatsPer10s[(b.position / 500).toInt()]++
        }
        // Detection is back to ~11 beats per 10 s well within half a minute of the spike.
        for (bucket in 5 until 12) assertTrue("bucket $bucket: ${beatsPer10s.toList()}", beatsPer10s[bucket] >= 9)
    }
}
