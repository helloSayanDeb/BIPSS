package com.bipss.app.data

import com.bipss.app.data.db.dao.MetricBucket
import com.bipss.app.data.db.dao.MinuteAggregateDao
import com.bipss.app.data.db.dao.RawReadingDao
import com.bipss.app.domain.workout.WorkMinute
import com.bipss.app.ui.metric.mergeBuckets

/**
 * Per-minute series from both history tables: raw rows for the last week,
 * minute averages for anything older. Everything that looks back more than a
 * day (daily summaries, workout detection, VO2 max) reads through here.
 */
class MinuteData(private val rawDao: RawReadingDao, private val aggDao: MinuteAggregateDao) {

    /** One metric, bucketed to the minute, keyed by the minute start. */
    suspend fun buckets(type: String, startMs: Long, endMs: Long): Map<Long, MetricBucket> {
        val last = endMs - 1
        return mergeBuckets(
            rawDao.getBuckets(type, startMs, last, MINUTE),
            aggDao.getBuckets(type, startMs / MINUTE, last / MINUTE, MINUTE)
        ).associateBy { it.bucketStartMs }
    }

    /** Sum of a counted metric (steps) in a minute: the mean times how many samples made it. */
    private fun MetricBucket.sum() = avgValue * sampleCount

    /**
     * The minutes of [startMs, endMs) that have a heart rate or movement, as
     * the workout detector reads them. Steps are null for a minute with none
     * counted, GPS speed null outside a recorded workout.
     */
    suspend fun workMinutes(startMs: Long, endMs: Long): List<WorkMinute> {
        val hr = buckets(MetricTypes.HR, startMs, endMs)
        val activity = buckets(MetricTypes.ACTIVITY, startMs, endMs)
        val steps = buckets(MetricTypes.STEPS, startMs, endMs)
        val gps = buckets(MetricTypes.GPS_SPEED, startMs, endMs)
        return (hr.keys + activity.keys).toSortedSet().map { m ->
            WorkMinute(
                startMs = m,
                hr = hr[m]?.avgValue,
                activityMgS = activity[m]?.let { it.avgValue * it.sampleCount },
                steps = steps[m]?.sum()?.toInt(),
                gpsSpeedMps = gps[m]?.avgValue
            )
        }
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
