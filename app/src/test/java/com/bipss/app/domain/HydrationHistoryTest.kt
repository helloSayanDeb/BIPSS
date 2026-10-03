package com.bipss.app.domain

import com.bipss.app.data.db.entity.WaterLogEntity
import com.bipss.app.domain.time.LocalDay
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HydrationHistoryTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun time(s: String) = Instant.parse(s).toEpochMilli()

    @Test fun `history uses local midnight and fills missing days without carrying totals forward`() {
        val now = time("2026-09-30T00:00:00Z")
        val today = LocalDay.epochDay(now, zone)
        val entries = listOf(
            WaterLogEntity(timestampMs = time("2026-09-29T18:29:59Z"), amountMl = 500),
            WaterLogEntity(timestampMs = time("2026-09-29T18:30:00Z"), amountMl = 250),
            WaterLogEntity(timestampMs = time("2026-09-30T00:00:00Z"), amountMl = 250),
            WaterLogEntity(timestampMs = time("2026-09-01T00:00:00Z"), amountMl = 1000)
        )
        val history = waterHistory(entries, today, zone)
        assertEquals((today - 6..today).toList(), history.map { it.epochDay })
        assertEquals(listOf(0, 0, 0, 0, 0, 500, 500), history.map { it.totalMl })
    }

    @Test fun `empty history always contains seven empty days`() {
        assertEquals(List(7) { 0 }, waterHistory(emptyList(), 20000, zone).map { it.totalMl })
    }
}
