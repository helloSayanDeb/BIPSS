package com.bipss.app.sos

import org.junit.Assert.*
import org.junit.Test

class CountdownClockTest {
    @Test fun `number and arc share exact deadline boundaries including missed frames`() {
        val remaining = listOf(15000L, 14999L, 14000L, 7250L, 1L, 0L, -400L)
        assertEquals(listOf(15, 15, 14, 8, 1, 0, 0), remaining.map(CountdownClock::secondsLeft))
        val progress = remaining.map { CountdownClock.progress(it, 15000) }
        assertEquals(0f, progress.first(), 0f)
        assertEquals(1f / 15, progress[2], 0.0001f)
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1f, progress.last(), 0f)
    }
}
