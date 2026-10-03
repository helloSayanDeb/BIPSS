package com.bipss.app.ai

import com.bipss.app.ble.PulseSource
import com.bipss.app.ble.SensorReading

data class CoachVitals(val heartRate: Int? = null, val spO2: Int? = null, val skinTempC: Float? = null)

/** Each value expires independently: a temperature packet cannot keep an old pulse alive. */
class CoachReadings {
    private var hr: Pair<Int, Long>? = null
    private var oxygen: Pair<Int, Long>? = null
    private var temperature: Pair<Float, Long>? = null

    fun accept(reading: SensorReading) {
        reading.heartRate?.let { hr = it to reading.timestamp }
        if (reading.pulseSource == PulseSource.PIPELINE && reading.heartRate == null) hr = null
        reading.spO2?.let { oxygen = it to reading.timestamp }
        reading.skinTempC?.let { temperature = it to reading.timestamp }
    }

    fun snapshot(nowMs: Long, connected: Boolean): CoachVitals {
        if (!connected) return CoachVitals()
        fun <T> Pair<T, Long>?.fresh(): T? = this?.takeIf { nowMs - it.second in 0..15_000L }?.first
        return CoachVitals(hr.fresh(), oxygen.fresh(), temperature.fresh())
    }
}
