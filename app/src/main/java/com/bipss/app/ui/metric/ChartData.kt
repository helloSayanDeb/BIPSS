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

/**
 * Labels for the time axis of [range] ending at [endMs]: clock times every 6 h
 * for a day, weekdays every other day for a week, dates weekly for a month,
 * each on a local boundary, and "Now" at the right edge. Ticks that would
 * crowd "Now" are dropped.
 */
fun timeTicks(range: TimeRange, endMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<Pair<Long, String>> {
    val startMs = endMs - range.durationMs
    val start = java.time.Instant.ofEpochMilli(startMs).atZone(zone)
    if (range == TimeRange.LIVE) {
        // A tick each minute back from now: "4 min", "3 min", ... "Now".
        return (4 downTo 1).map { m -> (endMs - m * 60_000L) to "$m min" } + (endMs to "Now")
    }
    val (first, step, pattern, clearanceMs) = when (range) {
        TimeRange.LIVE -> error("handled above")
        TimeRange.DAY -> {
            val h = start.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            val next = h.plusHours(((6 - h.hour % 6) % 6).toLong()).let { if (it.toInstant().toEpochMilli() <= startMs) it.plusHours(6) else it }
            Quad(next, java.time.Duration.ofHours(6), "HH:mm", 2 * 3_600_000L)
        }
        TimeRange.WEEK -> Quad(start.toLocalDate().plusDays(1).atStartOfDay(zone), java.time.Duration.ofDays(2), "EEE", 18 * 3_600_000L)
        TimeRange.MONTH -> Quad(start.toLocalDate().plusDays(1).atStartOfDay(zone), java.time.Duration.ofDays(7), "d MMM", 3 * 86_400_000L)
    }
    val fmt = java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.ENGLISH)
    val out = mutableListOf<Pair<Long, String>>()
    var t = first
    while (t.toInstant().toEpochMilli() < endMs - clearanceMs) {
        out += t.toInstant().toEpochMilli() to fmt.format(t)
        t = t.plus(step)
    }
    out += endMs to "Now"
    return out
}

private data class Quad(val first: java.time.ZonedDateTime, val step: java.time.Duration, val pattern: String, val clearanceMs: Long)
