package com.bipss.app.ui.metric

import com.bipss.app.data.db.dao.MetricBucket
import com.bipss.app.data.db.dao.MetricStats

/**
 * Pure helpers behind the history chart, kept free of Android so they are
 * unit-tested directly.
 *
 * History lives in two tables split by age: raw 1 Hz rows for the last seven
 * days, minute aggregates for older data (see AggregationWorker). The old
 * Week/Month views read only the aggregates, so Week - which lies entirely
 * inside the raw window - was always empty, and Month showed only days 8-30.
 * Both sources are now bucketed to the same grid and merged.
 */

/**
 * Merges bucket lists from both sources onto one timeline. The two only
 * overlap at the seam (a bucket straddling the raw/aggregate boundary), and a
 * shared bucket is combined sample-weighted rather than duplicated.
 */
fun mergeBuckets(a: List<MetricBucket>, b: List<MetricBucket>): List<MetricBucket> =
    (a + b).groupBy { it.bucketStartMs }
        .map { (start, parts) ->
            if (parts.size == 1) parts[0] else {
                val n = parts.sumOf { it.sampleCount }
                MetricBucket(
                    bucketStartMs = start,
                    avgValue      = parts.sumOf { it.avgValue * it.sampleCount } / n.coerceAtLeast(1),
                    minValue      = parts.minOf { it.minValue },
                    maxValue      = parts.maxOf { it.maxValue },
                    sampleCount   = n
                )
            }
        }
        .sortedBy { it.bucketStartMs }

/** Window summary from buckets: true min/max and the sample-weighted mean. Null fields when empty. */
fun combineStats(buckets: List<MetricBucket>): MetricStats {
    val n = buckets.sumOf { it.sampleCount }
    if (n == 0) return MetricStats(null, null, null, 0)
    return MetricStats(
        minValue    = buckets.minOf { it.minValue }.toFloat(),
        maxValue    = buckets.maxOf { it.maxValue }.toFloat(),
        avgValue    = (buckets.sumOf { it.avgValue * it.sampleCount } / n).toFloat(),
        sampleCount = n
    )
}

/** Plots each bucket at its midpoint; gaps are inserted afterwards by [insertGaps]. */
fun bucketsToPoints(buckets: List<MetricBucket>, bucketMs: Long): List<GraphPoint> =
    buckets.map { GraphPoint(it.bucketStartMs + bucketMs / 2, it.avgValue.toFloat()) }
