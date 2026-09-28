package com.bipss.app.domain.time

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The one definition of "a day" in this app: the device's local calendar day,
 * from local midnight to local midnight.
 *
 * Day bucketing goes through here - so far the water totals and "yesterday";
 * baseline day counts and cycle days are to move onto it as they are
 * rebuilt. The bug this replaces divided epoch
 * milliseconds by 86 400 000, which is the UTC day - so in India the daily
 * water total reset at 05:30 in the morning, and a drink at 23:00 and another
 * at 04:00 were counted as the same day.
 *
 * Pure `java.time` (available from minSdk 26), zone passed explicitly so it can
 * be tested against a fixed zone.
 */
object LocalDay {

    /** Local calendar day containing [ms], as an epoch-day number. */
    fun epochDay(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()

    /** Local midnight at the start of [epochDay]. DST-safe (uses the zone's rules, not 24 h arithmetic). */
    fun startOfDayMs(epochDay: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Half-open range [start, end) of the local day containing [ms]. */
    fun dayRangeMs(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LongRange {
        val day = epochDay(ms, zone)
        return startOfDayMs(day, zone) until startOfDayMs(day + 1, zone)
    }

    /** Half-open range of the local day before the one containing [ms]. */
    fun previousDayRangeMs(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LongRange {
        val day = epochDay(ms, zone) - 1
        return startOfDayMs(day, zone) until startOfDayMs(day + 1, zone)
    }

    /** Milliseconds from [ms] until the next local midnight. */
    fun msUntilNextMidnight(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        startOfDayMs(epochDay(ms, zone) + 1, zone) - ms

    /**
     * Emits once immediately and again just after every local midnight, so
     * "today" totals roll over on screen without waiting for some other write.
     */
    fun midnightTicker(zone: ZoneId = ZoneId.systemDefault()): Flow<Long> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(epochDay(now, zone))
            delay(msUntilNextMidnight(now, zone) + 1_000L)
        }
    }
}
