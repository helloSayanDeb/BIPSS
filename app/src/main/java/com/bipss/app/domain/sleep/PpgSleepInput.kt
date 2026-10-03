package com.bipss.app.domain.sleep

import com.bipss.app.ble.WaveformChunk
import kotlin.math.floor
import kotlin.math.sqrt

data class PpgEpoch(val startMs: Long, val samples: FloatArray, val valid: Boolean)

/** Linear interpolation on the model's 1024 samples / 30 seconds clock.
 * Never interpolate across missing contact, saturation, reconnects or >120 ms gaps.
 * Absolute epoch boundaries make recordings comparable and retain clock gaps.
 */
class PpgEpochBuilder {
    private var epochStart = Long.MIN_VALUE
    private var values = FloatArray(1024) { Float.NaN }
    private var previousTime = Double.NaN
    private var previousValue = 0f
    private var next = 0
    fun accept(chunk: WaveformChunk): List<PpgEpoch> {
        val first = chunk.firstSampleWallMs ?: return emptyList()
        if (chunk.samplePeriodMs !in 15.0..25.0) { previousTime = Double.NaN; return emptyList() }
        if (chunk.gapBefore || !chunk.skinContact || chunk.saturated) previousTime = Double.NaN
        val out = mutableListOf<PpgEpoch>()
        chunk.ppgIr.forEachIndexed { i, v ->
            val time = first + i * chunk.samplePeriodMs
            val start = floor(time / 30_000).toLong() * 30_000
            if (start != epochStart) {
                if (epochStart != Long.MIN_VALUE) {
                    // The endpoint sample belongs to the epoch that just ended.
                    while (next < 1024 && previousTime.isFinite() && chunk.skinContact && !chunk.saturated &&
                        time > previousTime && time - previousTime <= 120) {
                        val target = epochStart + (next + 1) * 30_000.0 / 1024
                        if (target > time) break
                        if (target >= previousTime) values[next] = previousValue +
                            ((v - previousValue) * (target - previousTime) / (time - previousTime)).toFloat()
                        next++
                    }
                    val finite = values.filter { it.isFinite() }
                    val valid = finite.size >= 1004 && (finite.maxOrNull() ?: 0f) - (finite.minOrNull() ?: 0f) > 1f
                    val mean = if (finite.isEmpty()) 0f else finite.average().toFloat()
                    out += PpgEpoch(epochStart, values.map { if (it.isFinite()) it else mean }.toFloatArray(), valid)
                }
                epochStart = start; values = FloatArray(1024) { Float.NaN }; next = 0
            }
            val usable = chunk.skinContact && !chunk.saturated && v in 1..262142
            if (usable && previousTime.isFinite() && time > previousTime && time - previousTime <= 120) {
                // Training resampler starts at (1 / rate), not at zero.
                while (next < 1024) {
                    val target = epochStart + (next + 1) * 30_000.0 / 1024
                    if (target > time) break
                    if (target >= previousTime) values[next] = previousValue +
                        ((v - previousValue) * (target - previousTime) / (time - previousTime)).toFloat()
                    next++
                }
            }
            previousTime = if (usable) time else Double.NaN; previousValue = v.toFloat()
        }
        return out
    }
}

object PpgSleepInput {
    /** Same whole-recording sample standard deviation as torch.std(correction=1). */
    fun normalize(input: FloatArray): FloatArray? {
        if (input.size < 2 || input.any { !it.isFinite() }) return null
        val mean = input.sumOf { it.toDouble() } / input.size
        val sd = sqrt(input.sumOf { (it - mean) * (it - mean) } / (input.size - 1))
        if (sd < 1e-6) return null
        return FloatArray(input.size) { ((input[it] - mean) / sd).toFloat() }
    }

    fun stage(logits: FloatArray, usable: Boolean): Int {
        if (!usable || logits.size != 4 || logits.any { !it.isFinite() }) return -1
        val top = logits.indices.maxBy { logits[it] }
        val sum = logits.sumOf { kotlin.math.exp((it - logits[top]).toDouble()) }
        // Confidence is a model score, not a calibrated probability of correctness.
        return if (1.0 / sum >= 0.55) top else -1
    }
}
