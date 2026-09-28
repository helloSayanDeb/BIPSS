package com.bipss.app.ui.metric

import com.bipss.app.data.db.dao.MetricBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartDataTest {

    private fun b(start: Long, avg: Double, min: Double, max: Double, n: Int) = MetricBucket(start, avg, min, max, n)

    @Test
    fun `week view gets raw buckets even with no aggregates`() {
        // The old code read only aggregates, which never exist for the last 7 days.
        val merged = mergeBuckets(listOf(b(0, 70.0, 60.0, 80.0, 60), b(300_000, 72.0, 65.0, 90.0, 60)), emptyList())
        assertEquals(2, merged.size)
    }

    @Test
    fun `sources are interleaved in time order`() {
        val merged = mergeBuckets(listOf(b(600_000, 1.0, 1.0, 1.0, 1)), listOf(b(0, 2.0, 2.0, 2.0, 1), b(300_000, 3.0, 3.0, 3.0, 1)))
        assertEquals(listOf(0L, 300_000L, 600_000L), merged.map { it.bucketStartMs })
    }

    @Test
    fun `seam bucket is combined sample-weighted`() {
        val merged = mergeBuckets(listOf(b(0, 60.0, 55.0, 70.0, 30)), listOf(b(0, 90.0, 85.0, 100.0, 10)))
        assertEquals(1, merged.size)
        assertEquals((60.0 * 30 + 90.0 * 10) / 40, merged[0].avgValue, 1e-9)
        assertEquals(55.0, merged[0].minValue, 0.0)
        assertEquals(100.0, merged[0].maxValue, 0.0)
        assertEquals(40, merged[0].sampleCount)
    }

    @Test
    fun `stats are sample-weighted, not an average of averages`() {
        val stats = combineStats(listOf(b(0, 60.0, 50.0, 70.0, 1), b(1, 100.0, 90.0, 110.0, 99)))
        assertEquals(99.6f, stats.avgValue!!, 1e-4f)
        assertEquals(50f, stats.minValue)
        assertEquals(110f, stats.maxValue)
        assertEquals(100, stats.sampleCount)
    }

    @Test
    fun `empty window has null stats rather than zeros`() {
        val stats = combineStats(emptyList())
        assertNull(stats.avgValue); assertNull(stats.minValue); assertEquals(0, stats.sampleCount)
    }
}
