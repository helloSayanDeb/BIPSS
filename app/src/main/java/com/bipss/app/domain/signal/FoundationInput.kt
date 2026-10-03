package com.bipss.app.domain.signal

import kotlin.math.*

/** PaPaGei preparation for the archive's 1024/30 Hz clock: pyPPG 1.0.41
 * Chebyshev-II (4, 20 dB, 0.5–12 Hz), scipy filtfilt odd padding, Fourier
 * resample to 125 Hz, central 10 s, per-window population z-score.
 * No new frequency content is recovered by upsampling the band's waveform.
 */
object FoundationInput {
    private val b = doubleArrayOf(.342819588004421,-.2932096507043551,-.7391852444233145,.1476983203699795,1.0837947558330374,.14769832036997949,-.7391852444233145,-.29320965070435506,.34281958800442097)
    private val a = doubleArrayOf(1.0,-2.27629542235065,1.095697652610594,-.038176481999424805,1.033441934730541,-.6508564670114231,-.3370614926346176,.0551017646739799,.11855633524599749)
    private val zi = doubleArrayOf(-.24281958800520365,-.177239479534132,.6715155301493843,.5199995615794921,-.4604510007813001,-.6732349678519125,.032244127308204085,.330963954479914)
    fun prepare(epoch: FloatArray): FloatArray? {
        if (epoch.size != 1024 || epoch.any { !it.isFinite() } || epoch.max() - epoch.min() <= 1f) return null
        val pad = 27
        val extended = DoubleArray(1024 + pad * 2) { i -> when {
            i < pad -> 2.0 * epoch[0] - epoch[pad - i]
            i >= pad + 1024 -> 2.0 * epoch.last() - epoch[1022 - (i - pad - 1024)]
            else -> epoch[i - pad].toDouble()
        } }
        fun filter(x: DoubleArray): DoubleArray {
            val state = DoubleArray(8) { zi[it] * x[0] }
            return DoubleArray(x.size) { i ->
                val y = b[0] * x[i] + state[0]
                for (j in 0..6) state[j] = b[j + 1] * x[i] - a[j + 1] * y + state[j + 1]
                state[7] = b[8] * x[i] - a[8] * y
                y
            }
        }
        val filtered = filter(filter(extended).reversedArray()).reversedArray().copyOfRange(pad, pad + 1024)
        val real = filtered.copyOf(); val imag = DoubleArray(1024)
        // Radix-2 FFT, unscaled forward transform.
        var j = 0
        for (i in 1 until 1024) {
            var bit = 512
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val temp = real[i]; real[i] = real[j]; real[j] = temp }
        }
        var length = 2
        while (length <= 1024) {
            val angle = -2 * PI / length
            for (start in 0 until 1024 step length) {
                for (k in 0 until length / 2) {
                    val c = cos(angle * k); val s = sin(angle * k)
                    val even = start + k; val odd = even + length / 2
                    val re = real[odd] * c - imag[odd] * s
                    val im = real[odd] * s + imag[odd] * c
                    real[odd] = real[even] - re; imag[odd] = imag[even] - im
                    real[even] += re; imag[even] += im
                }
            }
            length *= 2
        }
        // Evaluate only the central 10 seconds of the 3750-sample inverse.
        val window = DoubleArray(1250) { real[0] / 1024 }
        for (k in 1..512) {
            val step = 2 * PI * k / 3750
            val cStep = cos(step); val sStep = sin(step)
            var c = cos(step * 1250); var s = sin(step * 1250)
            val scale = (if (k == 512) 1.0 else 2.0) / 1024
            for (i in window.indices) {
                window[i] += scale * (real[k] * c - imag[k] * s)
                val nextC = c * cStep - s * sStep
                s = s * cStep + c * sStep; c = nextC
            }
        }
        val mean = window.average()
        val sd = sqrt(window.sumOf { (it - mean).pow(2) } / window.size)
        if (!sd.isFinite() || sd < 1e-6) return null
        return FloatArray(1250) { ((window[it] - mean) / sd).toFloat() }
    }
}
