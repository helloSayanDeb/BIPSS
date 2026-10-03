package com.bipss.app.ai

import com.bipss.app.ble.PulseSource
import com.bipss.app.ble.SensorReading
import org.junit.Assert.*
import org.junit.Test

class CoachReadingsTest {
    @Test fun `readings expire instead of becoming permanent current vitals`() {
        val readings = CoachReadings()
        readings.accept(SensorReading(heartRate = 72, spO2 = 98, timestamp = 100_000))
        assertEquals(72, readings.snapshot(105_000, true).heartRate)
        assertNull(readings.snapshot(116_000, true).heartRate)
        assertNull(readings.snapshot(99_000, true).heartRate)
        assertEquals(CoachVitals(), readings.snapshot(105_000, false))
    }

    @Test fun `temperature packets cannot renew an old heart rate`() {
        val readings = CoachReadings()
        readings.accept(SensorReading(heartRate = 72, timestamp = 100_000))
        readings.accept(SensorReading(skinTempC = 33f, timestamp = 120_000))
        val now = readings.snapshot(120_000, true)
        assertNull(now.heartRate)
        assertEquals(33f, now.skinTempC)
    }

    @Test fun `poor signal clears pipeline pulse immediately`() {
        val readings = CoachReadings()
        readings.accept(SensorReading(heartRate = 72, pulseSource = PulseSource.PIPELINE, timestamp = 100_000))
        readings.accept(SensorReading(pulseSource = PulseSource.PIPELINE, timestamp = 101_000))
        assertNull(readings.snapshot(101_000, true).heartRate)
    }
}
